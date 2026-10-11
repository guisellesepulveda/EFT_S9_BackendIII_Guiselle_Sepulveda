package cl.bancoxyz.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.TransactionFailed;
import cl.bancoxyz.platform.events.payload.TransactionStep;
import cl.bancoxyz.platform.events.payload.TransactionType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/** Máquina de estados del pago y compensación, con Kafka y PostgreSQL reales (accounts-service se simula con eventos). */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/payments-service.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "INTERNAL_SERVICE_CLIENT_SECRET=internal-secret", "payments.retrier-enabled=false",
        "platform.outbox.poll-interval=100ms", "platform.kafka.initial-backoff=50ms", "platform.kafka.max-retries=2",
        "resilience4j.retry.instances.accounts-compensation.max-attempts=2",
        "resilience4j.retry.instances.accounts-compensation.wait-duration=20ms",
        "resilience4j.retry.instances.accounts.wait-duration=20ms"})
@AutoConfigureMockMvc
class PaymentsSagaIT extends AbstractPaymentsIT {

    static KafkaTap tap;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", () -> TestContainers.kafka().getBootstrapServers());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    EventCodec codec;
    @Autowired
    CircuitBreakerRegistry breakers;

    @BeforeEach
    void setUp() {
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        if (tap == null) {
            tap = new KafkaTap(TestContainers.kafka().getBootstrapServers(), Topics.TRANSACTIONS_REQUESTED,
                    Topics.TRANSACTIONS_COMPLETED, Topics.dlt(Topics.TRANSACTIONS_FAILED), Topics.SECURITY_ALERTS);
        }
    }

    private UUID create(String path, String body) throws Exception {
        String res = mvc.perform(post(path).with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(res).get("paymentId").asText());
    }

    private UUID transfer(UUID from, UUID to, String amount) throws Exception {
        return create("/payments/transfer", "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amount\":" + amount + "}");
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, id);
    }

    private void awaitStatus(UUID id, String expected) {
        await().ignoreExceptions().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(statusOf(id)).isEqualTo(expected));
    }

    private EventEnvelope<TransactionStep> step(String type, UUID paymentId, TransactionType t, UUID from, UUID to, String amount) {
        return EventEnvelope.of(type, new TransactionStep(paymentId, t, from, to, from, new BigDecimal(amount), "CLP", BigDecimal.TEN));
    }

    private void send(String topic, UUID paymentId, EventEnvelope<?> e) throws Exception {
        kafka.send(topic, paymentId.toString(), codec.write(e)).get();
    }

    private List<String> historyStatuses(UUID id) {
        return jdbc.queryForList("SELECT status FROM payment_history WHERE payment_id = ? ORDER BY id", String.class, id);
    }

    @Test
    void transferWalksPendingDebitedCompletedAndAnnouncesCompletion() throws Exception {
        UUID from = fake.account("1000");
        UUID to = fake.account("0");
        UUID id = transfer(from, to, "100");

        JsonNode requested = tap.awaitForPayment(Topics.TRANSACTIONS_REQUESTED, id);   // outbox → Kafka
        assertThat(requested.path("payload").path("type").asText()).isEqualTo("TRANSFER");
        assertThat(requested.path("payload").path("amount").decimalValue()).isEqualByComparingTo("100");

        send(Topics.TRANSACTIONS_DEBITED, id, step(EventTypes.TRANSACTION_DEBITED, id, TransactionType.TRANSFER, from, to, "100"));
        awaitStatus(id, "DEBITED");
        send(Topics.TRANSACTIONS_CREDITED, id, step(EventTypes.TRANSACTION_CREDITED, id, TransactionType.TRANSFER, from, to, "100"));
        awaitStatus(id, "COMPLETED");

        JsonNode completed = tap.awaitForPayment(Topics.TRANSACTIONS_COMPLETED, id);
        assertThat(completed.path("eventType").asText()).isEqualTo("transaction.completed");
        assertThat(historyStatuses(id)).containsExactly("PENDING", "DEBITED", "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM payment WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void duplicatedAndLateEventsDoNotChangeACompletedPayment() throws Exception {
        UUID from = fake.account("1000");
        UUID to = fake.account("0");
        UUID id = transfer(from, to, "10");
        EventEnvelope<TransactionStep> credited = step(EventTypes.TRANSACTION_CREDITED, id, TransactionType.TRANSFER, from, to, "10");
        send(Topics.TRANSACTIONS_CREDITED, id, credited);   // el crédito llega antes que el débito (consumidores en paralelo)
        awaitStatus(id, "COMPLETED");

        send(Topics.TRANSACTIONS_CREDITED, id, credited);   // reentrega exacta
        send(Topics.TRANSACTIONS_DEBITED, id, step(EventTypes.TRANSACTION_DEBITED, id, TransactionType.TRANSFER, from, to, "10")); // tardío
        tap.awaitGroupCaughtUp("payments-saga-credited", Topics.TRANSACTIONS_CREDITED);
        tap.awaitGroupCaughtUp("payments-saga-debited", Topics.TRANSACTIONS_DEBITED);

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        assertThat(tap.forPayment(Topics.TRANSACTIONS_COMPLETED, id)).hasSize(1);       // un solo "completed"
        assertThat(historyStatuses(id)).containsExactly("PENDING", "COMPLETED");
    }

    @Test
    void withdrawalCompletesOnDebitAndDepositOnCredit() throws Exception {
        UUID acc = fake.account("1000");
        UUID w = create("/payments/withdraw", "{\"fromAccountId\":\"" + acc + "\",\"amount\":10}");
        send(Topics.TRANSACTIONS_DEBITED, w, step(EventTypes.TRANSACTION_DEBITED, w, TransactionType.WITHDRAWAL, acc, null, "10"));
        awaitStatus(w, "COMPLETED");

        UUID d = create("/payments/deposit", "{\"toAccountId\":\"" + acc + "\",\"amount\":10}");
        send(Topics.TRANSACTIONS_CREDITED, d, step(EventTypes.TRANSACTION_CREDITED, d, TransactionType.DEPOSIT, null, acc, "10"));
        awaitStatus(d, "COMPLETED");
    }

    @Test
    void aFailedDebitFailsThePaymentWithoutCompensation() throws Exception {
        UUID acc = fake.account("1000");
        UUID id = create("/payments/withdraw", "{\"fromAccountId\":\"" + acc + "\",\"amount\":10}");
        send(Topics.TRANSACTIONS_FAILED, id, EventEnvelope.of(EventTypes.TRANSACTION_FAILED, new TransactionFailed(id,
                TransactionType.WITHDRAWAL, "DEBIT", "INSUFFICIENT_FUNDS", "Saldo insuficiente", false, acc, BigDecimal.TEN)));
        awaitStatus(id, "FAILED");

        assertThat(jdbc.queryForMap("SELECT failure_code, failure_reason FROM payment WHERE id = ?", id))
                .containsEntry("failure_code", "INSUFFICIENT_FUNDS");
        assertThat(fake.creditCalls.get()).isZero();                        // no hubo reembolso: nunca se debitó
        assertThat(tap.forPayment(Topics.TRANSACTIONS_COMPLETED, id)).isEmpty();
    }

    @Test
    void aFailedCreditTriggersTheRefundAndEndsCompensated() throws Exception {
        UUID from = fake.account("1000");
        UUID to = fake.account("0");
        UUID id = transfer(from, to, "250.50");
        send(Topics.TRANSACTIONS_DEBITED, id, step(EventTypes.TRANSACTION_DEBITED, id, TransactionType.TRANSFER, from, to, "250.50"));
        awaitStatus(id, "DEBITED");

        send(Topics.TRANSACTIONS_FAILED, id, EventEnvelope.of(EventTypes.TRANSACTION_FAILED, new TransactionFailed(id,
                TransactionType.TRANSFER, "CREDIT", "ACCOUNT_CLOSED", "Destino cerrado", true, from, new BigDecimal("250.50"))));
        awaitStatus(id, "COMPENSATED");

        // el reembolso fue una llamada idempotente (paymentId:REFUND), autenticada con el token interno
        assertThat(fake.creditBodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"operationId\":\"" + id + ":REFUND\"").contains("250.50");
        });
        assertThat(fake.creditAuth).containsOnly("Bearer internal-token-123");
        assertThat(historyStatuses(id)).containsExactly("PENDING", "DEBITED", "COMPENSATING", "COMPENSATED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payment WHERE id = ?", String.class, id)).isEqualTo("ACCOUNT_CLOSED");
    }

    @Test
    void ifAccountsIsDownTheRefundIsRetriedThenSentToTheDeadLetterTopicAndStaysCompensating() throws Exception {
        UUID from = fake.account("1000");
        UUID to = fake.account("0");
        UUID id = transfer(from, to, "40");
        fake.creditMode = "error";

        send(Topics.TRANSACTIONS_FAILED, id, EventEnvelope.of(EventTypes.TRANSACTION_FAILED, new TransactionFailed(id,
                TransactionType.TRANSFER, "CREDIT", "ACCOUNT_CLOSED", "Destino cerrado", true, from, new BigDecimal("40"))));

        JsonNode dead = tap.awaitForPayment(Topics.dlt(Topics.TRANSACTIONS_FAILED), id);   // agotó los reintentos → DLT
        assertThat(dead.path("eventType").asText()).isEqualTo("transaction.failed");
        assertThat(statusOf(id)).isEqualTo("COMPENSATING");                                 // el dinero NO se da por perdido
        assertThat(fake.creditCalls.get()).isGreaterThan(2);                              // Resilience4j + reintentos de Kafka
    }

    @Test
    void aRejectedRefundNeedsManualInterventionAndRaisesAHighSeverityAlert() throws Exception {
        UUID from = fake.account("1000");
        UUID to = fake.account("0");
        UUID id = transfer(from, to, "75");
        fake.creditMode = "rejected";

        send(Topics.TRANSACTIONS_FAILED, id, EventEnvelope.of(EventTypes.TRANSACTION_FAILED, new TransactionFailed(id,
                TransactionType.TRANSFER, "CREDIT", "ACCOUNT_CLOSED", "Destino cerrado", true, from, new BigDecimal("75"))));
        awaitStatus(id, "FAILED");

        assertThat(jdbc.queryForObject("SELECT failure_code FROM payment WHERE id = ?", String.class, id)).isEqualTo("COMPENSATION_REJECTED");
        JsonNode alert = tap.await(Topics.SECURITY_ALERTS, e -> e.path("payload").path("subject").asText().equals(id.toString()));
        assertThat(alert.path("payload").path("severity").asText()).isEqualTo("HIGH");
        assertThat(alert.path("payload").path("alertType").asText()).isEqualTo("COMPENSATION_FAILED");
    }
}

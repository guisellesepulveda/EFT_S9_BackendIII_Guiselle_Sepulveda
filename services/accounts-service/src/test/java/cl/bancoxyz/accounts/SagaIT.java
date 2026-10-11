package cl.bancoxyz.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.TransactionCompleted;
import cl.bancoxyz.platform.events.payload.TransactionRequested;
import cl.bancoxyz.platform.events.payload.TransactionType;

/** Pasos de la saga de accounts-service contra Kafka y PostgreSQL reales. */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "platform.outbox.poll-interval=100ms"})
class SagaIT extends AbstractAccountsIT {

    static KafkaTap tap;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", () -> TestContainers.kafka().getBootstrapServers());
    }

    /** Se crea con el contexto ya levantado: la app es quien crea los tópicos (3 particiones). */
    @BeforeEach
    void startTap() {
        if (tap == null) {
            tap = new KafkaTap(TestContainers.kafka().getBootstrapServers(), Topics.TRANSACTIONS_DEBITED, Topics.TRANSACTIONS_CREDITED,
                    Topics.TRANSACTIONS_FAILED, Topics.ACCOUNTS_LIFECYCLE);
        }
    }

    @AfterAll
    static void stopTap() {
        tap.close();
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cl.bancoxyz.accounts.clients.CustomerGateway customers;
    @Autowired
    cl.bancoxyz.accounts.api.AccountService accountService;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    EventCodec codec;

    private EventEnvelope<TransactionRequested> request(UUID paymentId, TransactionType type, UUID from, UUID to, String amount) {
        return EventEnvelope.of(EventTypes.TRANSACTION_REQUESTED,
                new TransactionRequested(paymentId, type, from, to, new BigDecimal(amount), "CLP"));
    }

    private void send(String topic, UUID key, EventEnvelope<?> e) throws Exception {
        kafka.send(topic, key.toString(), codec.write(e)).get();
    }

    @Test
    void transferRunsDebitThenCreditAndEmitsBothEvents() throws Exception {
        UUID from = account("500");
        UUID to = account("20");
        UUID payment = UUID.randomUUID();
        send(Topics.TRANSACTIONS_REQUESTED, payment, request(payment, TransactionType.TRANSFER, from, to, "120.50"));

        JsonNode debited = tap.awaitForPayment(Topics.TRANSACTIONS_DEBITED, payment);
        JsonNode credited = tap.awaitForPayment(Topics.TRANSACTIONS_CREDITED, payment);

        assertThat(debited.path("payload").path("balanceAfter").decimalValue()).isEqualByComparingTo("379.50");
        assertThat(credited.path("payload").path("balanceAfter").decimalValue()).isEqualByComparingTo("140.50");
        assertThat(credited.path("eventType").asText()).isEqualTo("transaction.credited");
        assertThat(balance(from)).isEqualByComparingTo("379.50");
        assertThat(balance(to)).isEqualByComparingTo("140.50");
        assertThat(jdbc.queryForList("SELECT movement_status FROM account_operation WHERE reference = ?", String.class, payment.toString()))
                .containsOnly("PENDING");

        // transaction.completed (lo emite payments) confirma los movimientos
        send(Topics.TRANSACTIONS_COMPLETED, payment, EventEnvelope.of(EventTypes.TRANSACTION_COMPLETED,
                new TransactionCompleted(payment, TransactionType.TRANSFER, from, to, new BigDecimal("120.50"), "CLP", Instant.now())));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT movement_status FROM account_operation WHERE reference = ?", String.class, payment.toString())).containsOnly("CONFIRMED"));
    }

    @Test
    void insufficientFundsFailsTheSagaWithoutMovingMoney() throws Exception {
        UUID from = account("50");
        UUID to = account("0");
        UUID payment = UUID.randomUUID();
        send(Topics.TRANSACTIONS_REQUESTED, payment, request(payment, TransactionType.TRANSFER, from, to, "80"));

        JsonNode failed = tap.awaitForPayment(Topics.TRANSACTIONS_FAILED, payment);
        assertThat(failed.path("payload").path("step").asText()).isEqualTo("DEBIT");
        assertThat(failed.path("payload").path("reasonCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(failed.path("payload").path("compensationRequired").asBoolean()).isFalse();
        assertThat(balance(from)).isEqualByComparingTo("50");
        assertThat(balance(to)).isEqualByComparingTo("0");
        assertThat(tap.forPayment(Topics.TRANSACTIONS_DEBITED, payment)).isEmpty();
    }

    @Test
    void creditToAClosedAccountRequestsCompensationOfTheDebit() throws Exception {
        UUID from = account("300");
        UUID closed = account(UUID.randomUUID(), "0", "CLOSED");
        UUID payment = UUID.randomUUID();
        send(Topics.TRANSACTIONS_REQUESTED, payment, request(payment, TransactionType.TRANSFER, from, closed, "100"));

        tap.awaitForPayment(Topics.TRANSACTIONS_DEBITED, payment);
        JsonNode failed = tap.awaitForPayment(Topics.TRANSACTIONS_FAILED, payment);
        assertThat(failed.path("payload").path("step").asText()).isEqualTo("CREDIT");
        assertThat(failed.path("payload").path("reasonCode").asText()).isEqualTo("ACCOUNT_CLOSED");
        assertThat(failed.path("payload").path("compensationRequired").asBoolean()).isTrue();
        assertThat(failed.path("payload").path("fromAccountId").asText()).isEqualTo(from.toString());
        assertThat(failed.path("payload").path("amount").decimalValue()).isEqualByComparingTo("100");
        // el origen quedó debitado: payments debe reembolsarlo (ver PaymentsSagaIT)
        assertThat(balance(from)).isEqualByComparingTo("200");
        assertThat(tap.forPayment(Topics.TRANSACTIONS_CREDITED, payment)).isEmpty();
    }

    @Test
    void redeliveredAndReplayedRequestsMoveMoneyOnlyOnce() throws Exception {
        UUID from = account("100");
        UUID to = account("0");
        UUID payment = UUID.randomUUID();
        EventEnvelope<TransactionRequested> same = request(payment, TransactionType.TRANSFER, from, to, "30");
        for (int i = 0; i < 3; i++) {
            send(Topics.TRANSACTIONS_REQUESTED, payment, same); // mismo eventId (reentrega)
        }
        // reintento del emisor: otro eventId, mismo pago → reproduce el resultado sin mover dinero
        send(Topics.TRANSACTIONS_REQUESTED, payment, request(payment, TransactionType.TRANSFER, from, to, "30"));

        tap.awaitGroupCaughtUp("accounts-saga-requested", Topics.TRANSACTIONS_REQUESTED);
        tap.awaitForPayment(Topics.TRANSACTIONS_CREDITED, payment);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(tap.forPayment(Topics.TRANSACTIONS_DEBITED, payment)).hasSize(2));
        tap.awaitGroupCaughtUp("accounts-saga-debited", Topics.TRANSACTIONS_DEBITED);

        assertThat(balance(from)).isEqualByComparingTo("70");   // un solo débito de 30
        assertThat(balance(to)).isEqualByComparingTo("30");     // un solo crédito de 30
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_operation WHERE reference = ?", Long.class, payment.toString())).isEqualTo(2);
    }

    @Test
    void withdrawalOnlyDebitsAndDepositOnlyCredits() throws Exception {
        UUID acc = account("100");
        UUID w = UUID.randomUUID();
        send(Topics.TRANSACTIONS_REQUESTED, w, request(w, TransactionType.WITHDRAWAL, acc, null, "40"));
        tap.awaitForPayment(Topics.TRANSACTIONS_DEBITED, w);
        assertThat(balance(acc)).isEqualByComparingTo("60");

        UUID d = UUID.randomUUID();
        send(Topics.TRANSACTIONS_REQUESTED, d, request(d, TransactionType.DEPOSIT, null, acc, "15"));
        tap.awaitForPayment(Topics.TRANSACTIONS_CREDITED, d);
        assertThat(balance(acc)).isEqualByComparingTo("75");
        tap.awaitGroupCaughtUp("accounts-saga-debited", Topics.TRANSACTIONS_DEBITED);
        assertThat(tap.forPayment(Topics.TRANSACTIONS_CREDITED, w)).isEmpty(); // un retiro no acredita a nadie
    }

    @Test
    void selfTransferIsRejectedAsInvalid() throws Exception {
        UUID acc = account("100");
        UUID payment = UUID.randomUUID();
        send(Topics.TRANSACTIONS_REQUESTED, payment, request(payment, TransactionType.TRANSFER, acc, acc, "10"));
        JsonNode failed = tap.awaitForPayment(Topics.TRANSACTIONS_FAILED, payment);
        assertThat(failed.path("payload").path("reasonCode").asText()).isEqualTo("INVALID_TRANSFER");
        assertThat(balance(acc)).isEqualByComparingTo("100");
    }

    @Test
    void openingAndClosingAnAccountPublishLifecycleEventsThroughTheOutbox() throws Exception {
        UUID customerId = UUID.randomUUID();
        org.mockito.Mockito.when(customers.find(customerId)).thenReturn(new cl.bancoxyz.accounts.clients.CustomerView(customerId, "ACTIVE", "A", "B"));

        var account = accountService.open(new cl.bancoxyz.accounts.api.AccountRequest(customerId, cl.bancoxyz.accounts.domain.AccountType.SAVINGS));
        JsonNode opened = tap.await(Topics.ACCOUNTS_LIFECYCLE, e -> e.path("payload").path("accountId").asText().equals(account.getId().toString())
                && e.path("eventType").asText().equals("account.opened"));
        assertThat(opened.path("payload").path("customerId").asText()).isEqualTo(customerId.toString());

        accountService.close(account.getId());
        tap.await(Topics.ACCOUNTS_LIFECYCLE, e -> e.path("payload").path("accountId").asText().equals(account.getId().toString())
                && e.path("eventType").asText().equals("account.closed"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class)).isZero());
    }
}

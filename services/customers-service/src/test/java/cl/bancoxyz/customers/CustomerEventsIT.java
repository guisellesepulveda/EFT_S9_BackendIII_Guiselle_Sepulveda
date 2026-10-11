package cl.bancoxyz.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cl.bancoxyz.customers.domain.Customer;
import cl.bancoxyz.customers.domain.CustomerProfileRepository;
import cl.bancoxyz.customers.domain.CustomerRepository;
import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.AccountLifecycle;
import cl.bancoxyz.platform.events.payload.SecurityAlert;
import cl.bancoxyz.platform.events.payload.TransactionCompleted;
import cl.bancoxyz.platform.events.payload.TransactionType;

/** Consumidores contra Kafka y PostgreSQL reales: idempotencia, DLT y proyecciones. */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "platform.kafka.initial-backoff=50ms", "platform.kafka.max-retries=2"})
class CustomerEventsIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", () -> TestContainers.kafka().getBootstrapServers());
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    EventCodec codec;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CustomerRepository customers;
    @Autowired
    CustomerProfileRepository profiles;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE customer, notification_log, security_alert, batch_job_event, processed_event CASCADE");
    }

    private Customer customer() {
        String n = UUID.randomUUID().toString().substring(0, 7);
        return customers.save(new Customer(String.format("%08d-1", Math.abs(n.hashCode()) % 100_000_000), "Luis", "Soto", n + "@example.com", null));
    }

    private void send(String topic, String key, EventEnvelope<?> event) throws Exception {
        kafka.send(topic, key, codec.write(event)).get();
    }

    private static AccountLifecycle lifecycle(UUID customerId) {
        return new AccountLifecycle(UUID.randomUUID(), customerId, "SAVINGS", Instant.now());
    }

    @Test
    void accountLifecycleEventsBuildTheCustomerProfile() throws Exception {
        Customer c = customer();
        send(Topics.ACCOUNTS_LIFECYCLE, c.getId().toString(), EventEnvelope.of(EventTypes.ACCOUNT_OPENED, lifecycle(c.getId())));
        send(Topics.ACCOUNTS_LIFECYCLE, c.getId().toString(), EventEnvelope.of(EventTypes.ACCOUNT_OPENED, lifecycle(c.getId())));
        send(Topics.ACCOUNTS_LIFECYCLE, c.getId().toString(), EventEnvelope.of(EventTypes.ACCOUNT_CLOSED, lifecycle(c.getId())));

        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var p = profiles.findById(c.getId()).orElseThrow();
            assertThat(p.getAccountsOpen()).isEqualTo(1);
            assertThat(p.getAccountsClosed()).isEqualTo(1);
        });
    }

    @Test
    void redeliveredEventsAreAppliedOnlyOnce() throws Exception {
        Customer c = customer();
        EventEnvelope<AccountLifecycle> opened = EventEnvelope.of(EventTypes.ACCOUNT_OPENED, lifecycle(c.getId()));
        for (int i = 0; i < 3; i++) {
            send(Topics.ACCOUNTS_LIFECYCLE, c.getId().toString(), opened); // mismo eventId, 3 entregas
        }
        // un evento distinto actúa como "marcador" de que ya se consumió todo lo anterior (misma partición por clave)
        send(Topics.ACCOUNTS_LIFECYCLE, c.getId().toString(), EventEnvelope.of(EventTypes.ACCOUNT_OPENED, lifecycle(c.getId())));

        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(profiles.findById(c.getId()).orElseThrow().getAccountsOpen()).isEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE consumer = 'profile'", Long.class)).isEqualTo(2);
    }

    @Test
    void eventForAnUnknownCustomerIsIgnoredWithoutPoisoningTheTopic() throws Exception {
        UUID ghost = UUID.randomUUID();
        Customer real = customer();
        send(Topics.ACCOUNTS_LIFECYCLE, ghost.toString(), EventEnvelope.of(EventTypes.ACCOUNT_OPENED, lifecycle(ghost)));
        send(Topics.ACCOUNTS_LIFECYCLE, real.getId().toString(), EventEnvelope.of(EventTypes.ACCOUNT_OPENED, lifecycle(real.getId())));
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(profiles.findById(real.getId()).orElseThrow().getAccountsOpen()).isEqualTo(1));
    }

    @Test
    void unreadableAndUnknownEventsEndInTheDeadLetterTopic() throws Exception {
        String dlt = Topics.dlt(Topics.ACCOUNTS_LIFECYCLE);
        String garbage = "esto-no-es-json-" + UUID.randomUUID();
        kafka.send(Topics.ACCOUNTS_LIFECYCLE, "k1", garbage).get();
        Customer c = customer();
        EventEnvelope<AccountLifecycle> weird = EventEnvelope.of("account.exploded", lifecycle(c.getId()));
        send(Topics.ACCOUNTS_LIFECYCLE, "k2", weird);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(dlt));
            List<String> values = new java.util.ArrayList<>();
            await().ignoreExceptions().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    values.add(rec.value());
                }
                assertThat(values).anyMatch(v -> v.equals(garbage)).anyMatch(v -> v.contains("account.exploded"));
            });
        }
        // la partición no quedó bloqueada: un evento válido posterior se procesa
        Customer ok = customer();
        send(Topics.ACCOUNTS_LIFECYCLE, "k3", EventEnvelope.of(EventTypes.ACCOUNT_OPENED, lifecycle(ok.getId())));
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(profiles.findById(ok.getId()).orElseThrow().getAccountsOpen()).isEqualTo(1));
    }

    @Test
    void completedTransactionsAreNotifiedOnce() throws Exception {
        UUID paymentId = UUID.randomUUID();
        EventEnvelope<TransactionCompleted> e = EventEnvelope.of(EventTypes.TRANSACTION_COMPLETED,
                new TransactionCompleted(paymentId, TransactionType.TRANSFER, UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("1500.50"), "CLP", Instant.now()));
        send(Topics.TRANSACTIONS_COMPLETED, paymentId.toString(), e);
        send(Topics.TRANSACTIONS_COMPLETED, paymentId.toString(), e); // reentrega

        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT message FROM notification_log WHERE payment_id = ?", String.class, paymentId))
                .singleElement().asString().contains("TRANSFER").contains("1500.50"));
    }

    @Test
    void securityAlertsAreStoredForAuditors() throws Exception {
        EventEnvelope<SecurityAlert> e = EventEnvelope.of(EventTypes.SECURITY_ALERT,
                new SecurityAlert("auth-server", "HIGH", "BRUTE_FORCE_SUSPECTED", "admin", "5 fallos", Instant.now()));
        send(Topics.SECURITY_ALERTS, "admin", e);
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT alert_type FROM security_alert WHERE subject = 'admin'", String.class)).containsExactly("BRUTE_FORCE_SUSPECTED"));
    }

    @Test
    void batchJobEventsFromBatchServiceAreMonitored() throws Exception {
        // Mismo JSON que publica batch-service (su propio sobre con el mismo contrato)
        String json = """
                {"eventId":"%s","eventType":"batch.job.failed","occurredAt":"%s","version":1,
                 "payload":{"jobName":"monthlyInterestJob","jobInstanceId":7,"jobExecutionId":9,"status":"FAILED",
                            "exitCode":"FAILED","attempt":1,"willRetry":true,"parameters":{"periodo":"MENSUAL"}}}
                """.formatted(UUID.randomUUID(), Instant.now());
        kafka.send(Topics.BATCH_JOB_EVENTS, "monthlyInterestJob", json).get();
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForMap(
                "SELECT job_name, status, attempt, will_retry FROM batch_job_event"))
                .containsEntry("job_name", "monthlyInterestJob").containsEntry("status", "FAILED")
                .containsEntry("attempt", 1).containsEntry("will_retry", true));
    }
}

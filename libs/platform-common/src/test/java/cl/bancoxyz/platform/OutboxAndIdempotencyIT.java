package cl.bancoxyz.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.idempotency.ProcessedEventStore;
import cl.bancoxyz.platform.outbox.OutboxPublisher;
import cl.bancoxyz.platform.outbox.OutboxService;

/** Outbox transaccional y consumidores idempotentes contra un PostgreSQL real (Kafka simulado). */
class OutboxAndIdempotencyIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static OutboxService outbox;
    static ProcessedEventStore processed;
    static EventCodec codec;

    @BeforeAll
    static void init() {
        PG.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
                CREATE TABLE outbox_event (
                  id BIGSERIAL PRIMARY KEY, event_id VARCHAR(64) NOT NULL UNIQUE, topic VARCHAR(100) NOT NULL,
                  msg_key VARCHAR(100), event_type VARCHAR(100) NOT NULL, payload TEXT NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT now(), published_at TIMESTAMP,
                  attempts INT NOT NULL DEFAULT 0, last_error TEXT, traceparent VARCHAR(100));
                CREATE TABLE processed_event (consumer VARCHAR(100) NOT NULL, event_id VARCHAR(64) NOT NULL,
                  processed_at TIMESTAMP NOT NULL DEFAULT now(), PRIMARY KEY (consumer, event_id));
                """);
        codec = new EventCodec(new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        outbox = new OutboxService(jdbc, codec);
        processed = new ProcessedEventStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE outbox_event, processed_event RESTART IDENTITY");
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaOk() {
        KafkaTemplate<String, String> k = mock(KafkaTemplate.class);
        when(k.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        return k;
    }

    private void publish(String topic, String key) {
        tx.executeWithoutResult(s -> outbox.publish(topic, key, EventEnvelope.of("test.event", java.util.Map.of("k", key))));
    }

    @Test
    void outboxInsertsInsideATransaction() {
        // Sin proxy transaccional de Spring (objeto creado a mano) la anotación no se aplica; la verificación real de
        // MANDATORY se hace en los ITs de los servicios. Aquí se comprueba que publicar dentro de una tx funciona.
        tx.executeWithoutResult(s -> outbox.publish("t", "k", EventEnvelope.of("x", "y")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class)).isEqualTo(1);
    }

    @Test
    void eventsAreStoredWithTheBusinessTransactionAndRolledBackWithIt() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.publish("t", "k1", EventEnvelope.of("test.event", "x"));
            throw new IllegalStateException("falla de negocio");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class)).isZero();

        publish("t", "k2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void publisherSendsPendingEventsInOrderAndMarksThem() {
        publish("topic.a", "k1");
        publish("topic.b", "k2");
        KafkaTemplate<String, String> kafka = kafkaOk();
        OutboxPublisher publisher = new OutboxPublisher(jdbc, kafka, tx, 10);

        publisher.publishPending();

        org.mockito.ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> sent =
                org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafka, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(r -> r.topic() + ":" + r.key()).containsExactly("topic.a:k1", "topic.b:k2");
        assertThat(publisher.pendingCount()).isZero();
        publisher.publishPending(); // nada pendiente → no reenvía
        verify(kafka, org.mockito.Mockito.times(2)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }

    @Test
    void failureStopsThePublicationToPreserveOrderAndKeepsTheEventPending() {
        publish("topic.a", "k1");
        publish("topic.b", "k2");
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker caído")));
        OutboxPublisher publisher = new OutboxPublisher(jdbc, kafka, tx, 10);

        publisher.publishPending();

        assertThat(publisher.pendingCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event ORDER BY id LIMIT 1", String.class))
                .contains("broker caído");
        verify(kafka, org.mockito.Mockito.times(1)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }

    @Test
    void concurrentPublishersNeverSendTheSameEventTwice() throws Exception {
        for (int i = 0; i < 40; i++) {
            publish("topic.c", "k" + i);
        }
        KafkaTemplate<String, String> kafka = kafkaOk();
        List<Thread> threads = java.util.stream.IntStream.range(0, 4).mapToObj(i -> new Thread(
                () -> new OutboxPublisher(jdbc, kafka, tx, 5).publishPending())).toList();
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        verify(kafka, org.mockito.Mockito.times(40)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class)).isZero();
    }

    private boolean first(String consumer, String eventId) {
        return Boolean.TRUE.equals(tx.execute(s -> processed.markIfFirst(consumer, eventId)));
    }

    @Test
    void theTraceContextOfTheOriginatingRequestTravelsWithTheEvent() {
        org.slf4j.MDC.put("traceId", "6ac9ad13ea93f31e9c3312f31ba8320e");
        org.slf4j.MDC.put("spanId", "543301bcf492aaf6");
        try {
            publish("topic.t", "k1");
        } finally {
            org.slf4j.MDC.clear();
        }
        KafkaTemplate<String, String> kafka = kafkaOk();
        new OutboxPublisher(jdbc, kafka, tx, 10).publishPending();

        org.mockito.ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> sent =
                org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(new String(sent.getValue().headers().lastHeader("traceparent").value()))
                .isEqualTo("00-6ac9ad13ea93f31e9c3312f31ba8320e-543301bcf492aaf6-01");
    }

    @Test
    void processedEventStoreAcceptsAnEventOnlyOncePerConsumer() {
        String id = UUID.randomUUID().toString();
        assertThat(first("accounts-debit", id)).isTrue();
        assertThat(first("accounts-debit", id)).isFalse();
        assertThat(first("payments", id)).isTrue(); // otro consumidor, mismo evento
    }
}

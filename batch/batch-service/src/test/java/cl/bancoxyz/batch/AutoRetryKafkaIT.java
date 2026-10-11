package cl.bancoxyz.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.batch.api.JobLaunchService;

/**
 * Evento batch.job.failed a Kafka + reintento automático: un job que falla (fallo simulado) se relanza solo,
 * continúa desde el último chunk confirmado y termina COMPLETED, emitiendo failed y luego completed.
 */
@TestPropertySource(properties = {
        "batch.events.enabled=true",
        "batch.auto-retry.enabled=true",
        "batch.auto-retry.max-attempts=3",
        "batch.auto-retry.delay=1s"
})
class AutoRetryKafkaIT extends AbstractIT {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    JobLaunchService launchService;
    @Autowired
    JobExplorer explorer;

    @Test
    void failedJobIsRetriedAutomaticallyAndEventsAreEmitted() throws Exception {
        JobExecution first = launchService.launch(INTEREST, Map.of(
                "inputFile", defaultInput(INTEREST).toString(), "simulateFailureAtChunk", "5"));

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of("batch.job.events"));

            // 2 ejecuciones de la misma instancia: la 1.ª FAILED y la 2.ª (reintento automático) COMPLETED
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
                var executions = explorer.getJobExecutions(first.getJobInstance());
                assertThat(executions).hasSize(2);
                assertThat(executions).extracting(JobExecution::getStatus)
                        .containsExactlyInAnyOrder(BatchStatus.FAILED, BatchStatus.COMPLETED);
            });

            List<JsonNode> events = new ArrayList<>();
            ObjectMapper mapper = new ObjectMapper();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    assertThat(rec.key()).isEqualTo(INTEREST);
                    events.add(mapper.readTree(rec.value()));
                }
                assertThat(events).hasSize(2);
            });

            // contrato versionado
            JsonNode failed = events.get(0);
            assertThat(failed.get("eventType").asText()).isEqualTo("batch.job.failed");
            assertThat(failed.get("version").asInt()).isEqualTo(1);
            assertThat(failed.get("eventId").asText()).isNotBlank();
            assertThat(failed.get("occurredAt").asText()).contains("T");
            assertThat(failed.get("payload").get("status").asText()).isEqualTo("FAILED");
            assertThat(failed.get("payload").get("attempt").asInt()).isEqualTo(1);
            assertThat(failed.get("payload").get("willRetry").asBoolean()).isTrue();
            assertThat(failed.get("payload").get("jobName").asText()).isEqualTo(INTEREST);

            JsonNode done = events.get(1);
            assertThat(done.get("eventType").asText()).isEqualTo("batch.job.completed");
            assertThat(done.get("payload").get("attempt").asInt()).isEqualTo(2);
            assertThat(done.get("payload").get("willRetry").asBoolean()).isFalse();
        }

        // el reintento continuó (no repitió) el trabajo: sin duplicados
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interest_calculation", Long.class)).isEqualTo(509);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM batch_rejected_record WHERE disposition = 'SKIPPED'", Long.class))
                .isEqualTo(489);
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    @Test
    void dailyJobPublishesAnAnomalyAlertToSecurityAlerts() throws Exception {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of("security.alerts"));
            JobExecution je = launchService.launch(DAILY, Map.of("inputFile", defaultInput(DAILY).toString()));

            ObjectMapper mapper = new ObjectMapper();
            List<JsonNode> alerts = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    alerts.add(mapper.readTree(rec.value()));
                }
                assertThat(alerts).isNotEmpty();
            });
            JsonNode alert = alerts.get(0);
            assertThat(alert.get("eventType").asText()).isEqualTo("security.alert");
            assertThat(alert.get("payload").get("source").asText()).isEqualTo("batch-service");
            assertThat(alert.get("payload").get("alertType").asText()).isEqualTo("DATA_ANOMALIES_DETECTED");
            assertThat(alert.get("payload").get("severity").asText()).isEqualTo("MEDIUM"); // 1231 anomalías > umbral de 100
            assertThat(alert.get("payload").get("subject").asText()).isEqualTo(DAILY);
            assertThat(je.getJobInstance().getJobName()).isEqualTo(DAILY);
        }
    }
}

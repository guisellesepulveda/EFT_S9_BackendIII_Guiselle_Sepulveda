package cl.bancoxyz.batch.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Publica en el tópico batch.job.events. Un fallo de Kafka nunca debe romper el job: se registra y se continúa. */
public class KafkaJobEventPublisher implements JobEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobEventPublisher.class);
    private static final String SECURITY_ALERTS_TOPIC = "security.alerts";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public KafkaJobEventPublisher(KafkaTemplate<String, String> kafka, String topic, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    @Override
    public void publishAlert(String severity, String alertType, String subject, String detail) {
        var alert = new java.util.LinkedHashMap<String, Object>();
        alert.put("source", "batch-service");
        alert.put("severity", severity);
        alert.put("alertType", alertType);
        alert.put("subject", subject);
        alert.put("detail", detail);
        alert.put("occurredAt", java.time.Instant.now());
        try {
            kafka.send(SECURITY_ALERTS_TOPIC, subject, json.writeValueAsString(EventEnvelope.of("security.alert", alert)));
        } catch (RuntimeException | JsonProcessingException e) {
            log.error("Error enviando la alerta {} a Kafka: {}", alertType, e.toString());
        }
    }

    @Override
    public void publish(String eventType, JobEventPayload payload) {
        EventEnvelope<JobEventPayload> event = EventEnvelope.of(eventType, payload);
        try {
            kafka.send(topic, payload.jobName(), json.writeValueAsString(event)).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("No se pudo publicar {} en {}: {}", eventType, topic, ex.toString());
                } else {
                    log.info("Evento {} publicado en {} (partición {}, offset {})", eventType, topic,
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
        } catch (RuntimeException | JsonProcessingException e) {
            log.error("Error enviando {} a Kafka: {}", eventType, e.toString());
        }
    }
}

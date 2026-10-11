package cl.bancoxyz.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publicador del outbox. Toma filas pendientes con FOR UPDATE SKIP LOCKED, por lo que varias instancias del
 * servicio (escalado horizontal) pueden publicar a la vez sin duplicar ni bloquearse. Entrega "al menos una vez":
 * los consumidores son idempotentes por eventId. Ante un fallo se detiene para conservar el orden.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Tracer tracer;           // opcionales: sin tracing se publica igual
    private final Propagator propagator;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, int batchSize) {
        this(jdbc, kafka, tx, batchSize, null, null);
    }

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, int batchSize,
                           Tracer tracer, Propagator propagator) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval:500ms}")
    public void publishPending() {
        Integer sent;
        do {
            sent = tx.execute(status -> publishBatch());
        } while (sent != null && sent == batchSize);
    }

    int publishBatch() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_id, topic, msg_key, payload, traceparent FROM outbox_event
                WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                """, batchSize);
        int sent = 0;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>((String) row.get("topic"),
                        (String) row.get("msg_key"), (String) row.get("payload"));
                String traceparent = (String) row.get("traceparent");
                if (traceparent != null) {
                    // Cabecera de respaldo; con tracing activo la observación de Kafka la reemplaza por la del span hijo
                    record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
                }
                sendInOriginalTrace(record, traceparent);
                jdbc.update("UPDATE outbox_event SET published_at = now(), attempts = attempts + 1 WHERE id = ?", id);
                sent++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("No se pudo publicar el evento {} ({}): {}", row.get("event_id"), row.get("topic"), e.toString());
                jdbc.update("UPDATE outbox_event SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        String.valueOf(e).substring(0, Math.min(500, String.valueOf(e).length())), id);
                break; // conserva el orden: no se publican los siguientes hasta resolver este
            }
        }
        return sent;
    }

    /**
     * El publicador corre en otro hilo y momento que la petición original. Se restaura su contexto de traza
     * (traceparent guardado con el evento) para que el span de envío y los de los consumidores cuelguen de la MISMA traza.
     */
    private void sendInOriginalTrace(ProducerRecord<String, String> record, String traceparent) throws Exception {
        if (tracer == null || propagator == null || traceparent == null) {
            kafka.send(record).get(10, TimeUnit.SECONDS);
            return;
        }
        Span span = propagator.extract(Map.of("traceparent", traceparent), Map::get).name("outbox publish").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public long pendingCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }
}

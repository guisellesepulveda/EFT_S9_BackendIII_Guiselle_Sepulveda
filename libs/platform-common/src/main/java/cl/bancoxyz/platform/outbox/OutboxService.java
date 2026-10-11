package cl.bancoxyz.platform.outbox;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;

/**
 * Transactional Outbox: el evento se inserta en la MISMA transacción que el cambio de negocio
 * (propagation MANDATORY: si no hay transacción activa es un error de programación). Un publicador
 * programado lo envía luego a Kafka, de modo que no hay dual-write ni eventos perdidos.
 * Requiere la tabla outbox_event (ver docs/adr/ADR-005 y las migraciones de cada servicio).
 */
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final EventCodec codec;

    public OutboxService(JdbcTemplate jdbc, EventCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String key, EventEnvelope<?> event) {
        jdbc.update("""
                INSERT INTO outbox_event (event_id, topic, msg_key, event_type, payload, traceparent)
                VALUES (?, ?, ?, ?, ?, ?)
                """, event.eventId(), topic, key, event.eventType(), codec.write(event), currentTraceparent());
    }

    /**
     * Contexto de traza (W3C traceparent) de la petición/mensaje que originó el evento. El publicador del outbox corre en
     * otro hilo y momento, así que se guarda junto al evento para que la traza continúe a través de Kafka.
     */
    static String currentTraceparent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null) {
            return null;
        }
        return "00-" + "0".repeat(Math.max(0, 32 - traceId.length())) + traceId + "-"
                + "0".repeat(Math.max(0, 16 - spanId.length())) + spanId + "-01";
    }
}

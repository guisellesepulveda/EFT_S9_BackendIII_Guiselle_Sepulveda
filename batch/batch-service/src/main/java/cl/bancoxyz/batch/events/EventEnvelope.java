package cl.bancoxyz.batch.events;

import java.time.Instant;
import java.util.UUID;

/** Contrato versionado de eventos (eventId, eventType, occurredAt, version, payload). */
public record EventEnvelope<T>(String eventId, String eventType, Instant occurredAt, int version, T payload) {

    public static final int CURRENT_VERSION = 1;

    public static <T> EventEnvelope<T> of(String eventType, T payload) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), eventType, Instant.now(), CURRENT_VERSION, payload);
    }
}

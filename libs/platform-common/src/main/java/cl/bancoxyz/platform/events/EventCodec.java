package cl.bancoxyz.platform.events;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** (De)serialización del sobre de eventos. Los consumidores reciben String y parsean aquí (sin acoplar deserializadores). */
public class EventCodec {

    private final ObjectMapper mapper;

    public EventCodec(ObjectMapper mapper) {
        // Los montos son dinero: nunca pasar por double al leer el árbol JSON, y conservar la escala (1500.50)
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    }

    public String write(EventEnvelope<?> event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("No se pudo serializar el evento " + event.eventType(), e);
        }
    }

    /** Lee el sobre dejando el payload como árbol JSON; falla con IllegalArgumentException (no reintentable) si es inválido. */
    public EventEnvelope<JsonNode> read(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            for (String field : new String[] {"eventId", "eventType", "occurredAt", "version", "payload"}) {
                if (!root.hasNonNull(field)) {
                    throw new IllegalArgumentException("Evento inválido: falta el campo " + field);
                }
            }
            return new EventEnvelope<>(root.get("eventId").asText(), root.get("eventType").asText(),
                    mapper.treeToValue(root.get("occurredAt"), Instant.class), root.get("version").asInt(),
                    root.get("payload"));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Evento ilegible: " + e.getOriginalMessage(), e);
        }
    }

    public <T> T payload(EventEnvelope<JsonNode> event, Class<T> type) {
        try {
            return mapper.treeToValue(event.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload inválido para " + event.eventType() + ": " + e.getOriginalMessage(), e);
        }
    }
}

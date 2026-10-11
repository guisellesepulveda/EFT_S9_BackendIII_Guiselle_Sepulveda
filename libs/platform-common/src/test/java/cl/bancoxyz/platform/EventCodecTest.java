package cl.bancoxyz.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.TransactionRequested;
import cl.bancoxyz.platform.events.payload.TransactionType;

class EventCodecTest {

    private final EventCodec codec = new EventCodec(new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test
    void roundTripKeepsTheVersionedContract() {
        TransactionRequested payload = new TransactionRequested(UUID.randomUUID(), TransactionType.TRANSFER,
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1500.50"), "CLP");
        EventEnvelope<TransactionRequested> event = EventEnvelope.of(EventTypes.TRANSACTION_REQUESTED, payload);

        String json = codec.write(event);
        EventEnvelope<JsonNode> read = codec.read(json);

        assertThat(json).contains("\"eventId\"", "\"eventType\":\"transaction.requested\"", "\"version\":1", "\"occurredAt\":\"");
        assertThat(read.eventId()).isEqualTo(event.eventId());
        assertThat(read.version()).isEqualTo(1);
        assertThat(codec.payload(read, TransactionRequested.class)).isEqualTo(payload);
    }

    @Test
    void malformedOrIncompleteEventsFailAsNonRetryable() {
        assertThatThrownBy(() -> codec.read("{not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.read("{\"eventId\":\"x\"}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void everyTopicHasADeadLetterTopicName() {
        assertThat(Topics.ALL).hasSize(8).doesNotHaveDuplicates();
        assertThat(Topics.dlt(Topics.TRANSACTIONS_FAILED)).isEqualTo("transactions.failed.DLT");
    }
}

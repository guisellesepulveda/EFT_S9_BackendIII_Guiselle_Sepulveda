package cl.bancoxyz.accounts.saga;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.Topics;

/** Listeners Kafka; la lógica transaccional está en SagaProcessor. Cada tópico tiene su propio grupo de consumidores. */
@Component
class SagaConsumers {

    private final SagaProcessor processor;
    private final EventCodec codec;

    SagaConsumers(SagaProcessor processor, EventCodec codec) {
        this.processor = processor;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS_REQUESTED, groupId = "accounts-saga-requested")
    void requested(String message) {
        processor.onRequested(codec.read(message));
    }

    @KafkaListener(topics = Topics.TRANSACTIONS_DEBITED, groupId = "accounts-saga-debited")
    void debited(String message) {
        processor.onDebited(codec.read(message));
    }

    @KafkaListener(topics = Topics.TRANSACTIONS_COMPLETED, groupId = "accounts-projection")
    void completed(String message) {
        processor.onCompleted(codec.read(message));
    }
}

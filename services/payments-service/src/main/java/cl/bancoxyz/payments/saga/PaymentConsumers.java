package cl.bancoxyz.payments.saga;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.Topics;

@Component
class PaymentConsumers {

    private final PaymentSaga saga;
    private final EventCodec codec;

    PaymentConsumers(PaymentSaga saga, EventCodec codec) {
        this.saga = saga;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS_DEBITED, groupId = "payments-saga-debited")
    void debited(String message) {
        saga.onDebited(codec.read(message));
    }

    @KafkaListener(topics = Topics.TRANSACTIONS_CREDITED, groupId = "payments-saga-credited")
    void credited(String message) {
        saga.onCredited(codec.read(message));
    }

    /** transactions.failed → compensación (si corresponde). Un fallo del reembolso se reintenta y termina en la DLT. */
    @KafkaListener(topics = Topics.TRANSACTIONS_FAILED, groupId = "payments-saga-failed")
    void failed(String message) {
        saga.onFailed(codec.read(message)).ifPresent(saga::compensate);
    }
}

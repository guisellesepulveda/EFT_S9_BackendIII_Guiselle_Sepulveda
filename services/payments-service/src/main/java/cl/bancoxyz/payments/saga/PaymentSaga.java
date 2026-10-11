package cl.bancoxyz.payments.saga;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import cl.bancoxyz.payments.clients.Gateways;
import cl.bancoxyz.payments.domain.Payment;
import cl.bancoxyz.payments.domain.PaymentRepository;
import cl.bancoxyz.payments.domain.PaymentStatus;
import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.SecurityAlert;
import cl.bancoxyz.platform.events.payload.TransactionCompleted;
import cl.bancoxyz.platform.events.payload.TransactionFailed;
import cl.bancoxyz.platform.events.payload.TransactionStep;
import cl.bancoxyz.platform.events.payload.TransactionType;
import cl.bancoxyz.platform.idempotency.ProcessedEventStore;
import cl.bancoxyz.platform.outbox.OutboxService;
import cl.bancoxyz.platform.web.ApiException;

/**
 * Máquina de estados del pago, dirigida por eventos. Cada handler es idempotente por dos vías: processed_event
 * (misma entrega repetida) y transiciones condicionales (evento tardío o repetido con otro eventId).
 */
@Service
public class PaymentSaga {

    private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);

    /** Orden de reembolso: se ejecuta FUERA de la transacción (llamada remota). */
    public record CompensationOrder(UUID paymentId, UUID fromAccountId, java.math.BigDecimal amount) {
    }

    private final PaymentRepository payments;
    private final OutboxService outbox;
    private final ProcessedEventStore processed;
    private final EventCodec codec;
    private final Gateways gateways;
    private final TransactionTemplate tx;

    public PaymentSaga(PaymentRepository payments, OutboxService outbox, ProcessedEventStore processed, EventCodec codec,
                       Gateways gateways, PlatformTransactionManager txManager) {
        this.payments = payments;
        this.outbox = outbox;
        this.processed = processed;
        this.codec = codec;
        this.gateways = gateways;
        this.tx = new TransactionTemplate(txManager);
    }

    @Transactional
    public void onDebited(EventEnvelope<JsonNode> event) {
        if (!processed.markIfFirst("payments-debited", event.eventId())) {
            return;
        }
        TransactionStep s = codec.payload(event, TransactionStep.class);
        if (s.type() == TransactionType.WITHDRAWAL) {
            complete(s.paymentId(), "Retiro debitado");
        } else if (payments.transition(s.paymentId(), PaymentStatus.DEBITED, null, null, PaymentStatus.PENDING)) {
            payments.addHistory(s.paymentId(), PaymentStatus.DEBITED, "Origen debitado; acreditando destino");
        }
    }

    @Transactional
    public void onCredited(EventEnvelope<JsonNode> event) {
        if (!processed.markIfFirst("payments-credited", event.eventId())) {
            return;
        }
        TransactionStep s = codec.payload(event, TransactionStep.class);
        complete(s.paymentId(), s.type() == TransactionType.DEPOSIT ? "Depósito acreditado" : "Destino acreditado");
    }

    /**
     * transaction.failed. A diferencia de los demás handlers NO usa processed_event: la compensación hace una llamada
     * remota fuera de la transacción, y si fallara (accounts caído) la reentrega de Kafka debe poder reintentarla.
     * La idempotencia la dan el estado del pago (transiciones condicionales) y la operación de reembolso (paymentId:REFUND).
     */
    @Transactional
    public Optional<CompensationOrder> onFailed(EventEnvelope<JsonNode> event) {
        TransactionFailed f = codec.payload(event, TransactionFailed.class);
        String detail = f.step() + ": " + f.reasonCode();
        if (f.compensationRequired()) {
            if (payments.transition(f.paymentId(), PaymentStatus.COMPENSATING, f.reasonCode(), f.reason(),
                    PaymentStatus.PENDING, PaymentStatus.DEBITED)) {
                payments.addHistory(f.paymentId(), PaymentStatus.COMPENSATING, "Falló " + detail + "; se reembolsa el origen");
            } else if (payments.findById(f.paymentId()).map(p -> p.status() != PaymentStatus.COMPENSATING).orElse(true)) {
                return Optional.empty(); // ya compensado, fallido o inexistente: nada que hacer
            }
            // Primera entrega o reentrega con la compensación aún pendiente → (re)intentar el reembolso
            return Optional.of(new CompensationOrder(f.paymentId(), f.fromAccountId(), f.amount()));
        }
        if (payments.transition(f.paymentId(), PaymentStatus.FAILED, f.reasonCode(), f.reason(),
                PaymentStatus.PENDING, PaymentStatus.DEBITED)) {
            payments.addHistory(f.paymentId(), PaymentStatus.FAILED, "Falló " + detail);
        }
        return Optional.empty();
    }

    private void complete(UUID paymentId, String detail) {
        if (!payments.transition(paymentId, PaymentStatus.COMPLETED, null, null, PaymentStatus.PENDING, PaymentStatus.DEBITED)) {
            log.debug("Pago {} ya no está en curso: evento ignorado", paymentId);
            return;
        }
        payments.addHistory(paymentId, PaymentStatus.COMPLETED, detail);
        Payment p = payments.findById(paymentId).orElseThrow();
        outbox.publish(Topics.TRANSACTIONS_COMPLETED, paymentId.toString(), EventEnvelope.of(EventTypes.TRANSACTION_COMPLETED,
                new TransactionCompleted(p.id(), p.type(), p.fromAccountId(), p.toAccountId(), p.amount(), p.currency(),
                        Instant.now())));
        log.info("Pago {} COMPLETADO", paymentId);
    }

    /**
     * Compensación de la saga: reembolsa el origen con una operación idempotente (paymentId:REFUND).
     * Si accounts-service no responde, la excepción sube (Kafka reintenta con backoff y luego envía a la DLT; además
     * el reintentador reprocesa los pagos que queden en COMPENSATING).
     */
    public void compensate(CompensationOrder order) {
        try {
            gateways.refund(order.fromAccountId(), order.paymentId(), order.amount());
        } catch (ApiException e) {
            if (e.status().is4xxClientError()) {
                finishCompensation(order, false, e.code());
                return;
            }
            throw e;
        }
        finishCompensation(order, true, null);
    }

    private void finishCompensation(CompensationOrder order, boolean refunded, String code) {
        tx.executeWithoutResult(status -> {
            if (refunded) {
                if (payments.transition(order.paymentId(), PaymentStatus.COMPENSATED, null, null, PaymentStatus.COMPENSATING)) {
                    payments.addHistory(order.paymentId(), PaymentStatus.COMPENSATED, "Origen reembolsado");
                    log.info("Pago {} COMPENSADO (reembolso aplicado)", order.paymentId());
                }
            } else if (payments.transition(order.paymentId(), PaymentStatus.FAILED, "COMPENSATION_REJECTED", code,
                    PaymentStatus.COMPENSATING)) {
                payments.addHistory(order.paymentId(), PaymentStatus.FAILED, "El reembolso fue rechazado: " + code);
                // Dinero debitado sin reembolso: requiere atención manual → alerta de alta severidad
                outbox.publish(Topics.SECURITY_ALERTS, order.paymentId().toString(), EventEnvelope.of(EventTypes.SECURITY_ALERT,
                        new SecurityAlert("payments-service", "HIGH", "COMPENSATION_FAILED", order.paymentId().toString(),
                                "Reembolso rechazado (" + code + ") de " + order.amount() + " a la cuenta " + order.fromAccountId(),
                                Instant.now())));
                log.error("Pago {}: reembolso rechazado ({}). Requiere intervención manual", order.paymentId(), code);
            }
        });
    }
}

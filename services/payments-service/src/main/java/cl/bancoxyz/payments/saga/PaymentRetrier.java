package cl.bancoxyz.payments.saga;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.payments.config.PaymentsProperties;
import cl.bancoxyz.payments.domain.Payment;
import cl.bancoxyz.payments.domain.PaymentRepository;
import cl.bancoxyz.payments.domain.PaymentStatus;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.TransactionRequested;
import cl.bancoxyz.platform.outbox.OutboxService;

/**
 * Reintento de pagos sin avance: un pago PENDING demasiado tiempo se vuelve a emitir como transaction.requested
 * (accounts-service es idempotente, así que reproduce el resultado sin mover dinero otra vez) hasta max-request-attempts.
 * También retoma las compensaciones que quedaron a medias. Varias instancias conviven gracias a FOR UPDATE SKIP LOCKED.
 */
@Component
@ConditionalOnProperty(prefix = "payments", name = "retrier-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRetrier {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetrier.class);

    private final PaymentRepository payments;
    private final OutboxService outbox;
    private final PaymentSaga saga;
    private final PaymentsProperties props;
    private final TransactionTemplate tx;

    public PaymentRetrier(PaymentRepository payments, OutboxService outbox, PaymentSaga saga, PaymentsProperties props,
                          PlatformTransactionManager txManager) {
        this.payments = payments;
        this.outbox = outbox;
        this.saga = saga;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${payments.retrier-interval:10s}", initialDelayString = "${payments.retrier-interval:10s}")
    public void retryStalePayments() {
        List<PaymentSaga.CompensationOrder> compensations = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (Payment p : payments.lockStalePending(props.pendingRetryAfter(), 50)) {
                if (p.requestAttempts() >= props.maxRequestAttempts()) {
                    if (payments.transition(p.id(), PaymentStatus.FAILED, "TIMEOUT_REVIEW_REQUIRED",
                            "Sin respuesta tras " + p.requestAttempts() + " intentos; revisar manualmente", PaymentStatus.PENDING)) {
                        payments.addHistory(p.id(), PaymentStatus.FAILED, "Agotados los reintentos sin respuesta de accounts-service");
                        log.error("Pago {} sin respuesta tras {} intentos: requiere revisión", p.id(), p.requestAttempts());
                    }
                    continue;
                }
                payments.bumpAttempt(p.id());
                payments.addHistory(p.id(), PaymentStatus.PENDING, "Reintento #" + (p.requestAttempts() + 1) + " (sin avance)");
                outbox.publish(Topics.TRANSACTIONS_REQUESTED, p.id().toString(), EventEnvelope.of(EventTypes.TRANSACTION_REQUESTED,
                        new TransactionRequested(p.id(), p.type(), p.fromAccountId(), p.toAccountId(), p.amount(), p.currency())));
                log.warn("Pago {} PENDING sin avance: se reenvía transaction.requested (intento {})", p.id(), p.requestAttempts() + 1);
            }
            for (Payment p : payments.lockStaleCompensating(props.pendingRetryAfter(), 50)) {
                payments.touch(p.id());
                compensations.add(new PaymentSaga.CompensationOrder(p.id(), p.fromAccountId(), p.amount()));
            }
        });
        for (PaymentSaga.CompensationOrder order : compensations) {
            try {
                saga.compensate(order);
            } catch (RuntimeException e) {
                log.warn("La compensación del pago {} sigue pendiente: {}", order.paymentId(), e.toString());
            }
        }
    }
}

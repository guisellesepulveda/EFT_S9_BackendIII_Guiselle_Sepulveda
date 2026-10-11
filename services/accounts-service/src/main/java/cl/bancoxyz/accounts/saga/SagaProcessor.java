package cl.bancoxyz.accounts.saga;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import cl.bancoxyz.accounts.ledger.LedgerService;
import cl.bancoxyz.accounts.ledger.OperationResult;
import cl.bancoxyz.accounts.ledger.OperationType;
import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.TransactionCompleted;
import cl.bancoxyz.platform.events.payload.TransactionFailed;
import cl.bancoxyz.platform.events.payload.TransactionRequested;
import cl.bancoxyz.platform.events.payload.TransactionStep;
import cl.bancoxyz.platform.events.payload.TransactionType;
import cl.bancoxyz.platform.idempotency.ProcessedEventStore;
import cl.bancoxyz.platform.outbox.OutboxService;

/**
 * Pasos de la saga coreografiada que ejecuta accounts-service. Cada paso es una transacción local:
 * (1) movimiento en el libro mayor + (2) evento resultado en el outbox, ambos atómicos.
 * <pre>
 *  transaction.requested ─► [DÉBITO origen] ─► transaction.debited ─► [CRÉDITO destino] ─► transaction.credited
 *                                │                                          │
 *                                └─► transaction.failed                     └─► transaction.failed (compensationRequired)
 * </pre>
 * Reentregas (mismo eventId) se ignoran; reintentos del emisor (otro eventId, mismo pago) reproducen el resultado ya
 * registrado, sin mover dinero otra vez.
 */
@Service
public class SagaProcessor {

    private static final Logger log = LoggerFactory.getLogger(SagaProcessor.class);

    private final LedgerService ledger;
    private final OutboxService outbox;
    private final ProcessedEventStore processed;
    private final EventCodec codec;

    public SagaProcessor(LedgerService ledger, OutboxService outbox, ProcessedEventStore processed, EventCodec codec) {
        this.ledger = ledger;
        this.outbox = outbox;
        this.processed = processed;
        this.codec = codec;
    }

    @Transactional
    public void onRequested(EventEnvelope<JsonNode> event) {
        if (!processed.markIfFirst("saga-requested", event.eventId())) {
            return;
        }
        TransactionRequested r = codec.payload(event, TransactionRequested.class);
        String ref = r.paymentId().toString();
        switch (r.type()) {
            case WITHDRAWAL -> debit(r, ref);
            case TRANSFER -> {
                if (r.fromAccountId() == null || r.toAccountId() == null || r.fromAccountId().equals(r.toAccountId())) {
                    fail(r, "DEBIT", "INVALID_TRANSFER", "La transferencia requiere cuentas de origen y destino distintas", false);
                } else {
                    debit(r, ref);
                }
            }
            case DEPOSIT -> {
                OperationResult res = ledger.apply(ref + ":CREDIT", r.toAccountId(), OperationType.CREDIT, r.amount(), ref, "Depósito");
                if (res.applied()) {
                    publishStep(Topics.TRANSACTIONS_CREDITED, EventTypes.TRANSACTION_CREDITED, r, r.toAccountId(), res);
                } else {
                    fail(r, "CREDIT", res.reasonCode(), "No se pudo acreditar el depósito", false);
                }
            }
        }
    }

    private void debit(TransactionRequested r, String ref) {
        OperationResult res = ledger.apply(ref + ":DEBIT", r.fromAccountId(), OperationType.DEBIT, r.amount(), ref,
                r.type() == TransactionType.TRANSFER ? "Transferencia enviada" : "Retiro");
        if (res.applied()) {
            publishStep(Topics.TRANSACTIONS_DEBITED, EventTypes.TRANSACTION_DEBITED, r, r.fromAccountId(), res);
        } else {
            fail(r, "DEBIT", res.reasonCode(), "No se pudo debitar la cuenta de origen", false);
        }
    }

    /** Paso 2 de una transferencia: acredita el destino tras el débito. */
    @Transactional
    public void onDebited(EventEnvelope<JsonNode> event) {
        if (!processed.markIfFirst("saga-debited", event.eventId())) {
            return;
        }
        TransactionStep s = codec.payload(event, TransactionStep.class);
        if (s.type() != TransactionType.TRANSFER) {
            return; // en un retiro el débito es el último paso: payments completa el pago
        }
        String ref = s.paymentId().toString();
        OperationResult res = ledger.apply(ref + ":CREDIT", s.toAccountId(), OperationType.CREDIT, s.amount(), ref, "Transferencia recibida");
        if (res.applied()) {
            outbox.publish(Topics.TRANSACTIONS_CREDITED, ref, EventEnvelope.of(EventTypes.TRANSACTION_CREDITED,
                    new TransactionStep(s.paymentId(), s.type(), s.fromAccountId(), s.toAccountId(), s.toAccountId(),
                            s.amount(), s.currency(), res.balanceAfter())));
        } else {
            // El origen ya fue debitado: payments debe compensar (reembolsar)
            outbox.publish(Topics.TRANSACTIONS_FAILED, ref, EventEnvelope.of(EventTypes.TRANSACTION_FAILED,
                    new TransactionFailed(s.paymentId(), s.type(), "CREDIT", res.reasonCode(),
                            "No se pudo acreditar la cuenta de destino", true, s.fromAccountId(), s.amount())));
            log.warn("Pago {}: crédito rechazado ({}), se solicita compensación", ref, res.reasonCode());
        }
    }

    /** Proyección: el pago se completó → sus movimientos quedan CONFIRMED. */
    @Transactional
    public void onCompleted(EventEnvelope<JsonNode> event) {
        if (!processed.markIfFirst("projection-completed", event.eventId())) {
            return;
        }
        TransactionCompleted c = codec.payload(event, TransactionCompleted.class);
        int n = ledger.confirmMovements(c.paymentId());
        log.info("Pago {} completado: {} movimiento(s) confirmados", c.paymentId(), n);
    }

    private void publishStep(String topic, String type, TransactionRequested r, UUID accountId, OperationResult res) {
        outbox.publish(topic, r.paymentId().toString(), EventEnvelope.of(type,
                new TransactionStep(r.paymentId(), r.type(), r.fromAccountId(), r.toAccountId(), accountId, r.amount(),
                        r.currency(), res.balanceAfter())));
    }

    private void fail(TransactionRequested r, String step, String code, String reason, boolean compensate) {
        outbox.publish(Topics.TRANSACTIONS_FAILED, r.paymentId().toString(), EventEnvelope.of(EventTypes.TRANSACTION_FAILED,
                new TransactionFailed(r.paymentId(), r.type(), step, code, reason, compensate, r.fromAccountId(), r.amount())));
    }
}

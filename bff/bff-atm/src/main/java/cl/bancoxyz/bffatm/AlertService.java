package cl.bancoxyz.bffatm;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.SecurityAlert;
import cl.bancoxyz.platform.outbox.OutboxService;

/** Alertas a security.alerts por Transactional Outbox: se confirman junto con la auditoría y nunca se pierden. */
@Service
public class AlertService {

    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public AlertService(OutboxService outbox, PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Dentro de una transacción ya abierta. */
    public void raiseInTx(String severity, String type, String subject, String detail) {
        outbox.publish(Topics.SECURITY_ALERTS, subject == null ? "atm" : subject, EventEnvelope.of(EventTypes.SECURITY_ALERT,
                new SecurityAlert("bff-atm", severity, type, subject, detail, Instant.now())));
    }

    /** Abre su propia transacción (para llamadas desde filtros y handlers). */
    public void raise(String severity, String type, String subject, String detail) {
        tx.executeWithoutResult(s -> raiseInTx(severity, type, subject, detail));
    }
}

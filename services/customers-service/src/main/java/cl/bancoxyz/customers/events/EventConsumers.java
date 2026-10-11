package cl.bancoxyz.customers.events;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import cl.bancoxyz.customers.domain.CustomerProfile;
import cl.bancoxyz.customers.domain.CustomerProfileRepository;
import cl.bancoxyz.customers.domain.CustomerRepository;
import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.AccountLifecycle;
import cl.bancoxyz.platform.events.payload.SecurityAlert;
import cl.bancoxyz.platform.events.payload.TransactionCompleted;
import cl.bancoxyz.platform.idempotency.ProcessedEventStore;

/**
 * Consumidores de customers-service. Todos son idempotentes (processed_event por consumidor+eventId, en la misma
 * transacción que el efecto) y, si el mensaje es ilegible, fallan sin reintento y terminan en <topic>.DLT.
 */
@Component
public class EventConsumers {

    private static final Logger log = LoggerFactory.getLogger(EventConsumers.class);

    private final EventCodec codec;
    private final ProcessedEventStore processed;
    private final CustomerRepository customers;
    private final CustomerProfileRepository profiles;
    private final JdbcTemplate jdbc;

    public EventConsumers(EventCodec codec, ProcessedEventStore processed, CustomerRepository customers,
                          CustomerProfileRepository profiles, JdbcTemplate jdbc) {
        this.codec = codec;
        this.processed = processed;
        this.customers = customers;
        this.profiles = profiles;
        this.jdbc = jdbc;
    }

    /** accounts.lifecycle → actualiza el perfil (cuántas cuentas abiertas/cerradas tiene el cliente). */
    @KafkaListener(topics = Topics.ACCOUNTS_LIFECYCLE, groupId = "customers-service-profile")
    @Transactional
    public void onAccountLifecycle(String message) {
        EventEnvelope<JsonNode> event = codec.read(message);
        if (!processed.markIfFirst("profile", event.eventId())) {
            log.debug("Evento {} ya procesado, se ignora", event.eventId());
            return;
        }
        AccountLifecycle p = codec.payload(event, AccountLifecycle.class);
        if (!customers.existsById(p.customerId())) {
            log.warn("accounts.lifecycle de un cliente inexistente {}: se ignora", p.customerId());
            return;
        }
        CustomerProfile profile = profiles.findById(p.customerId()).orElseGet(() -> new CustomerProfile(p.customerId()));
        if (EventTypes.ACCOUNT_OPENED.equals(event.eventType())) {
            profile.accountOpened(p.occurredAt());
        } else if (EventTypes.ACCOUNT_CLOSED.equals(event.eventType())) {
            profile.accountClosed(p.occurredAt());
        } else {
            throw new IllegalArgumentException("Tipo de evento de cuenta desconocido: " + event.eventType());
        }
        profiles.save(profile);
        log.info("Perfil de {} actualizado por {}", p.customerId(), event.eventType());
    }

    /** transactions.completed → "notificación" al cliente (registro en notification_log). */
    @KafkaListener(topics = Topics.TRANSACTIONS_COMPLETED, groupId = "customers-service-notifications")
    @Transactional
    public void onTransactionCompleted(String message) {
        EventEnvelope<JsonNode> event = codec.read(message);
        if (!processed.markIfFirst("notifications", event.eventId())) {
            return;
        }
        TransactionCompleted t = codec.payload(event, TransactionCompleted.class);
        String text = "Transacción " + t.type() + " por " + t.amount().toPlainString() + " " + t.currency() + " completada";
        jdbc.update("INSERT INTO notification_log (event_id, payment_id, channel, message) VALUES (?, ?, 'LOG', ?)",
                event.eventId(), t.paymentId(), text);
        log.info("[NOTIFICACIÓN] {} (pago {})", text, t.paymentId());
    }

    /** security.alerts → tabla de alertas + log. */
    @KafkaListener(topics = Topics.SECURITY_ALERTS, groupId = "customers-service-alerts")
    @Transactional
    public void onSecurityAlert(String message) {
        EventEnvelope<JsonNode> event = codec.read(message);
        if (!processed.markIfFirst("alerts", event.eventId())) {
            return;
        }
        SecurityAlert a = codec.payload(event, SecurityAlert.class);
        jdbc.update("""
                INSERT INTO security_alert (event_id, source, severity, alert_type, subject, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), a.source(), a.severity(), a.alertType(), a.subject(), a.detail(),
                java.sql.Timestamp.from(a.occurredAt() == null ? Instant.now() : a.occurredAt()));
        log.warn("[ALERTA {}] {} {} sujeto={} {}", a.severity(), a.source(), a.alertType(), a.subject(), a.detail());
    }

    /** batch.job.events → monitoreo del batch. */
    @KafkaListener(topics = Topics.BATCH_JOB_EVENTS, groupId = "customers-service-batch-monitor")
    @Transactional
    public void onBatchJobEvent(String message) {
        EventEnvelope<JsonNode> event = codec.read(message);
        if (!processed.markIfFirst("batch-monitor", event.eventId())) {
            return;
        }
        JsonNode p = event.payload();
        jdbc.update("""
                INSERT INTO batch_job_event (event_id, event_type, job_name, status, attempt, will_retry, occurred_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.eventType(), p.path("jobName").asText("?"), p.path("status").asText(null),
                p.hasNonNull("attempt") ? p.get("attempt").asInt() : null,
                p.hasNonNull("willRetry") ? p.get("willRetry").asBoolean() : null,
                java.sql.Timestamp.from(event.occurredAt()), p.toString());
        log.info("[BATCH] {} {} estado={} intento={}", event.eventType(), p.path("jobName").asText(), p.path("status").asText(),
                p.path("attempt").asText());
    }
}

package cl.bancoxyz.payments.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.payments.clients.AccountLookup;
import cl.bancoxyz.payments.clients.AccountView;
import cl.bancoxyz.payments.clients.CustomerLookup;
import cl.bancoxyz.payments.clients.Gateways;
import cl.bancoxyz.payments.config.PaymentsProperties;
import cl.bancoxyz.payments.domain.Payment;
import cl.bancoxyz.payments.domain.PaymentRepository;
import cl.bancoxyz.payments.domain.PaymentStatus;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.TransactionRequested;
import cl.bancoxyz.platform.events.payload.TransactionType;
import cl.bancoxyz.platform.outbox.OutboxService;
import cl.bancoxyz.platform.web.ApiException;

/**
 * Alta de pagos. Primero se valida (consultas remotas con Resilience4j, FUERA de la transacción); después, en UNA
 * transacción local, se guarda el pago PENDING y su evento transaction.requested (Transactional Outbox). El resto de la
 * saga es asíncrona: si accounts-service está caído el pago queda PENDING y se procesa cuando vuelva, sin perderse.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Resultado de una solicitud; replayed=true si la Idempotency-Key ya existía. */
    public record Outcome(Payment payment, boolean replayed) {
    }

    private final PaymentRepository payments;
    private final Gateways gateways;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final PaymentsProperties props;

    public PaymentService(PaymentRepository payments, Gateways gateways, OutboxService outbox,
                          PlatformTransactionManager txManager, PaymentsProperties props) {
        this.payments = payments;
        this.gateways = gateways;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
    }

    public Outcome request(TransactionType type, PaymentRequest req, String idempotencyKey) {
        checkShape(type, req);
        String hash = fingerprint(type, req);

        if (idempotencyKey != null) {
            Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), hash);
            }
        }

        Validation v = validate(type, req);
        Payment payment = new Payment(UUID.randomUUID(), type, req.fromAccountId(), req.toAccountId(), req.amount(),
                props.currency(), PaymentStatus.PENDING, req.description(), idempotencyKey, hash, v.degraded(), v.reason(),
                null, null, 1, null, null, null);
        try {
            tx.executeWithoutResult(status -> {
                payments.insert(payment);
                payments.addHistory(payment.id(), PaymentStatus.PENDING,
                        v.degraded() ? "Solicitud aceptada en modo degradado: " + v.reason() : "Solicitud recibida y validada");
                outbox.publish(Topics.TRANSACTIONS_REQUESTED, payment.id().toString(), EventEnvelope.of(
                        EventTypes.TRANSACTION_REQUESTED, new TransactionRequested(payment.id(), type, req.fromAccountId(),
                                req.toAccountId(), req.amount(), props.currency())));
            });
        } catch (DuplicateKeyException race) {
            // Dos peticiones simultáneas con la misma Idempotency-Key: la segunda devuelve el pago de la primera
            return replay(payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race), hash);
        }
        return new Outcome(payments.findById(payment.id()).orElseThrow(), false);
    }

    private Outcome replay(Payment existing, String hash) {
        if (!hash.equals(existing.requestHash())) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "La Idempotency-Key ya se usó con una solicitud distinta");
        }
        return new Outcome(existing, true);
    }

    // ------------------------------------------------------------------ validación
    private record Validation(boolean degraded, String reason) {
    }

    private static void checkShape(TransactionType type, PaymentRequest r) {
        boolean ok = switch (type) {
            case TRANSFER -> r.fromAccountId() != null && r.toAccountId() != null && !r.fromAccountId().equals(r.toAccountId());
            case DEPOSIT -> r.toAccountId() != null && r.fromAccountId() == null;
            case WITHDRAWAL -> r.fromAccountId() != null && r.toAccountId() == null;
        };
        if (!ok) {
            throw ApiException.unprocessable("INVALID_REQUEST", switch (type) {
                case TRANSFER -> "Una transferencia requiere fromAccountId y toAccountId distintos";
                case DEPOSIT -> "Un depósito requiere solo toAccountId";
                case WITHDRAWAL -> "Un retiro requiere solo fromAccountId";
            });
        }
    }

    private Validation validate(TransactionType type, PaymentRequest req) {
        boolean degraded = false;
        StringBuilder reason = new StringBuilder();

        UUID primary = type == TransactionType.DEPOSIT ? req.toAccountId() : req.fromAccountId();
        AccountLookup src = gateways.account(primary);
        if (src.kind() == AccountLookup.Kind.NOT_FOUND) {
            throw ApiException.unprocessable("ACCOUNT_NOT_FOUND", "La cuenta " + primary + " no existe");
        }
        if (src.isDegraded()) {
            degraded = true;
            reason.append(src.reason());
        } else {
            AccountView a = src.account();
            if (!a.open()) {
                throw ApiException.unprocessable("ACCOUNT_CLOSED", "La cuenta " + primary + " está cerrada");
            }
            if (type != TransactionType.DEPOSIT && a.balance().compareTo(req.amount()) < 0) {
                // Pre-chequeo para responder rápido; el débito real lo valida accounts-service de forma atómica
                throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Saldo insuficiente en la cuenta " + primary);
            }
            CustomerLookup c = gateways.customer(a.customerId());
            if (c.customer() != null && !c.customer().active()) {
                throw ApiException.unprocessable("CUSTOMER_INACTIVE", "El cliente titular está inactivo");
            }
            if (c.degraded()) {
                degraded = true;
                reason.append("CUSTOMERS_UNAVAILABLE(").append(c.source()).append(")");
            }
        }

        if (type == TransactionType.TRANSFER) {
            AccountLookup dst = gateways.account(req.toAccountId());
            if (dst.kind() == AccountLookup.Kind.NOT_FOUND) {
                throw ApiException.unprocessable("DESTINATION_NOT_FOUND", "La cuenta destino " + req.toAccountId() + " no existe");
            }
            if (dst.kind() == AccountLookup.Kind.FOUND && !dst.account().open()) {
                throw ApiException.unprocessable("DESTINATION_CLOSED", "La cuenta destino está cerrada");
            }
            if (dst.isDegraded() && !degraded) {
                degraded = true;
                reason.append(dst.reason());
            }
        }
        if (degraded) {
            log.warn("Pago aceptado en modo degradado: {}", reason);
        }
        return new Validation(degraded, degraded ? reason.toString() : null);
    }

    private static String fingerprint(TransactionType type, PaymentRequest r) {
        try {
            String raw = type + "|" + r.fromAccountId() + "|" + r.toAccountId() + "|" + normalize(r.amount());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ consultas
    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> ApiException.notFound("Pago", id));
    }

    public List<java.util.Map<String, Object>> history(UUID id) {
        return payments.history(id);
    }

    public List<Payment> byAccount(UUID accountId, int limit) {
        return payments.findByAccount(accountId, limit);
    }
}

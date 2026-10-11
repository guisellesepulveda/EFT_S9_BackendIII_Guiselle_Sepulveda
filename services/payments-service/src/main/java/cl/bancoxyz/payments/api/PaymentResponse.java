package cl.bancoxyz.payments.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cl.bancoxyz.payments.domain.Payment;
import cl.bancoxyz.payments.domain.PaymentStatus;
import cl.bancoxyz.platform.events.payload.TransactionType;

public record PaymentResponse(UUID paymentId, TransactionType type, PaymentStatus status, UUID fromAccountId,
                              UUID toAccountId, BigDecimal amount, String currency, String description,
                              boolean degraded, String degradedReason, String failureCode, String failureReason,
                              Instant requestedAt, Instant updatedAt, Instant completedAt,
                              List<Map<String, Object>> history) {

    public static PaymentResponse of(Payment p, List<Map<String, Object>> history) {
        return new PaymentResponse(p.id(), p.type(), p.status(), p.fromAccountId(), p.toAccountId(), p.amount(),
                p.currency(), p.description(), p.degraded(), p.degradedReason(), p.failureCode(), p.failureReason(),
                p.requestedAt(), p.updatedAt(), p.completedAt(), history);
    }
}

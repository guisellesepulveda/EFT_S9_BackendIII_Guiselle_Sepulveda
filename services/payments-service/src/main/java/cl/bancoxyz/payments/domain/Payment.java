package cl.bancoxyz.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import cl.bancoxyz.platform.events.payload.TransactionType;

public record Payment(UUID id, TransactionType type, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                      String currency, PaymentStatus status, String description, String idempotencyKey,
                      String requestHash, boolean degraded, String degradedReason, String failureCode,
                      String failureReason, int requestAttempts, Instant requestedAt, Instant updatedAt,
                      Instant completedAt) {
}

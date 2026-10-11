package cl.bancoxyz.platform.events.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionCompleted(UUID paymentId, TransactionType type, UUID fromAccountId, UUID toAccountId,
                                   BigDecimal amount, String currency, Instant completedAt) {
}

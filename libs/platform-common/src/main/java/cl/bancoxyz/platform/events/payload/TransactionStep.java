package cl.bancoxyz.platform.events.payload;

import java.math.BigDecimal;
import java.util.UUID;

/** Resultado de un paso exitoso (transaction.debited / transaction.credited). */
public record TransactionStep(UUID paymentId, TransactionType type, UUID fromAccountId, UUID toAccountId,
                              UUID accountId, BigDecimal amount, String currency, BigDecimal balanceAfter) {
}

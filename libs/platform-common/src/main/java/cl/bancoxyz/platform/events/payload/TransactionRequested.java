package cl.bancoxyz.platform.events.payload;

import java.math.BigDecimal;
import java.util.UUID;

/** Inicio de la saga. fromAccountId es null en depósitos; toAccountId es null en retiros. */
public record TransactionRequested(UUID paymentId, TransactionType type, UUID fromAccountId, UUID toAccountId,
                                   BigDecimal amount, String currency) {
}

package cl.bancoxyz.platform.events.payload;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fallo de un paso de la saga. compensationRequired=true cuando ya se debitó el origen y hay que reembolsarlo
 * (lo ejecuta payments-service, que es el dueño de la saga).
 */
public record TransactionFailed(UUID paymentId, TransactionType type, String step, String reasonCode, String reason,
                                boolean compensationRequired, UUID fromAccountId, BigDecimal amount) {
}

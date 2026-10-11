package cl.bancoxyz.accounts.ledger;

import java.math.BigDecimal;

/** Resultado de una operación del libro mayor. replayed=true si la operación ya existía (idempotencia). */
public record OperationResult(String operationId, boolean applied, String reasonCode, BigDecimal balanceAfter,
                              boolean replayed) {

    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String ACCOUNT_CLOSED = "ACCOUNT_CLOSED";
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
}

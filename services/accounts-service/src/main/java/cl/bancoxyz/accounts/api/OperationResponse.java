package cl.bancoxyz.accounts.api;

import java.math.BigDecimal;

public record OperationResponse(String operationId, boolean applied, BigDecimal balanceAfter, boolean replayed) {
}

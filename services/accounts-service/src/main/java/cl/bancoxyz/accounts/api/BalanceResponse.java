package cl.bancoxyz.accounts.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(UUID accountId, BigDecimal balance, String currency, Instant asOf) {
}

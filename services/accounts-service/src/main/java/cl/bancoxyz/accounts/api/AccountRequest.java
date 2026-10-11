package cl.bancoxyz.accounts.api;

import java.util.UUID;

import cl.bancoxyz.accounts.domain.AccountType;
import jakarta.validation.constraints.NotNull;

public record AccountRequest(@NotNull UUID customerId, @NotNull AccountType type) {
}

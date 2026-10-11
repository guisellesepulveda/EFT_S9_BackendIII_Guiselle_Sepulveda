package cl.bancoxyz.accounts.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import cl.bancoxyz.accounts.domain.Account;
import cl.bancoxyz.accounts.domain.AccountStatus;
import cl.bancoxyz.accounts.domain.AccountType;

public record AccountResponse(UUID id, UUID customerId, String number, AccountType type, String currency,
                              BigDecimal balance, AccountStatus status, Instant openedAt, Instant closedAt) {

    public static AccountResponse of(Account a) {
        // Una cuenta recién creada aún no tiene el saldo cargado desde la base (la columna es de solo lectura): es 0
        return new AccountResponse(a.getId(), a.getCustomerId(), a.getNumber(), a.getType(), a.getCurrency(),
                a.getBalance() == null ? BigDecimal.ZERO.setScale(2) : a.getBalance(), a.getStatus(), a.getOpenedAt(),
                a.getClosedAt());
    }
}

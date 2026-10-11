package cl.bancoxyz.bff.common.clients;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import cl.bancoxyz.bff.common.clients.Dtos.AccountDto;
import cl.bancoxyz.bff.common.clients.Dtos.BalanceDto;
import cl.bancoxyz.bff.common.clients.Dtos.MovementDto;
import cl.bancoxyz.bff.common.resilience.FeignErrors;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

/**
 * Llamadas a accounts-service con Resilience4j (Retry → CircuitBreaker → TimeLimiter → Bulkhead, instancia "accounts").
 * Devuelven CompletableFuture: el BFF web las lanza en paralelo y degrada con elegancia si alguna falla.
 */
@Retry(name = "accounts")
@CircuitBreaker(name = "accounts")
@TimeLimiter(name = "accounts")
@Bulkhead(name = "accounts", type = Bulkhead.Type.SEMAPHORE)
public class AccountsBackend {

    private final AccountsApi api;
    private final Executor executor;

    public AccountsBackend(AccountsApi api, Executor executor) {
        this.api = api;
        this.executor = executor;
    }

    public CompletableFuture<List<AccountDto>> byCustomer(String bearer, UUID customerId) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.byCustomer(bearer, customerId)), executor);
    }

    public CompletableFuture<AccountDto> get(String bearer, UUID id) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.get(bearer, id)), executor);
    }

    public CompletableFuture<BalanceDto> balance(String bearer, UUID id) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.balance(bearer, id)), executor);
    }

    public CompletableFuture<List<MovementDto>> movements(String bearer, UUID id, int limit) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.movements(bearer, id, limit)), executor);
    }
}

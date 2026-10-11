package cl.bancoxyz.bff.common.clients;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import cl.bancoxyz.bff.common.clients.Dtos.CustomerDto;
import cl.bancoxyz.bff.common.clients.Dtos.ProfileDto;
import cl.bancoxyz.bff.common.resilience.FeignErrors;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

@Retry(name = "customers")
@CircuitBreaker(name = "customers")
@TimeLimiter(name = "customers")
@Bulkhead(name = "customers", type = Bulkhead.Type.SEMAPHORE)
public class CustomersBackend {

    private final CustomersApi api;
    private final Executor executor;

    public CustomersBackend(CustomersApi api, Executor executor) {
        this.api = api;
        this.executor = executor;
    }

    public CompletableFuture<CustomerDto> byUsername(String bearer, String username) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.byUsername(bearer, username)), executor);
    }

    public CompletableFuture<CustomerDto> get(String bearer, UUID id) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.get(bearer, id)), executor);
    }

    public CompletableFuture<ProfileDto> profile(String bearer, UUID id) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.profile(bearer, id)), executor);
    }
}

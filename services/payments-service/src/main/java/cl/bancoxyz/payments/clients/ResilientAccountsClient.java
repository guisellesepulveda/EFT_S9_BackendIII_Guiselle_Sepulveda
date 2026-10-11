package cl.bancoxyz.payments.clients;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import cl.bancoxyz.platform.resilience.Futures;
import cl.bancoxyz.platform.web.ApiException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

/**
 * Llamadas a accounts-service con Resilience4j (Retry → CircuitBreaker → TimeLimiter → Bulkhead).
 * <ul>
 *   <li><b>lookup</b>: si accounts-service no responde, el fallback devuelve {@code DEGRADED}: el pago NO se pierde,
 *       queda PENDING con degraded=true y la validación definitiva la hace accounts-service al procesar el evento.</li>
 *   <li><b>credit</b> (compensación): no tiene valor alternativo; si falla, la excepción sube para que Kafka reintente
 *       y, agotados los reintentos, el mensaje termine en la DLT (y el reintentador lo retome).</li>
 * </ul>
 */
@Component
public class ResilientAccountsClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientAccountsClient.class);

    private final AccountsClient client;
    private final Executor executor;

    public ResilientAccountsClient(AccountsClient client, @Qualifier("resilienceExecutor") Executor executor) {
        this.client = client;
        this.executor = executor;
    }

    @Retry(name = "accounts", fallbackMethod = "lookupFallback")
    @CircuitBreaker(name = "accounts")
    @TimeLimiter(name = "accounts")
    @Bulkhead(name = "accounts", type = Bulkhead.Type.SEMAPHORE)
    public CompletableFuture<AccountLookup> lookupAsync(UUID accountId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return AccountLookup.found(client.get(accountId));
            } catch (FeignException.NotFound e) {
                return AccountLookup.notFound();
            }
        }, executor);
    }

    @SuppressWarnings("unused") // invocado por Resilience4j
    CompletableFuture<AccountLookup> lookupFallback(UUID accountId, Throwable t) {
        var business = Futures.businessError(t);
        if (business.isPresent()) {
            return CompletableFuture.failedFuture(business.get()); // la dependencia rechazó la petición: no es una caída
        }
        RuntimeException cause = Futures.unwrap(t);
        log.warn("accounts-service no disponible al consultar {} ({}): el pago se acepta como PENDING degradado",
                accountId, cause.getClass().getSimpleName());
        return CompletableFuture.completedFuture(AccountLookup.degraded("ACCOUNTS_UNAVAILABLE"));
    }

    @Retry(name = "accounts-compensation", fallbackMethod = "creditFallback")
    @CircuitBreaker(name = "accounts-compensation")
    @TimeLimiter(name = "accounts-compensation")
    @Bulkhead(name = "accounts-compensation", type = Bulkhead.Type.SEMAPHORE)
    public CompletableFuture<Void> creditAsync(UUID accountId, CreditRequest request) {
        return CompletableFuture.runAsync(() -> {
            try {
                client.credit(accountId, request);
            } catch (FeignException.UnprocessableEntity | FeignException.NotFound e) {
                // La cuenta rechazó el reembolso (cerrada/inexistente): es un resultado de negocio, no una caída
                throw ApiException.unprocessable("REFUND_REJECTED", "La cuenta " + accountId + " rechazó el reembolso");
            }
        }, executor);
    }

    @SuppressWarnings("unused") // invocado por Resilience4j
    CompletableFuture<Void> creditFallback(UUID accountId, CreditRequest request, Throwable t) {
        var business = Futures.businessError(t);
        if (business.isPresent()) {
            return CompletableFuture.failedFuture(business.get()); // la dependencia rechazó la petición: no es una caída
        }
        RuntimeException cause = Futures.unwrap(t);
        return CompletableFuture.failedFuture(ApiException.unavailable("ACCOUNTS_UNAVAILABLE",
                "No se pudo reembolsar ahora: accounts-service no disponible"));
    }
}

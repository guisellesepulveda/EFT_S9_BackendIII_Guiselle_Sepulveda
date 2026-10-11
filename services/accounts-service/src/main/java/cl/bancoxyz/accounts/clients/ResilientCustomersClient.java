package cl.bancoxyz.accounts.clients;

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
 * Llamada a customers-service protegida con Resilience4j. Orden de decoradores (de afuera hacia adentro):
 * Retry → CircuitBreaker → TimeLimiter → Bulkhead. El fallback vive en el Retry (el más externo) para que primero se
 * agoten los reintentos (o el circuito rechace la llamada) y solo entonces se aplique el comportamiento alternativo.
 * Es un bean aparte de CustomerGateway: los decoradores funcionan por proxy y no se aplican a llamadas internas
 * de la misma clase.
 */
@Component
public class ResilientCustomersClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientCustomersClient.class);

    private final CustomersClient client;
    private final Executor executor;

    public ResilientCustomersClient(CustomersClient client, @Qualifier("resilienceExecutor") Executor executor) {
        this.client = client;
        this.executor = executor;
    }

    @Retry(name = "customers", fallbackMethod = "findFallback")
    @CircuitBreaker(name = "customers")
    @TimeLimiter(name = "customers")
    @Bulkhead(name = "customers", type = Bulkhead.Type.SEMAPHORE)
    public CompletableFuture<CustomerView> findAsync(UUID id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.get(id);
            } catch (FeignException.NotFound e) {
                throw ApiException.unprocessable("CUSTOMER_NOT_FOUND", "El cliente " + id + " no existe");
            }
        }, executor);
    }

    /** Aquí no hay valor alternativo válido (abrir una cuenta exige verificar al cliente): se falla rápido con 503. */
    @SuppressWarnings("unused") // invocado por Resilience4j
    CompletableFuture<CustomerView> findFallback(UUID id, Throwable t) {
        var business = Futures.businessError(t);
        if (business.isPresent()) {
            return CompletableFuture.failedFuture(business.get()); // la dependencia rechazó la petición: no es una caída
        }
        RuntimeException cause = Futures.unwrap(t);
        log.warn("customers-service no disponible al consultar {}: {}", id, cause.toString());
        return CompletableFuture.failedFuture(ApiException.unavailable("CUSTOMERS_UNAVAILABLE",
                "No se pudo verificar al cliente: servicio de clientes temporalmente no disponible"));
    }
}

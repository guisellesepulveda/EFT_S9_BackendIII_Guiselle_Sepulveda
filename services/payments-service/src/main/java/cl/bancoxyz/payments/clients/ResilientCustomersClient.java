package cl.bancoxyz.payments.clients;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import cl.bancoxyz.payments.config.PaymentsProperties;
import cl.bancoxyz.platform.resilience.Futures;
import cl.bancoxyz.platform.web.ApiException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

/**
 * Validación de clientes con caché local como fallback: si customers-service está caído, se responde con el último dato
 * conocido y degraded=true (o "desconocido" si nunca se consultó), en lugar de bloquear los pagos.
 */
@Component
public class ResilientCustomersClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientCustomersClient.class);

    private final CustomersClient client;
    private final Executor executor;
    private final Cache<UUID, CustomerView> cache;

    public ResilientCustomersClient(CustomersClient client, @Qualifier("resilienceExecutor") Executor executor,
                                    PaymentsProperties props) {
        this.client = client;
        this.executor = executor;
        this.cache = Caffeine.newBuilder().expireAfterWrite(props.customerCacheTtl()).maximumSize(10_000).build();
    }

    @Retry(name = "customers", fallbackMethod = "lookupFallback")
    @CircuitBreaker(name = "customers")
    @TimeLimiter(name = "customers")
    @Bulkhead(name = "customers", type = Bulkhead.Type.SEMAPHORE)
    public CompletableFuture<CustomerLookup> lookupAsync(UUID customerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CustomerView c = client.get(customerId);
                cache.put(customerId, c);
                return CustomerLookup.live(c);
            } catch (FeignException.NotFound e) {
                throw ApiException.unprocessable("CUSTOMER_NOT_FOUND", "El cliente " + customerId + " no existe");
            }
        }, executor);
    }

    @SuppressWarnings("unused") // invocado por Resilience4j
    CompletableFuture<CustomerLookup> lookupFallback(UUID customerId, Throwable t) {
        var business = Futures.businessError(t);
        if (business.isPresent()) {
            return CompletableFuture.failedFuture(business.get()); // la dependencia rechazó la petición: no es una caída
        }
        RuntimeException cause = Futures.unwrap(t);
        CustomerView cached = cache.getIfPresent(customerId);
        log.warn("customers-service no disponible ({}): respuesta {} para el cliente {}", cause.getClass().getSimpleName(),
                cached != null ? "desde caché" : "sin datos", customerId);
        return CompletableFuture.completedFuture(cached != null ? CustomerLookup.cached(cached) : CustomerLookup.unknown());
    }
}

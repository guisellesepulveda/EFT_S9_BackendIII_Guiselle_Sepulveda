package cl.bancoxyz.bff.common.clients;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import cl.bancoxyz.bff.common.clients.Dtos.PaymentCommand;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentDto;
import cl.bancoxyz.bff.common.resilience.FeignErrors;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

/**
 * payments-service. Las escrituras SIEMPRE llevan Idempotency-Key, por lo que un reintento (o una respuesta perdida)
 * nunca duplica un pago: payments devuelve el mismo pago para la misma clave.
 */
@Retry(name = "payments")
@CircuitBreaker(name = "payments")
@TimeLimiter(name = "payments")
@Bulkhead(name = "payments", type = Bulkhead.Type.SEMAPHORE)
public class PaymentsBackend {

    private final PaymentsApi api;
    private final Executor executor;

    public PaymentsBackend(PaymentsApi api, Executor executor) {
        this.api = api;
        this.executor = executor;
    }

    public CompletableFuture<PaymentDto> transfer(String bearer, String idempotencyKey, PaymentCommand command) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.transfer(bearer, idempotencyKey, command)), executor);
    }

    public CompletableFuture<PaymentDto> withdraw(String bearer, String idempotencyKey, PaymentCommand command) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.withdraw(bearer, idempotencyKey, command)), executor);
    }

    public CompletableFuture<PaymentDto> get(String bearer, UUID id) {
        return CompletableFuture.supplyAsync(() -> FeignErrors.translate4xx(() -> api.get(bearer, id)), executor);
    }
}

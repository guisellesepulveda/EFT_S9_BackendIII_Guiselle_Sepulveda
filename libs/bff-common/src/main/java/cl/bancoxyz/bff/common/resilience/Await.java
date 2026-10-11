package cl.bancoxyz.bff.common.resilience;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import cl.bancoxyz.platform.resilience.Futures;
import cl.bancoxyz.platform.web.ApiException;

/**
 * Espera una llamada protegida y traduce su fallo: error de negocio (4xx) → se propaga tal cual;
 * cualquier otra cosa (timeout, circuito abierto, bulkhead lleno, 5xx, red) → 503 con un mensaje SEGURO y neutro
 * (sin nombres de host ni trazas), código {SERVICIO}_UNAVAILABLE.
 */
public final class Await {

    public static final String SAFE_MESSAGE = "Servicio temporalmente no disponible";

    private Await() {
    }

    public static <T> T of(CompletableFuture<T> future, String service) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(service);
        } catch (TimeoutException e) {
            throw unavailable(service);
        } catch (ExecutionException e) {
            throw translate(e.getCause(), service);
        }
    }

    public static RuntimeException translate(Throwable t, String service) {
        return Futures.businessError(t).<RuntimeException>map(e -> e).orElseGet(() -> unavailable(service));
    }

    public static ApiException unavailable(String service) {
        return ApiException.unavailable(service.toUpperCase() + "_UNAVAILABLE", SAFE_MESSAGE);
    }
}

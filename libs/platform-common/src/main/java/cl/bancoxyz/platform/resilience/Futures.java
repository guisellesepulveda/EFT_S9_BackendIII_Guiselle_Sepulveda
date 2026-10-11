package cl.bancoxyz.platform.resilience;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import cl.bancoxyz.platform.web.ApiException;

/** Espera el resultado de una llamada protegida con Resilience4j (CompletableFuture) y desenvuelve la causa real. */
public final class Futures {

    private static final Duration SAFETY_TIMEOUT = Duration.ofSeconds(30);

    private Futures() {
    }

    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(SAFETY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("INTERRUPTED", "Operación interrumpida");
        } catch (TimeoutException e) {
            throw ApiException.unavailable("DEPENDENCY_TIMEOUT", "La dependencia no respondió a tiempo");
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        }
    }

    /**
     * Error de NEGOCIO (4xx) dentro de la cadena de causas, si lo hay. Los fallbacks lo usan para distinguir "la
     * dependencia rechazó la petición" (se propaga) de "la dependencia está caída" (se aplica el comportamiento alternativo).
     */
    public static java.util.Optional<ApiException> businessError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ApiException api && api.status().is4xxClientError()) {
                return java.util.Optional.of(api);
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return java.util.Optional.empty();
    }

    public static RuntimeException unwrap(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return ApiException.unavailable("DEPENDENCY_FAILURE", "Falla en una dependencia: " + cause.getClass().getSimpleName());
    }
}

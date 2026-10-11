package cl.bancoxyz.payments.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/** Saturación (Bulkhead lleno) o circuito abierto en una llamada no protegida por fallback → 503 con Retry-After. */
@RestControllerAdvice
class ResilienceAdvice {

    @ExceptionHandler({BulkheadFullException.class, CallNotPermittedException.class})
    ResponseEntity<ProblemDetail> overloaded(RuntimeException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "El servicio está saturado o una dependencia no está disponible; reintente en unos segundos");
        pd.setTitle("Servicio temporalmente no disponible");
        pd.setProperty("code", e instanceof BulkheadFullException ? "BULKHEAD_FULL" : "CIRCUIT_OPEN");
        if (MDC.get("traceId") != null) {
            pd.setProperty("traceId", MDC.get("traceId"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "2").body(pd);
    }
}

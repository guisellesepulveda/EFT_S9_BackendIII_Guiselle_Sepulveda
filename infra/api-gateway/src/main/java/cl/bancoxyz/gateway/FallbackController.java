package cl.bancoxyz.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Respuesta alternativa del circuit breaker del gateway cuando un servicio no responde o tiene el circuito abierto. */
@RestController
class FallbackController {

    @RequestMapping("/fallback/{service}")
    ResponseEntity<ProblemDetail> fallback(@PathVariable String service) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "El servicio '" + service + "' no está disponible en este momento; reintente en unos segundos");
        pd.setTitle("Servicio temporalmente no disponible");
        pd.setProperty("code", "UPSTREAM_UNAVAILABLE");
        pd.setProperty("service", service);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(pd);
    }
}

package cl.bancoxyz.bff.common.resilience;

import java.util.function.Supplier;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.platform.web.ApiException;
import feign.FeignException;

/**
 * Traduce las respuestas 4xx de un servicio a un ApiException equivalente (mismo código HTTP y código de negocio).
 * Un 4xx es una respuesta de negocio, NO una caída: Resilience4j lo ignora (no reintenta ni abre el circuito).
 * Los 5xx, timeouts y errores de red se dejan pasar como FeignException/IOException para que los cuente el circuito.
 */
public final class FeignErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeignErrors() {
    }

    public static <T> T translate4xx(Supplier<T> call) {
        try {
            return call.get();
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                throw business(e);
            }
            throw e;
        }
    }

    private static ApiException business(FeignException e) {
        HttpStatus status = HttpStatus.resolve(e.status());
        String code = "DOWNSTREAM_" + e.status();
        String detail = "La operación fue rechazada";
        try {
            JsonNode body = MAPPER.readTree(e.contentUTF8());
            if (body.hasNonNull("code")) {
                code = body.get("code").asText();
            }
            if (body.hasNonNull("detail")) {
                detail = body.get("detail").asText();
            }
        } catch (Exception ignored) {
            // cuerpo no JSON: se usan los valores por defecto
        }
        // 401/403 de un servicio significa un token insuficiente para ese recurso: se informa como 403 sin detalles internos
        if (e.status() == 401 || e.status() == 403) {
            return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "No tiene permisos para esta operación");
        }
        return new ApiException(status == null ? HttpStatus.UNPROCESSABLE_ENTITY : status, code, detail);
    }
}

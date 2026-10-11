package cl.bancoxyz.bffmobile.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.platform.web.ApiException;

/**
 * Respuestas parciales (?fields=id,balance): la app pide solo los campos que usa y se ahorra bytes. Los nombres válidos son
 * una lista cerrada por recurso; un campo desconocido es un error 400 (no se ignora en silencio).
 */
final class PartialFields {

    private PartialFields() {
    }

    static List<Map<String, Object>> filter(ObjectMapper mapper, List<?> items, String fields, Set<String> allowed) {
        Set<String> wanted = parse(fields, allowed);
        return items.stream().map(i -> {
            Map<String, Object> full = mapper.convertValue(i, new TypeReference<LinkedHashMap<String, Object>>() { });
            if (wanted == null) {
                return full;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            full.forEach((k, v) -> {
                if (wanted.contains(k)) {
                    out.put(k, v);
                }
            });
            return out;
        }).toList();
    }

    static Set<String> parse(String fields, Set<String> allowed) {
        if (fields == null || fields.isBlank()) {
            return null;
        }
        Set<String> wanted = Arrays.stream(fields.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> unknown = wanted.stream().filter(f -> !allowed.contains(f)).collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "UNKNOWN_FIELDS",
                    "Campos no válidos: " + unknown + ". Permitidos: " + allowed);
        }
        return wanted;
    }
}

package cl.bancoxyz.batch.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;

/**
 * Normaliza fechas del legacy a {@link LocalDate}. Los formatos aceptados son configurables
 * (por defecto yyyy-MM-dd y yyyy/MM/dd); cualquier otro valor, o una fecha inexistente
 * (p. ej. 2024-13-01 o 2024-02-30), se rechaza.
 */
public class DateNormalizer {

    private final List<DateTimeFormatter> formatters;

    public DateNormalizer(List<String> patterns) {
        this.formatters = patterns.stream()
                // En modo STRICT "yyyy" (año-de-era) exige era; "uuuu" es el año proléptico.
                .map(p -> DateTimeFormatter.ofPattern(p.replace("yyyy", "uuuu")).withResolverStyle(ResolverStyle.STRICT))
                .toList();
    }

    public Optional<LocalDate> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.trim();
        for (DateTimeFormatter f : formatters) {
            try {
                return Optional.of(LocalDate.parse(v, f));
            } catch (DateTimeParseException ignored) {
                // se prueba el siguiente formato
            }
        }
        return Optional.empty();
    }
}

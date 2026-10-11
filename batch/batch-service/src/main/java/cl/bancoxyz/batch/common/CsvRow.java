package cl.bancoxyz.batch.common;

/** Fila CSV sin interpretar: toda la validación de negocio ocurre en el processor. */
public record CsvRow(long lineNumber, String rawLine, String[] values) implements SourceLine {

    /** Valor de la columna sin espacios sobrantes; nunca null. */
    public String get(int index) {
        String v = index < values.length ? values[index] : null;
        return v == null ? "" : v.trim();
    }
}

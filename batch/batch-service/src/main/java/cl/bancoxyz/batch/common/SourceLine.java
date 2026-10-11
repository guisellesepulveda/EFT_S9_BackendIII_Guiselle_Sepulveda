package cl.bancoxyz.batch.common;

/** Registro que conserva su origen (línea y contenido crudo) para poder auditar rechazos. */
public interface SourceLine {

    long lineNumber();

    String rawLine();
}

package cl.bancoxyz.batch.job.annual;

import java.math.BigDecimal;
import java.time.LocalDate;

import cl.bancoxyz.batch.common.SourceLine;

public record AnnualTransaction(long lineNumber, String rawLine, long cuentaId, LocalDate date, String tipo,
                                BigDecimal monto, String descripcion, String dedupKey) implements SourceLine {
}

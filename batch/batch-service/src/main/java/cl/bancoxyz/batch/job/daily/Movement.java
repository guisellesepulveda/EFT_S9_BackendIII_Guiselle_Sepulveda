package cl.bancoxyz.batch.job.daily;

import java.math.BigDecimal;
import java.time.LocalDate;

import cl.bancoxyz.batch.common.SourceLine;

public record Movement(long lineNumber, String rawLine, long id, LocalDate date, String tipo, BigDecimal monto,
                       boolean outlier) implements SourceLine {
}

package cl.bancoxyz.batch.job.interest;

import java.math.BigDecimal;

import cl.bancoxyz.batch.common.SourceLine;

public record InterestCalculation(long lineNumber, String rawLine, long cuentaId, String nombre, String tipo,
                                  Periodo periodo, BigDecimal saldoAnterior, BigDecimal tasaAnual,
                                  BigDecimal interes, BigDecimal saldoNuevo, String dedupKey) implements SourceLine {
}

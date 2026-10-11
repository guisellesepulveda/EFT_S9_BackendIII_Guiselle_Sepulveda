package cl.bancoxyz.batch.job.daily;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Una fila del resumen diario: cantidad y montos por (fecha, tipo). */
public record SummaryRow(LocalDate date, String tipo, long count, BigDecimal total, BigDecimal max) {
}

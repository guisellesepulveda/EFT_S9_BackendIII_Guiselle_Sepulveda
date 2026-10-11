package cl.bancoxyz.batch.job.annual;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Consolidado + detalle de movimientos de una cuenta, listo para exportar a archivo de auditoría. */
public record AccountStatement(long cuentaId, int year, BigDecimal totalDeposits, BigDecimal totalWithdrawals,
                               BigDecimal totalPurchases, BigDecimal finalBalance, long txCount,
                               List<Line> transactions) {

    public record Line(LocalDate date, String tipo, BigDecimal monto, String descripcion) {
    }
}

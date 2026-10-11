package cl.bancoxyz.batch.job.interest;

import java.math.BigDecimal;

public record BalanceUpdate(long cuentaId, String nombre, String tipo, BigDecimal saldo, String periodo) {
}

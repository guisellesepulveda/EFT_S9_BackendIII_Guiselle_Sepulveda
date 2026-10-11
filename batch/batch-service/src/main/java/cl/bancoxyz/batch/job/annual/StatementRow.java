package cl.bancoxyz.batch.job.annual;

import java.math.BigDecimal;

/** Consolidado anual por cuenta. Saldo final = depósitos − retiros − compras (saldo inicial 0). */
public record StatementRow(long cuentaId, int year, BigDecimal deposits, BigDecimal withdrawals,
                           BigDecimal purchases, long txCount) {

    public BigDecimal finalBalance() {
        return deposits.subtract(withdrawals).subtract(purchases);
    }
}

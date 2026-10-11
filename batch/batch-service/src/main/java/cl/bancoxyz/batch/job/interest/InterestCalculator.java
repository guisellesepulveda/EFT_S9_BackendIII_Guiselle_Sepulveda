package cl.bancoxyz.batch.job.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** interés = saldo × tasaAnual / divisor(periodo), redondeado a 2 decimales HALF_UP. */
public final class InterestCalculator {

    private InterestCalculator() {
    }

    public static BigDecimal interest(BigDecimal saldo, BigDecimal annualRate, Periodo periodo) {
        return saldo.multiply(annualRate).divide(BigDecimal.valueOf(periodo.divisor()), 2, RoundingMode.HALF_UP);
    }
}

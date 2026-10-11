package cl.bancoxyz.batch.job.interest;

/** Periodicidad del cálculo de intereses (ADR-002): interés = saldo × tasaAnual / divisor. */
public enum Periodo {
    MENSUAL(12),
    TRIMESTRAL(4);

    private final int divisor;

    Periodo(int divisor) {
        this.divisor = divisor;
    }

    public int divisor() {
        return divisor;
    }

    public static Periodo parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("periodo debe ser MENSUAL o TRIMESTRAL: " + value);
        }
    }
}

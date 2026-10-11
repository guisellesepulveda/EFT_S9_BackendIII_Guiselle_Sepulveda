package cl.bancoxyz.payments.domain;

import java.util.Set;

/**
 * PENDING → (DEBITED) → COMPLETED
 *        ↘ FAILED
 * DEBITED/PENDING → COMPENSATING → COMPENSATED   (el crédito destino falló y se reembolsa el origen)
 */
public enum PaymentStatus {
    PENDING,
    DEBITED,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED;

    public static final Set<PaymentStatus> IN_FLIGHT = Set.of(PENDING, DEBITED);

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == COMPENSATED;
    }
}

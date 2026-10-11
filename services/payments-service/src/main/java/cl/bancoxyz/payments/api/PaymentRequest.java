package cl.bancoxyz.payments.api;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Transferencia: from+to · Depósito: solo to · Retiro: solo from. */
public record PaymentRequest(
        UUID fromAccountId,
        UUID toAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @Size(max = 200) String description) {
}

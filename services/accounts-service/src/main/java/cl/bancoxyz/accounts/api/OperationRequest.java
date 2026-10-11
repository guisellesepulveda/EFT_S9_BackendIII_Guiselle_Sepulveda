package cl.bancoxyz.accounts.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Operación interna sobre una cuenta; operationId es la clave de idempotencia. */
public record OperationRequest(
        @NotBlank @Size(max = 100) String operationId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @Size(max = 64) String reference,
        @Size(max = 200) String description) {
}

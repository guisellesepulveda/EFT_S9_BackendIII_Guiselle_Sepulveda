package cl.bancoxyz.bffatm.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Contrato del BFF ATM: SOLO operaciones críticas y respuestas mínimas. El PIN viaja en el cuerpo y nunca se registra. */
public final class AtmDtos {

    private static final String CARD = "^[0-9]{8,32}$";
    private static final String PIN = "^[0-9]{4}$";

    private AtmDtos() {
    }

    public record CardPin(@NotNull @Pattern(regexp = CARD) @Schema(example = "4000123412340001") String cardId,
                          @NotNull @Pattern(regexp = PIN) @Schema(example = "1234") String pin) {
        @Override
        public String toString() {
            return "CardPin[cardId=****, pin=***]";
        }
    }

    public record BalanceView(@Schema(example = "****0001") String account, @Schema(example = "7499.50") BigDecimal balance,
                              @Schema(example = "CLP") String currency, Instant asOf) {
    }

    public record MovementItem(Instant at, @Schema(example = "DEBIT") String type, @Schema(example = "2500.50") BigDecimal amount) {
    }

    public record WithdrawalRequest(@NotNull @Pattern(regexp = CARD) @Schema(example = "4000123412340001") String cardId,
                                    @NotNull @Pattern(regexp = PIN) @Schema(example = "1234") String pin,
                                    @NotNull @DecimalMin("1000") @Digits(integer = 10, fraction = 0) @Schema(example = "20000") BigDecimal amount) {
        @Override
        public String toString() {
            return "WithdrawalRequest[cardId=****, pin=***, amount=" + amount + "]";
        }
    }

    /**
     * Decisión para el cajero. REGLA DE ORO: dispense=true SOLO cuando el pago está COMPLETED (débito confirmado).
     * En cualquier otro estado (PENDING, DECLINED, UNAVAILABLE) dispense=false.
     */
    public record WithdrawalResult(@Schema(example = "APPROVED", description = "APPROVED | PENDING | DECLINED | UNAVAILABLE") String status,
                                   boolean dispense, BigDecimal amount, @Schema(description = "id del pago, para consultar el estado") UUID reference,
                                   String message) {
    }

    public record CardRequest(@NotNull @Pattern(regexp = CARD) String cardId, @NotNull UUID accountId,
                              @NotNull @Pattern(regexp = PIN) String pin) {
        @Override
        public String toString() {
            return "CardRequest[cardId=****, pin=***]";
        }
    }
}

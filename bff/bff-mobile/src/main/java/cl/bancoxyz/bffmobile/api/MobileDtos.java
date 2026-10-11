package cl.bancoxyz.bffmobile.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contrato del BFF móvil: DTOs REDUCIDOS (solo lo que pinta la pantalla), pensados para redes lentas y poca batería. */
public final class MobileDtos {

    private MobileDtos() {
    }

    public record Account(UUID id, @Schema(example = "CL1000000001") String number, @Schema(example = "CHECKING") String type,
                          @Schema(example = "7499.50") BigDecimal balance) {
    }

    public record Movement(@Schema(example = "2026-10-10T12:00:00Z") Instant at, @Schema(example = "CREDIT") String type,
                           @Schema(example = "10000.00") BigDecimal amount, @Schema(example = "CONFIRMED") String status) {
    }

    public record Home(@Schema(example = "9999.50") BigDecimal totalBalance, java.util.List<Account> accounts) {
    }

    public record TransferRequest(
            @NotNull UUID from,
            @NotNull UUID to,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) @Schema(example = "2500.50") BigDecimal amount,
            @Size(max = 100) String note) {
    }

    public record Transfer(UUID id, @Schema(example = "PENDING") String status) {
    }

    public record TokenRequest(@NotBlank String code, @NotBlank @Size(min = 43, max = 128) String codeVerifier,
                               @NotBlank String redirectUri) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** El BFF nunca devuelve el id_token ni datos innecesarios; la app guarda el refresh token en el almacén seguro del SO. */
    public record Tokens(String accessToken, String refreshToken, long expiresIn, String scope) {
    }
}

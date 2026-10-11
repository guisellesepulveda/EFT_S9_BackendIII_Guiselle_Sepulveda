package cl.bancoxyz.bffweb.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contrato del BFF web: datos COMPLETOS y agregados para una pantalla de escritorio. */
public final class WebDtos {

    private WebDtos() {
    }

    public record Me(@Schema(example = "customer1") String username, List<String> roles, List<String> scopes,
                     @Schema(example = "web") String channel) {
    }

    public record CustomerCard(UUID id, @Schema(example = "Camila Rojas") String fullName,
                               @Schema(example = "camila@example.com") String email, @Schema(example = "+56912345678") String phone) {
    }

    public record ProfileCard(int accountsOpen, int accountsClosed, Instant lastAccountEventAt) {
    }

    public record MovementView(@Schema(example = "CREDIT") String type, @Schema(example = "10000.00") BigDecimal amount,
                               BigDecimal balanceAfter, @Schema(example = "CONFIRMED") String status, String description,
                               Instant at, UUID paymentId) {
    }

    public record AccountView(UUID id, @Schema(example = "CL1000000001") String number, @Schema(example = "CHECKING") String type,
                              @Schema(example = "CLP") String currency, @Schema(example = "7499.50") BigDecimal balance,
                              @Schema(example = "OPEN") String status, Instant openedAt,
                              @Schema(description = "null si el servicio de movimientos no respondió") List<MovementView> recentMovements,
                              boolean movementsAvailable) {
    }

    public record Dashboard(CustomerCard customer, @Schema(description = "null si customers-service no respondió") ProfileCard profile,
                            List<AccountView> accounts, @Schema(example = "9999.50") BigDecimal totalBalance,
                            @Schema(description = "true si alguna parte del tablero se sirvió de forma parcial") boolean degraded,
                            @Schema(example = "[\"profile\"]") List<String> unavailable, Instant generatedAt) {
    }

    public record AccountsPage(List<AccountView> items, int page, int size, long totalItems, int totalPages) {
    }

    public record MovementsPage(List<MovementView> items, int page, int size, long totalItems, int totalPages) {
    }

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) @Schema(example = "2500.50") BigDecimal amount,
            @Size(max = 200) @Schema(example = "Arriendo octubre") String description) {
    }

    public record TransferView(UUID paymentId, @Schema(example = "PENDING") String status, UUID fromAccountId, UUID toAccountId,
                               BigDecimal amount, String currency, boolean degraded, String failureCode, Instant requestedAt,
                               Instant completedAt) {
    }
}

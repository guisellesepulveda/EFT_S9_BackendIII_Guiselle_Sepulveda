package cl.bancoxyz.bff.common.clients;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Vistas de los microservicios tal como las necesitan los BFF (contratos consumidor: solo lo que se usa). */
public final class Dtos {

    private Dtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountDto(UUID id, UUID customerId, String number, String type, String currency, BigDecimal balance,
                             String status, Instant openedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BalanceDto(UUID accountId, BigDecimal balance, String currency, Instant asOf) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MovementDto(@JsonProperty("operation_id") String operationId, @JsonProperty("op_type") String type,
                              BigDecimal amount, @JsonProperty("balance_after") BigDecimal balanceAfter, String reference,
                              @JsonProperty("movement_status") String status, String description,
                              @JsonProperty("created_at") Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerDto(UUID id, String documentNumber, String firstName, String lastName, String email, String phone,
                              String username, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileDto(UUID customerId, String fullName, String email, String status, int accountsOpen,
                             int accountsClosed, Instant lastAccountEventAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentDto(UUID paymentId, String type, String status, UUID fromAccountId, UUID toAccountId,
                             BigDecimal amount, String currency, String description, boolean degraded,
                             String degradedReason, String failureCode, String failureReason, Instant requestedAt,
                             Instant updatedAt, Instant completedAt) {

        public boolean terminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status) || "COMPENSATED".equals(status);
        }
    }

    public record PaymentCommand(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String description) {
    }
}

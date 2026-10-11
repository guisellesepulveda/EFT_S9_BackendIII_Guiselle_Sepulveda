package cl.bancoxyz.accounts.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * El saldo es de SOLO LECTURA para JPA (updatable=false): únicamente el libro mayor (LedgerService) lo modifica,
 * con UPDATE atómico en SQL, para que ninguna carrera entre instancias pueda sobregirar una cuenta.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(nullable = false, unique = true)
    private String number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;
    @Column(nullable = false)
    private String currency;
    @Column(nullable = false, insertable = false, updatable = false)
    private BigDecimal balance;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Version
    private Long version;

    protected Account() {
    }

    public Account(UUID customerId, String number, AccountType type) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.number = number;
        this.type = type;
        this.currency = "CLP";
        this.status = AccountStatus.OPEN;
        this.openedAt = Instant.now();
    }

    public void close() {
        this.status = AccountStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getNumber() {
        return number;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}

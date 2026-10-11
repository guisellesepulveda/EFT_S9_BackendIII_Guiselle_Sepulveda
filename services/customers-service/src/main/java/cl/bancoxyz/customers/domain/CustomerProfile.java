package cl.bancoxyz.customers.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Proyección del perfil construida a partir de accounts.lifecycle. */
@Entity
@Table(name = "customer_profile")
public class CustomerProfile {

    @Id
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(name = "accounts_open", nullable = false)
    private int accountsOpen;
    @Column(name = "accounts_closed", nullable = false)
    private int accountsClosed;
    @Column(name = "last_account_event_at")
    private Instant lastAccountEventAt;

    protected CustomerProfile() {
    }

    public CustomerProfile(UUID customerId) {
        this.customerId = customerId;
    }

    public void accountOpened(Instant at) {
        accountsOpen++;
        lastAccountEventAt = at;
    }

    public void accountClosed(Instant at) {
        accountsOpen = Math.max(0, accountsOpen - 1);
        accountsClosed++;
        lastAccountEventAt = at;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public int getAccountsOpen() {
        return accountsOpen;
    }

    public int getAccountsClosed() {
        return accountsClosed;
    }

    public Instant getLastAccountEventAt() {
        return lastAccountEventAt;
    }
}

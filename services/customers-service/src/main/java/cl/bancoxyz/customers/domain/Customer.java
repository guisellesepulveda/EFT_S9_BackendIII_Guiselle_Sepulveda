package cl.bancoxyz.customers.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "customer")
public class Customer {

    @Id
    private UUID id;
    @Column(name = "document_number", nullable = false, unique = true)
    private String documentNumber;
    @Column(name = "first_name", nullable = false)
    private String firstName;
    @Column(name = "last_name", nullable = false)
    private String lastName;
    @Column(nullable = false, unique = true)
    private String email;
    private String phone;
    /** Usuario de inicio de sesión vinculado (claim sub del token); opcional. */
    @Column(unique = true)
    private String username;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    protected Customer() {
    }

    public Customer(String documentNumber, String firstName, String lastName, String email, String phone) {
        this(documentNumber, firstName, lastName, email, phone, null);
    }

    public Customer(String documentNumber, String firstName, String lastName, String email, String phone, String username) {
        this.username = username;
        this.id = UUID.randomUUID();
        this.documentNumber = documentNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.status = CustomerStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String firstName, String lastName, String email, String phone) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.updatedAt = Instant.now();
    }

    public void linkUsername(String username) {
        this.username = username;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.status = CustomerStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getUsername() {
        return username;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package cl.bancoxyz.customers.api;

import java.time.Instant;
import java.util.UUID;

import cl.bancoxyz.customers.domain.Customer;
import cl.bancoxyz.customers.domain.CustomerStatus;

public record CustomerResponse(UUID id, String documentNumber, String firstName, String lastName, String email,
                               String phone, String username, CustomerStatus status, Instant createdAt, Instant updatedAt) {

    public static CustomerResponse of(Customer c) {
        return new CustomerResponse(c.getId(), c.getDocumentNumber(), c.getFirstName(), c.getLastName(), c.getEmail(),
                c.getPhone(), c.getUsername(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }
}

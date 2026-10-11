package cl.bancoxyz.customers.api;

import java.time.Instant;
import java.util.UUID;

import cl.bancoxyz.customers.domain.Customer;
import cl.bancoxyz.customers.domain.CustomerProfile;
import cl.bancoxyz.customers.domain.CustomerStatus;

/** Perfil: datos esenciales + resumen de cuentas (proyección eventual desde accounts.lifecycle). */
public record ProfileResponse(UUID customerId, String fullName, String email, CustomerStatus status,
                              int accountsOpen, int accountsClosed, Instant lastAccountEventAt) {

    public static ProfileResponse of(Customer c, CustomerProfile p) {
        return new ProfileResponse(c.getId(), c.getFirstName() + " " + c.getLastName(), c.getEmail(), c.getStatus(),
                p == null ? 0 : p.getAccountsOpen(), p == null ? 0 : p.getAccountsClosed(),
                p == null ? null : p.getLastAccountEventAt());
    }
}

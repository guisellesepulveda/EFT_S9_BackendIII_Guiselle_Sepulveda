package cl.bancoxyz.accounts.clients;

import java.util.UUID;

/** Vista mínima del cliente que necesita accounts-service (contrato consumidor: solo lo que usa). */
public record CustomerView(UUID id, String status, String firstName, String lastName) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}

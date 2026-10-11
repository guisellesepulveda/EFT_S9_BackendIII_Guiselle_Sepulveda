package cl.bancoxyz.payments.clients;

import java.util.UUID;

public record CustomerView(UUID id, String status) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}

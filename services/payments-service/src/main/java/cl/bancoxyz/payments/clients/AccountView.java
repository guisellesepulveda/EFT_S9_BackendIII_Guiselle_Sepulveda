package cl.bancoxyz.payments.clients;

import java.math.BigDecimal;
import java.util.UUID;

/** Vista mínima de una cuenta (contrato consumidor). */
public record AccountView(UUID id, UUID customerId, String status, BigDecimal balance) {

    public boolean open() {
        return "OPEN".equals(status);
    }
}

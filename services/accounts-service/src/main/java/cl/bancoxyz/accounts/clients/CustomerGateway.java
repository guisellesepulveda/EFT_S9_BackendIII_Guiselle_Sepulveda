package cl.bancoxyz.accounts.clients;

import java.util.UUID;

import org.springframework.stereotype.Component;

import cl.bancoxyz.platform.resilience.Futures;

/** Fachada síncrona sobre la llamada resiliente a customers-service. */
@Component
public class CustomerGateway {

    private final ResilientCustomersClient resilient;

    public CustomerGateway(ResilientCustomersClient resilient) {
        this.resilient = resilient;
    }

    public CustomerView find(UUID id) {
        return Futures.await(resilient.findAsync(id));
    }
}

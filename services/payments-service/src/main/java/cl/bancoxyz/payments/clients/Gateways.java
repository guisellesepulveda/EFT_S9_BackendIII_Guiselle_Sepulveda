package cl.bancoxyz.payments.clients;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;

import cl.bancoxyz.platform.resilience.Futures;

/** Fachada síncrona sobre las llamadas resilientes (los decoradores actúan en los beans Resilient*). */
@Component
public class Gateways {

    private final ResilientAccountsClient accounts;
    private final ResilientCustomersClient customers;

    public Gateways(ResilientAccountsClient accounts, ResilientCustomersClient customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    public AccountLookup account(UUID id) {
        return Futures.await(accounts.lookupAsync(id));
    }

    public CustomerLookup customer(UUID id) {
        return Futures.await(customers.lookupAsync(id));
    }

    /** Reembolso idempotente (operationId = paymentId:REFUND). */
    public void refund(UUID accountId, UUID paymentId, BigDecimal amount) {
        Futures.await(accounts.creditAsync(accountId, new CreditRequest(paymentId + ":REFUND", amount, paymentId.toString(),
                "Reembolso por transferencia fallida")));
    }
}

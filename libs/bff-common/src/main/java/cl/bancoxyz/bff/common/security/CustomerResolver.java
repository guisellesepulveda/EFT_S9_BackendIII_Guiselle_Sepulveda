package cl.bancoxyz.bff.common.security;

import java.time.Duration;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import cl.bancoxyz.bff.common.clients.CustomersBackend;
import cl.bancoxyz.bff.common.clients.Dtos.AccountDto;
import cl.bancoxyz.bff.common.clients.Dtos.CustomerDto;
import cl.bancoxyz.bff.common.clients.AccountsBackend;
import cl.bancoxyz.bff.common.resilience.Await;
import cl.bancoxyz.platform.web.ApiException;

/**
 * Autorización por PROPIEDAD del recurso: resuelve "mi cliente" a partir del usuario autenticado (customers-service guarda
 * el vínculo username → cliente) y verifica que cada cuenta o pago consultado pertenezca a ese cliente.
 * Un recurso ajeno se informa como 404 (no se revela su existencia).
 */
public class CustomerResolver {

    private final CustomersBackend customers;
    private final AccountsBackend accounts;
    private final Cache<String, CustomerDto> cache;

    public CustomerResolver(CustomersBackend customers, AccountsBackend accounts, Duration ttl) {
        this.customers = customers;
        this.accounts = accounts;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    /** Cliente vinculado al usuario; 403 si el usuario no tiene un cliente asociado o este está inactivo. */
    public CustomerDto current(String bearer, String username) {
        CustomerDto cached = cache.getIfPresent(username);
        if (cached != null) {
            return cached;
        }
        CustomerDto c;
        try {
            c = Await.of(customers.byUsername(bearer, username), "customers");
        } catch (ApiException e) {
            if (e.status().value() == 404) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NO_CUSTOMER_LINKED",
                        "El usuario no tiene un cliente asociado");
            }
            throw e;
        }
        if (!"ACTIVE".equals(c.status())) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "CUSTOMER_INACTIVE", "El cliente está inactivo");
        }
        cache.put(username, c);
        return c;
    }

    /** Cuenta del cliente; 404 si no existe o es de otro cliente. */
    public AccountDto ownAccount(String bearer, CustomerDto customer, UUID accountId) {
        AccountDto a;
        try {
            a = Await.of(accounts.get(bearer, accountId), "accounts");
        } catch (ApiException e) {
            if (e.status().value() == 404) {
                throw ApiException.notFound("Cuenta", accountId);
            }
            throw e;
        }
        if (!customer.id().equals(a.customerId())) {
            throw ApiException.notFound("Cuenta", accountId);
        }
        return a;
    }
}

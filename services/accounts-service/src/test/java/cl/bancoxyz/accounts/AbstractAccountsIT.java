package cl.bancoxyz.accounts;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

abstract class AbstractAccountsIT {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    static RequestPostProcessor scope(String... scopes) {
        return jwt().authorities(java.util.Arrays.stream(scopes)
                .<GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    static final RequestPostProcessor WEB_READ = scope("web:read");
    static final RequestPostProcessor WEB_WRITE = scope("web:write");
    static final RequestPostProcessor INTERNAL = scope("internal");
    static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE account, account_operation, outbox_event, processed_event");
    }

    /** Inserta una cuenta directamente (más rápido que pasar por la API) con el saldo indicado. */
    protected UUID account(UUID customerId, String balance, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account (id, customer_id, number, type, currency, balance, status, opened_at)
                VALUES (?, ?, ?, 'SAVINGS', 'CLP', ?, ?, now())
                """, id, customerId, "T" + Math.abs(id.hashCode()), new BigDecimal(balance), status);
        return id;
    }

    protected UUID account(String balance) {
        return account(UUID.randomUUID(), balance, "OPEN");
    }

    protected BigDecimal balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance FROM account WHERE id = ?", BigDecimal.class, accountId);
    }
}

package cl.bancoxyz.payments;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;

abstract class AbstractPaymentsIT {

    /** Un solo servidor simulado por JVM de tests (accounts, customers y auth-server). */
    static final FakeBackends fake = new FakeBackends();

    static {
        try {
            fake.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        r.add("clients.accounts.url", fake::baseUrl);
        r.add("clients.customers.url", fake::baseUrl);
        r.add("AUTH_TOKEN_URI", fake::tokenUrl);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    static RequestPostProcessor scope(String... scopes) {
        return jwt().authorities(java.util.Arrays.stream(scopes)
                .<GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    static final RequestPostProcessor WEB_READ = scope("web:read");
    static final RequestPostProcessor WEB_WRITE = scope("web:write");
    static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @BeforeEach
    void cleanAll() {
        jdbc.execute("TRUNCATE payment_history, payment, outbox_event, processed_event CASCADE");
        fake.reset();
    }
}

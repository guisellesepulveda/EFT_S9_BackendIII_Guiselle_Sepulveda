package cl.bancoxyz.bffmobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Rate limiting en su propio contexto (los cubos son estado del contexto compartido). */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-mobile.yml", "test.context=ratelimit",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "MOBILE_CLIENT_SECRET=mobile-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD,
        "bff.mobile.rate-limit.burst=3", "bff.mobile.rate-limit.requests-per-minute=60", "bff.mobile.rate-limit.auth-requests-per-minute=4"})
@AutoConfigureMockMvc
class MobileRateLimitIT {

    static final FakeBackends fake = new FakeBackends();

    @BeforeAll
    static void start() throws IOException {
        fake.start();
    }

    @AfterAll
    static void stop() throws IOException {
        fake.server.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("clients.accounts.url", fake::baseUrl);
        r.add("clients.customers.url", fake::baseUrl);
        r.add("clients.payments.url", fake::baseUrl);
        r.add("bff.mobile.auth.token-uri", () -> fake.baseUrl() + "/oauth2/token");
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor token(String user, String... scopes) {
        return jwt().jwt(j -> j.subject(user))
                .authorities(java.util.Arrays.stream(scopes).<GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    @Test
    void rateLimitIsPerUserNotGlobal() throws Exception {
        RequestPostProcessor spammer = token("spammer", "mobile:read");
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            int s = mvc.perform(get("/mobile/accounts").with(spammer)).andReturn().getResponse().getStatus();
            if (s == 429) {
                limited++;
            } else {
                ok++;
            }
        }
        assertThat(ok).isBetween(3, 4);                  // burst = 3 (+1 por reposición durante la prueba)
        assertThat(limited).isGreaterThanOrEqualTo(4);
        mvc.perform(get("/mobile/accounts").with(spammer)).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // otro usuario no se ve afectado (el 403/404 de negocio prueba que pasó el limitador)
        int other = mvc.perform(get("/mobile/accounts").with(token("otro-usuario", "mobile:read")))
                .andReturn().getResponse().getStatus();
        assertThat(other).isNotEqualTo(429);
    }

    @Test
    void loginEndpointsAreRateLimitedPerIp() throws Exception {
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            int s = mvc.perform(post("/mobile/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"rt\"}"))
                    .andReturn().getResponse().getStatus();
            limited += s == 429 ? 1 : 0;
        }
        assertThat(limited).isGreaterThanOrEqualTo(3);   // auth-requests-per-minute = 4
    }
}

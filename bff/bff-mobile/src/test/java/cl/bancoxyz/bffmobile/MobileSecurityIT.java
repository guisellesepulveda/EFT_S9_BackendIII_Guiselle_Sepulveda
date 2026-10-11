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

/**
 * Seguridad del canal móvil: rechazo de tokens de otros canales (evidencia clave), proxy de autenticación con el secreto del
 * cliente confidencial, y rate limiting por usuario / por IP.
 */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-mobile.yml", "test.context=security",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "MOBILE_CLIENT_SECRET=mobile-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD,
        "bff.mobile.rate-limit.burst=1000", "bff.mobile.rate-limit.requests-per-minute=6000", "bff.mobile.rate-limit.auth-requests-per-minute=1000"})
@AutoConfigureMockMvc
class MobileSecurityIT {

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

    @BeforeEach
    void reset() {
        fake.reset();
    }

    private static RequestPostProcessor token(String user, String... scopes) {
        return jwt().jwt(j -> j.subject(user))
                .authorities(java.util.Arrays.stream(scopes).<GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    // ------------------------------------------------------------------ rechazo de otros canales
    @Test
    void swaggerUiGetsAUsableCspWhileTheApiKeepsTheStrictOne() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("default-src 'self'")));
        mvc.perform(get("/mobile/home")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
    }

    @Test
    void tokensFromOtherChannelsAreRejectedOnEveryMobileEndpoint() throws Exception {
        String transfer = "{\"from\":\"" + UUID.randomUUID() + "\",\"to\":\"" + UUID.randomUUID() + "\",\"amount\":10}";
        String[][] foreign = {{"web:read", "web:write"}, {"atm:operate"}, {"internal"}, {"openid"}};
        for (String[] scopes : foreign) {
            RequestPostProcessor t = token("intruso", scopes);
            mvc.perform(get("/mobile/accounts").with(t)).andExpect(status().isForbidden());
            mvc.perform(get("/mobile/home").with(t)).andExpect(status().isForbidden());
            mvc.perform(get("/mobile/accounts/" + UUID.randomUUID() + "/movements").with(t)).andExpect(status().isForbidden());
            mvc.perform(post("/mobile/transfers").header("Idempotency-Key", "abcdefgh12")
                    .with(t).contentType(MediaType.APPLICATION_JSON).content(transfer)).andExpect(status().isForbidden());
        }
        assertThat(fake.seen).isEmpty();           // ningún servicio fue invocado con un token ajeno
    }

    @Test
    void readOnlyMobileTokenCannotTransfer() throws Exception {
        mvc.perform(post("/mobile/transfers").header("Idempotency-Key", "abcdefgh12")
                        .with(token("ana", "mobile:read")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"" + UUID.randomUUID() + "\",\"to\":\"" + UUID.randomUUID() + "\",\"amount\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutTokenOrOutsideTheApiIsRejected() throws Exception {
        mvc.perform(get("/mobile/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/mobile/accounts").header("Authorization", "Bearer token-basura")).andExpect(status().isUnauthorized());
        mvc.perform(get("/otra-cosa").with(token("ana", "mobile:read", "mobile:transfer"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ proxy de autenticación (cliente confidencial)
    @Test
    void theBffExchangesTheCodeWithTheClientSecretTheAppNeverSees() throws Exception {
        mvc.perform(post("/mobile/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\",\"codeVerifier\":\"" + "v".repeat(64) + "\",\"redirectUri\":\"bancoxyz://callback\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("at-123")).andExpect(jsonPath("$.refreshToken").value("rt-456"))
                .andExpect(jsonPath("$.expiresIn").value(299)).andExpect(jsonPath("$.idToken").doesNotExist())
                .andExpect(jsonPath("$.id_token").doesNotExist());

        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("mobile-client:mobile-secret".getBytes());
        assertThat(fake.tokenAuth).containsExactly(basic);                       // el secreto lo pone el BFF
        assertThat(fake.tokenForms.get(0)).contains("grant_type=authorization_code").contains("code_verifier=" + "v".repeat(64))
                .contains("code=abc").contains("redirect_uri=bancoxyz");
    }

    @Test
    void refreshTokensAreExchangedWithTheRefreshGrant() throws Exception {
        mvc.perform(post("/mobile/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"rt-old\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").value("rt-456"));
        assertThat(fake.tokenForms.get(0)).contains("grant_type=refresh_token").contains("refresh_token=rt-old");
    }

    @Test
    void onlyRegisteredRedirectUrisAreForwardedToTheAuthServer() throws Exception {
        mvc.perform(post("/mobile/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\",\"codeVerifier\":\"" + "v".repeat(64) + "\",\"redirectUri\":\"https://evil.example/cb\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REDIRECT_URI_NOT_ALLOWED"));
        assertThat(fake.tokenForms).isEmpty();
    }

    @Test
    void authServerErrorsAreTranslatedWithoutLeakingDetails() throws Exception {
        fake.authMode = "invalid_grant";
        mvc.perform(post("/mobile/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"mala\",\"codeVerifier\":\"" + "v".repeat(64) + "\",\"redirectUri\":\"bancoxyz://callback\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH_INVALID_GRANT"))
                .andExpect(jsonPath("$.detail").value("No se pudo completar la autenticación"));
        fake.authMode = "down";
        mvc.perform(post("/mobile/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"rt\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
    }

    @Test
    void invalidAuthRequestsAreRejected() throws Exception {
        mvc.perform(post("/mobile/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"\",\"codeVerifier\":\"corto\"}"))
                .andExpect(status().isBadRequest());
    }
}

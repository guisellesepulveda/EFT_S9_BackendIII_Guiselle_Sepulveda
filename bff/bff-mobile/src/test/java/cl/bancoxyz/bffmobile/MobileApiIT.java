package cl.bancoxyz.bffmobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.RecordedRequest;

@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-mobile.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "MOBILE_CLIENT_SECRET=mobile-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD,
        "bff.mobile.rate-limit.burst=1000", "bff.mobile.rate-limit.requests-per-minute=6000",
        "resilience4j.retry.instances.accounts.wait-duration=20ms", "resilience4j.retry.instances.customers.wait-duration=20ms",
        "resilience4j.retry.instances.payments.wait-duration=20ms"})
@AutoConfigureMockMvc
class MobileApiIT {

    static final FakeBackends fake = new FakeBackends();
    static final String BEARER = "Bearer mobile-token-xyz";

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
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        fake.reset();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private static int seq = 0;

    private static String user() {
        return "mobile-user-" + (++seq);
    }

    private static RequestPostProcessor token(String user, String... scopes) {
        return jwt().jwt(j -> j.subject(user).tokenValue("mobile-token-xyz").claim("channel", "mobile"))
                .authorities(java.util.Arrays.stream(scopes).<GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    private static RequestPostProcessor mobile(String user) {
        return token(user, "mobile:read", "mobile:transfer");
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b; // el token viaja en el Jwt autenticado (jwt() de Spring Security Test); el BFF lo reenvía a los servicios
    }

    private FakeBackends.Acc setup(String user, String... balances) {
        FakeBackends.Cust c = fake.customer(user);
        FakeBackends.Acc first = null;
        int i = 0;
        for (String b : balances) {
            FakeBackends.Acc a = fake.account(c.id(), "CL300000000" + (++i), "OPEN", b);
            first = first == null ? a : first;
        }
        return first;
    }

    // ------------------------------------------------------------------ datos mínimos
    @Test
    void accountsAreMinimalDtosWithOnlyEssentialFields() throws Exception {
        String u = user();
        setup(u, "7499.50", "2500.50");
        fake.account(fake.customersByUser.get(u).id(), "CL3999999999", "CLOSED", "0");

        String json = mvc.perform(authed(get("/mobile/accounts")).with(mobile(u))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))                    // solo cuentas abiertas
                .andExpect(jsonPath("$[0].number").value("CL3000000001"))
                .andReturn().getResponse().getContentAsString();
        // NO viaja nada que la pantalla no use
        assertThat(json).doesNotContain("customerId").doesNotContain("currency").doesNotContain("openedAt").doesNotContain("status");
        assertThat(json.length()).isLessThan(400);
    }

    @Test
    void homeGivesTotalBalanceAndAccountsInOneCall() throws Exception {
        String u = user();
        setup(u, "100.25", "200");
        mvc.perform(authed(get("/mobile/home")).with(mobile(u))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBalance").value(300.25)).andExpect(jsonPath("$.accounts.length()").value(2));
    }

    @Test
    void partialResponsesReturnOnlyTheRequestedFields() throws Exception {
        String u = user();
        FakeBackends.Acc a = setup(u, "10");
        mvc.perform(authed(get("/mobile/accounts?fields=id,balance")).with(mobile(u))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(a.id().toString())).andExpect(jsonPath("$[0].balance").value(10))
                .andExpect(jsonPath("$[0].number").doesNotExist()).andExpect(jsonPath("$[0].type").doesNotExist());
        mvc.perform(authed(get("/mobile/accounts?fields=id,secretos")).with(mobile(u))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELDS"));
        mvc.perform(authed(get("/mobile/accounts/" + a.id() + "/movements?fields=type,amount")).with(mobile(u))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").exists()).andExpect(jsonPath("$[0].at").doesNotExist());
    }

    @Test
    void movementsAreShortPaginatedAndOwnedByTheUser() throws Exception {
        String u = user();
        FakeBackends.Acc a = setup(u, "10");
        mvc.perform(authed(get("/mobile/accounts/" + a.id() + "/movements?limit=500")).with(mobile(u))).andExpect(status().isOk());
        assertThat(fake.seen.stream().filter(r -> r.getPath().contains("/movements")).map(RecordedRequest::getPath))
                .allMatch(p -> p.endsWith("limit=20"));                         // tope duro: 20 movimientos

        FakeBackends.Acc foreign = fake.account(fake.customer(user()).id(), "CL3000009999", "OPEN", "5");
        mvc.perform(authed(get("/mobile/accounts/" + foreign.id() + "/movements")).with(mobile(u))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ transferencias
    @Test
    void transferNeedsAnIdempotencyKeyAndOwnAccountAndForwardsTheBearer() throws Exception {
        String u = user();
        FakeBackends.Acc from = setup(u, "9000");
        String body = "{\"from\":\"" + from.id() + "\",\"to\":\"" + UUID.randomUUID() + "\",\"amount\":150.75,\"note\":\"almuerzo\"}";

        mvc.perform(authed(post("/mobile/transfers")).with(mobile(u)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(authed(post("/mobile/transfers")).with(mobile(u)).header("Idempotency-Key", "mob-12345678")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").exists()).andExpect(jsonPath("$.amount").doesNotExist());   // respuesta mínima

        RecordedRequest sent = fake.seen.stream().filter(r -> "/payments/transfer".equals(r.getPath())).findFirst().orElseThrow();
        assertThat(sent.getHeader("Idempotency-Key")).isEqualTo("mobile-" + fake.customersByUser.get(u).id() + "-mob-12345678");
        assertThat(sent.getHeader("Authorization")).isEqualTo(BEARER);

        FakeBackends.Acc foreign = fake.account(fake.customer(user()).id(), "CL3000008888", "OPEN", "5");
        mvc.perform(authed(post("/mobile/transfers")).with(mobile(u)).header("Idempotency-Key", "mob-87654321")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"" + foreign.id() + "\",\"to\":\"" + UUID.randomUUID() + "\",\"amount\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transferStatusIsMinimal() throws Exception {
        String u = user();
        FakeBackends.Acc from = setup(u, "9000");
        String res = mvc.perform(authed(post("/mobile/transfers")).with(mobile(u)).header("Idempotency-Key", "mob-12345678")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"" + from.id() + "\",\"to\":\"" + UUID.randomUUID() + "\",\"amount\":5}")).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res).get("id").asText();
        mvc.perform(authed(get("/mobile/transfers/" + id)).with(mobile(u))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.length()").value(2));
    }

    // ------------------------------------------------------------------ degradación
    @Test
    void whenAccountsIsDownTheAppGetsASafe503() throws Exception {
        String u = user();
        setup(u, "10");
        fake.accountsFail = true;
        mvc.perform(authed(get("/mobile/accounts")).with(mobile(u))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNTS_UNAVAILABLE")).andExpect(jsonPath("$.detail").value("Servicio temporalmente no disponible"));
    }

    @Test
    void aUserWithoutACustomerIsForbidden() throws Exception {
        mvc.perform(authed(get("/mobile/accounts")).with(mobile("huerfano-" + user()))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_CUSTOMER_LINKED"));
    }
}

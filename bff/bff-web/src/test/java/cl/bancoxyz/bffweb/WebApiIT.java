package cl.bancoxyz.bffweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cl.bancoxyz.bff.common.security.CustomerResolver;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.RecordedRequest;

/** BFF web con la configuración REAL del config-repo y los tres servicios simulados. */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-web.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "WEB_CLIENT_SECRET=web-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD,
        "resilience4j.retry.instances.accounts.wait-duration=20ms", "resilience4j.retry.instances.customers.wait-duration=20ms",
        "resilience4j.retry.instances.payments.wait-duration=20ms"})
@AutoConfigureMockMvc
class WebApiIT {

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
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    CircuitBreakerRegistry breakers;
    @Autowired
    CustomerResolver resolver;

    @BeforeEach
    void reset() {
        fake.reset();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        // la caché de "mi cliente" es estática por usuario: se vacía usando usuarios distintos por test (ver user())
    }

    private static int seq = 0;

    /** Usuario distinto en cada test (la caché de CustomerResolver es por usuario). */
    private static String user() {
        return "user" + (++seq);
    }

    private static final String TOKEN = "web-token-123";

    private RequestPostProcessor session(String username, String... scopes) {
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, TOKEN, Instant.now(),
                Instant.now().plusSeconds(600), Set.of(scopes));
        return request -> {
            request = oauth2Login().attributes(a -> a.put("sub", username))
                    .authorities(java.util.Arrays.stream(scopes).map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toArray(SimpleGrantedAuthority[]::new))
                    .postProcessRequest(request);
            return oauth2Client("web-client").accessToken(token).postProcessRequest(request);
        };
    }

    private RequestPostProcessor web(String username) {
        return session(username, "web:read", "web:write");
    }

    private FakeBackends.Acc setup(String username, String... balances) {
        FakeBackends.Cust c = fake.customer(username);
        FakeBackends.Acc first = null;
        int i = 0;
        for (String b : balances) {
            FakeBackends.Acc a = fake.account(c.id(), "CL100000000" + (++i), "OPEN", b);
            first = first == null ? a : first;
        }
        return first;
    }

    // ------------------------------------------------------------------ tablero agregado
    @Test
    void dashboardAggregatesCustomerProfileAccountsAndRecentMovements() throws Exception {
        String u = user();
        setup(u, "7499.50", "2500.50");
        fake.account(fake.customersByUser.get(u).id(), "CL1000000099", "CLOSED", "0");

        mvc.perform(get("/web/dashboard?movements=2").with(web(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.fullName").value("Camila Rojas"))
                .andExpect(jsonPath("$.profile.accountsOpen").value(2))
                .andExpect(jsonPath("$.accounts.length()").value(3))
                .andExpect(jsonPath("$.totalBalance").value(10000.00))        // solo cuentas abiertas
                .andExpect(jsonPath("$.accounts[0].recentMovements.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].movementsAvailable").value(true))
                .andExpect(jsonPath("$.degraded").value(false));

        // el token del usuario se reenvió a los servicios (token relay)
        assertThat(fake.seen).filteredOn(r -> r.getPath().startsWith("/accounts")).isNotEmpty()
                .allMatch(r -> ("Bearer " + TOKEN).equals(r.getHeader("Authorization")));
    }

    @Test
    void aggregationRunsInParallelAndMyCustomerIsCached() throws Exception {
        String u = user();
        setup(u, "100", "200", "300");
        fake.delayMs = 400;

        long t0 = System.nanoTime();
        mvc.perform(get("/web/dashboard").with(web(u))).andExpect(status().isOk());
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        // secuencial: cliente + perfil + cuentas + 3 movimientos = 6 × 400 ms = 2400 ms. En paralelo: 3 etapas ≈ 1200 ms
        assertThat(ms).isLessThan(2000);

        int lookups = fake.customerLookups.get();
        mvc.perform(get("/web/dashboard").with(web(u))).andExpect(status().isOk());
        assertThat(fake.customerLookups.get()).isEqualTo(lookups);                  // "mi cliente" no se vuelve a pedir
    }

    @Test
    void dashboardDegradesGracefullyWhenProfileAndMovementsFail() throws Exception {
        String u = user();
        setup(u, "100", "200");
        fake.profileFail = true;
        fake.movementsFail = true;

        mvc.perform(get("/web/dashboard").with(web(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.profile").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.accounts[0].movementsAvailable").value(false))
                .andExpect(jsonPath("$.accounts[0].recentMovements").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.unavailable").isArray())
                .andExpect(jsonPath("$.totalBalance").value(300));                  // cuentas y saldos sí se entregan
    }

    @Test
    void withoutAccountsServiceThereIsNoDashboardJustASafe503() throws Exception {
        String u = user();
        setup(u, "100");
        fake.accountsFail = true;

        mvc.perform(get("/web/dashboard").with(web(u)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNTS_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Servicio temporalmente no disponible"));
    }

    // ------------------------------------------------------------------ propiedad del recurso
    @Test
    void accountsOfOtherCustomersAreInvisible() throws Exception {
        String u = user();
        setup(u, "100");
        FakeBackends.Cust other = fake.customer(user());
        FakeBackends.Acc foreign = fake.account(other.id(), "CL1000009999", "OPEN", "5000");

        mvc.perform(get("/web/accounts/" + foreign.id()).with(web(u))).andExpect(status().isNotFound());
        mvc.perform(get("/web/accounts/" + foreign.id() + "/movements").with(web(u))).andExpect(status().isNotFound());
        mvc.perform(post("/web/transfers").with(web(u)).with(csrf()).header("Idempotency-Key", "key-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + foreign.id() + "\",\"toAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":10}"))
                .andExpect(status().isNotFound());
        assertThat(fake.transferBodies).isEmpty();                                   // jamás llegó a payments
    }

    @Test
    void aUserWithoutALinkedCustomerIsForbidden() throws Exception {
        mvc.perform(get("/web/dashboard").with(web("sin-cliente-" + user())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_CUSTOMER_LINKED"));
    }

    // ------------------------------------------------------------------ paginación y filtros
    @Test
    void accountsAreFilterableAndPaginated() throws Exception {
        String u = user();
        FakeBackends.Cust c = fake.customer(u);
        for (int i = 1; i <= 5; i++) {
            fake.account(c.id(), "CL20000000" + i, i == 5 ? "CLOSED" : "OPEN", "10");
        }
        mvc.perform(get("/web/accounts?size=2").with(web(u))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/web/accounts?status=CLOSED").with(web(u))).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/web/accounts?status=OPEN&type=SAVINGS").with(web(u))).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void movementsAreFilterableByTypeStatusAndDate() throws Exception {
        String u = user();
        FakeBackends.Acc a = setup(u, "100");
        mvc.perform(get("/web/accounts/" + a.id() + "/movements").with(web(u))).andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].type").value("DEBIT"));          // más reciente primero
        mvc.perform(get("/web/accounts/" + a.id() + "/movements?type=CREDIT").with(web(u))).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/web/accounts/" + a.id() + "/movements?status=PENDING").with(web(u))).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/web/accounts/" + a.id() + "/movements?from=2026-10-11").with(web(u))).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/web/accounts/" + a.id() + "/movements?to=2026-10-09").with(web(u))).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/web/accounts/" + a.id() + "/movements?size=1&page=1").with(web(u)))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].type").value("CREDIT"));
    }

    // ------------------------------------------------------------------ transferencias
    @Test
    void transferRequiresAnIdempotencyKeyAndForwardsItToPayments() throws Exception {
        String u = user();
        FakeBackends.Acc from = setup(u, "9000");
        String body = "{\"fromAccountId\":\"" + from.id() + "\",\"toAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":2500.50,\"description\":\"Arriendo\"}";

        mvc.perform(post("/web/transfers").with(web(u)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mvc.perform(post("/web/transfers").with(web(u)).with(csrf()).header("Idempotency-Key", "abc12345")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));

        RecordedRequest sent = fake.seen.stream().filter(r -> "/payments/transfer".equals(r.getPath())).findFirst().orElseThrow();
        assertThat(sent.getHeader("Idempotency-Key")).isEqualTo("web-" + fake.customersByUser.get(u).id() + "-abc12345");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(fake.transferBodies.get(0)).contains("\"amount\":2500.50").contains("Arriendo");
    }

    @Test
    void invalidTransfersAreRejectedByValidation() throws Exception {
        String u = user();
        FakeBackends.Acc from = setup(u, "9000");
        for (String amount : new String[] {"0", "-5", "1.005"}) {
            mvc.perform(post("/web/transfers").with(web(u)).with(csrf()).header("Idempotency-Key", "abc12345")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fromAccountId\":\"" + from.id() + "\",\"toAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":" + amount + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(fake.transferBodies).isEmpty();
    }

    @Test
    void transferStatusIsOnlyVisibleForMyAccounts() throws Exception {
        String u = user();
        FakeBackends.Acc from = setup(u, "9000");
        String body = "{\"fromAccountId\":\"" + from.id() + "\",\"toAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":10}";
        String res = mvc.perform(post("/web/transfers").with(web(u)).with(csrf()).header("Idempotency-Key", "abc12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res).get("paymentId").asText();

        mvc.perform(get("/web/transfers/" + id).with(web(u))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        String stranger = user();
        setup(stranger, "1");
        mvc.perform(get("/web/transfers/" + id).with(web(stranger))).andExpect(status().isNotFound());
    }
}

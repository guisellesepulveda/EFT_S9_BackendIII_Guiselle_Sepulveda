package cl.bancoxyz.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Resilience4j real (CircuitBreaker + Retry + TimeLimiter + Bulkhead) sobre Feign, con la configuración REAL de
 * infra/config-repo/accounts-service.yml, contra un customers-service simulado. Incluye el token interno
 * client_credentials: el simulador también hace de auth-server.
 */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/accounts-service.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "platform.outbox.enabled=false",
        "management.zipkin.tracing.export.enabled=false", "INTERNAL_SERVICE_CLIENT_SECRET=internal-secret",
        "resilience4j.circuitbreaker.instances.customers.wait-duration-in-open-state=1500ms",
        "resilience4j.timelimiter.instances.customers.timeout-duration=800ms",
        "resilience4j.retry.instances.customers.wait-duration=50ms"})
@AutoConfigureMockMvc
class CustomersResilienceIT extends AbstractAccountsIT {

    static MockWebServer server = new MockWebServer();
    static final AtomicInteger customerCalls = new AtomicInteger();
    static final AtomicInteger tokenCalls = new AtomicInteger();
    static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    /** comportamiento del "customers-service" simulado */
    static volatile String mode = "ok";

    @BeforeAll
    static void start() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.startsWith("/oauth2/token")) {
                    tokenCalls.incrementAndGet();
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"internal-token-123\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"internal\"}");
                }
                customerCalls.incrementAndGet();
                lastAuthorization.set(request.getHeader("Authorization"));
                String id = path.substring(path.lastIndexOf('/') + 1);
                return switch (mode) {
                    case "ok" -> new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"" + id + "\",\"status\":\"ACTIVE\",\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}");
                    case "notfound" -> new MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("{}");
                    case "error" -> new MockResponse().setResponseCode(503);
                    case "slow" -> new MockResponse().setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                            .setHeader("Content-Type", "application/json").setBody("{\"id\":\"" + id + "\",\"status\":\"ACTIVE\"}");
                    default -> new MockResponse().setResponseCode(500);
                };
            }
        });
        server.start();
    }

    @AfterAll
    static void stop() throws IOException {
        server.shutdown();
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("clients.customers.url", () -> server.url("/").toString().replaceAll("/$", ""));
        r.add("AUTH_TOKEN_URI", () -> server.url("/oauth2/token").toString());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    CircuitBreakerRegistry breakers;

    CircuitBreaker breaker() {
        return breakers.circuitBreaker("customers");
    }

    @BeforeEach
    void reset() {
        mode = "ok";
        customerCalls.set(0);
        breaker().reset();
    }

    private void open(String customerId, int expectedStatus) throws Exception {
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId + "\",\"type\":\"SAVINGS\"}")).andExpect(status().is(expectedStatus));
    }

    @Test
    void successfulLookupSendsTheInternalServiceToken() throws Exception {
        open(UUID.randomUUID().toString(), 201);
        assertThat(customerCalls.get()).isEqualTo(1);
        assertThat(lastAuthorization.get()).isEqualTo("Bearer internal-token-123");
        assertThat(tokenCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void businessNotFoundIsNotAnOutageAndIsNotRetried() throws Exception {
        mode = "notfound";
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + UUID.randomUUID() + "\",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        assertThat(customerCalls.get()).isEqualTo(1);                       // sin reintentos
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero(); // no cuenta como falla del circuito
    }

    @Test
    void serverErrorsAreRetriedThenFallBackToA503() throws Exception {
        mode = "error";
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + UUID.randomUUID() + "\",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CUSTOMERS_UNAVAILABLE"));
        assertThat(customerCalls.get()).isEqualTo(3);                        // maxAttempts = 3
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Long.class)).isZero();
    }

    @Test
    void repeatedFailuresOpenTheCircuitWhichThenFailsFastAndRecovers() throws Exception {
        mode = "error";
        // 5 llamadas fallidas (mínimo para evaluar) → el circuito se abre
        for (int i = 0; i < 2; i++) {
            open(UUID.randomUUID().toString(), 503);
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = customerCalls.get();
        open(UUID.randomUUID().toString(), 503);                              // falla rápido: NO llega al servicio
        assertThat(customerCalls.get()).isEqualTo(before);

        // el estado es visible por Actuator (evidencia para el informe)
        mvc.perform(get("/actuator/circuitbreakers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreakers.customers.state").value("OPEN"));

        // se recupera: pasado el tiempo en OPEN pasa a HALF_OPEN, y llamadas exitosas lo cierran
        mode = "ok";
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            open(UUID.randomUUID().toString(), 201);
        });
        for (int i = 0; i < 3; i++) {
            open(UUID.randomUUID().toString(), 201);
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void slowResponsesAreCutByTheTimeLimiter() throws Exception {
        mode = "slow";
        long t0 = System.nanoTime();
        open(UUID.randomUUID().toString(), 503);
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertThat(ms).isLessThan(8_000);   // 3 intentos × 800 ms ≈ 2,5 s, nunca los 3 s de cada respuesta lenta ×3
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isPositive();
    }
}

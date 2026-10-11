package cl.bancoxyz.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * El gateway real (rutas de infra/config-repo/api-gateway.yml) delante de servicios simulados registrados con el
 * SimpleDiscoveryClient: valida enrutamiento lb://, JWT, balanceo, rate limiting, circuit breaker y traza.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:../config-repo/api-gateway.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "management.zipkin.tracing.export.enabled=false",
        // El enrutamiento se prueba en HTTP plano y con actuator en el mismo puerto; GatewayTlsIT cubre HTTPS y el puerto de gestión
        "server.ssl.enabled=false", "management.server.port=", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD,
        "gateway.rate-limit.replenish-rate=1", "gateway.rate-limit.burst-capacity=6",
        "resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state=1500ms",
        "resilience4j.circuitbreaker.configs.default.minimum-number-of-calls=3"})
@AutoConfigureWebTestClient(timeout = "15000")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability // en tests Boot apaga tracing y métricas
class GatewayIT {

    static final MockWebServer customersA = new MockWebServer();
    static final MockWebServer customersB = new MockWebServer();
    static final MockWebServer accounts = new MockWebServer();
    static final List<RecordedRequest> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile int customersStatus = 200;

    @BeforeAll
    static void start() throws IOException {
        Dispatcher ok = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                return new MockResponse().setResponseCode(customersStatus).setHeader("Content-Type", "application/json")
                        .setBody("{\"path\":\"" + request.getPath() + "\"}");
            }
        };
        customersA.setDispatcher(ok);
        customersB.setDispatcher(ok);
        accounts.setDispatcher(ok);
        customersA.start();
        customersB.start();
        accounts.start();
    }

    @AfterAll
    static void stop() throws IOException {
        customersA.shutdown();
        customersB.shutdown();
        accounts.shutdown();
    }

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry r) {
        r.add("spring.cloud.discovery.client.simple.instances.customers-service[0].uri", () -> "http://localhost:" + customersA.getPort());
        r.add("spring.cloud.discovery.client.simple.instances.customers-service[1].uri", () -> "http://localhost:" + customersB.getPort());
        r.add("spring.cloud.discovery.client.simple.instances.accounts-service[0].uri", () -> "http://localhost:" + accounts.getPort());
        // payments-service y batch-service: sin instancias (caídos)
        r.add("spring.ssl.bundle.jks.gateway-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.gateway-tls.truststore.location", TlsTestCerts::truststore);
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.security.oauth2.jwt.ReactiveJwtDecoder jwtDecoder;
    @Autowired
    WebTestClient web;
    @Autowired
    CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        org.mockito.Mockito.when(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                reactor.core.publisher.Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("token inválido")));
        customersStatus = 200;
        seen.clear();
        breakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    }

    private WebTestClient as(String user) {
        return web.mutateWith(mockJwt().jwt(j -> j.subject(user).claim("scope", "web:read")));
    }

    // ------------------------------------------------------------------ seguridad
    @Test
    void requestsWithoutAValidTokenAreRejectedAtTheEdge() {
        web.get().uri("/api/customers/1").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/customers/1").header("Authorization", "Bearer basura").exchange().expectStatus().isUnauthorized();
        assertThat(seen).isEmpty(); // ni siquiera llegó a un servicio
    }

    @Test
    void onlyOperationalEndpointsArePublic() {
        web.get().uri("/actuator/health").exchange().expectStatus().isOk();
        web.get().uri("/actuator/gateway/routes").exchange().expectStatus().isUnauthorized();
        web.get().uri("/no/existe").exchange().expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------------------ enrutamiento
    @Test
    void routesStripTheApiPrefixAndForwardTheToken() throws Exception {
        org.mockito.Mockito.when(jwtDecoder.decode("token-original")).thenReturn(reactor.core.publisher.Mono.just(
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token-original").header("alg", "none").subject("ana")
                        .claim("scope", "web:read").issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(60)).build()));
        web.get().uri("/api/accounts/abc/balance?x=1").header("Authorization", "Bearer token-original")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.path").isEqualTo("/accounts/abc/balance?x=1");
        RecordedRequest r = accounts.takeRequest(2, TimeUnit.SECONDS);
        assertThat(r.getPath()).isEqualTo("/accounts/abc/balance?x=1");
        assertThat(r.getHeader("Authorization")).isEqualTo("Bearer token-original"); // el token llega intacto al servicio
    }

    @Test
    void adminRoutesAreRewrittenToTheServiceAdminPath() {
        as("root").get().uri("/api/admin/customers-service/security-alerts").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/admin/security-alerts");
    }

    // ------------------------------------------------------------------ balanceo
    @Test
    void requestsAreSpreadAcrossAllInstancesOfAService() {
        int before = customersA.getRequestCount() + customersB.getRequestCount();
        int a0 = customersA.getRequestCount();
        int b0 = customersB.getRequestCount();
        for (int i = 0; i < 6; i++) {
            as("user-" + i).get().uri("/api/customers/" + i).exchange().expectStatus().isOk();
        }
        assertThat(customersA.getRequestCount() + customersB.getRequestCount()).isEqualTo(before + 6);
        assertThat(customersA.getRequestCount() - a0).isEqualTo(3);   // round-robin de Spring Cloud LoadBalancer
        assertThat(customersB.getRequestCount() - b0).isEqualTo(3);
    }

    // ------------------------------------------------------------------ rate limiting
    @Test
    void rateLimitIsPerUserAndReturns429WithHeaders() {
        int allowed = 0;
        int rejected = 0;
        String lastRemaining = null;
        for (int i = 0; i < 12; i++) {
            var res = as("spammer").get().uri("/api/accounts/x").exchange().returnResult(String.class);
            if (res.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                rejected++;
            } else {
                allowed++;
                lastRemaining = res.getResponseHeaders().getFirst("X-RateLimit-Remaining");
            }
        }
        assertThat(allowed).isBetween(6, 8);          // burst 6 (+ tokens repuestos durante la prueba)
        assertThat(rejected).isGreaterThanOrEqualTo(4);
        assertThat(lastRemaining).isNotNull();
        // otro usuario tiene su propia cubeta
        as("good-citizen").get().uri("/api/accounts/x").exchange().expectStatus().isOk()
                .expectHeader().exists("X-RateLimit-Remaining");
    }

    // ------------------------------------------------------------------ circuit breaker + fallback
    @Test
    void aServiceWithNoInstancesAnswersTheFallbackProblemDetail() {
        as("u1").get().uri("/api/payments/1").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "5")
                .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE").jsonPath("$.service").isEqualTo("payments");
    }

    @Test
    void failingUpstreamOpensTheGatewayCircuit() {
        customersStatus = 500;
        for (int i = 0; i < 4; i++) {
            as("cb-" + i).get().uri("/api/customers/1").exchange(); // 500 del servicio: el filtro lo cuenta como falla
        }
        // con el circuito abierto responde el fallback sin llamar al servicio
        int callsBefore = customersA.getRequestCount() + customersB.getRequestCount();
        as("cb-x").get().uri("/api/customers/1").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(customersA.getRequestCount() + customersB.getRequestCount()).isEqualTo(callsBefore);
        assertThat(breakers.circuitBreaker("customers").getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
    }

    // ------------------------------------------------------------------ trazabilidad
    @Test
    void theTraceContextIsPropagatedDownstreamAndReturnedToTheClient() throws Exception {
        List<String> traceIds = new ArrayList<>();
        as("tracer").get().uri("/api/accounts/trace").exchange().expectStatus().isOk()
                .expectHeader().value("X-Trace-Id", traceIds::add);
        RecordedRequest r = accounts.takeRequest(2, TimeUnit.SECONDS);
        while (r != null && !r.getPath().contains("trace")) {
            r = accounts.takeRequest(2, TimeUnit.SECONDS);
        }
        assertThat(traceIds).singleElement().asString().matches("[0-9a-f]{16,32}");
        assertThat(r).isNotNull();
        assertThat(r.getHeader("traceparent")).as("W3C traceparent hacia el servicio").contains(traceIds.get(0));
    }

    @Autowired
    ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Test
    void usesResilience4jAsTheCircuitBreakerImplementation() {
        assertThat(circuitBreakerFactory.getClass().getSimpleName()).contains("Resilience4J");
    }
}

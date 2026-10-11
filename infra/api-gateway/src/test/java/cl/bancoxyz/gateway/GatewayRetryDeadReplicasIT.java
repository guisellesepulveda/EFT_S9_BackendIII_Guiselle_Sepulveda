package cl.bancoxyz.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * Dos de tres réplicas de customers-service ya no escuchan (contenedores muertos que Eureka aún no expulsó): las LECTURAS deben
 * llegar igualmente a la réplica viva (el balanceador solo excluye la instancia del intento anterior, por eso el GET reintenta hasta 5 veces).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:../config-repo/api-gateway.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "server.ssl.enabled=false", "management.server.port=", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD,
        "gateway.rate-limit.replenish-rate=1000", "gateway.rate-limit.burst-capacity=1000"})
@AutoConfigureWebTestClient(timeout = "15000")
class GatewayRetryDeadReplicasIT {

    static final MockWebServer alive = new MockWebServer();
    static int deadPort;
    static int deadPort2;

    @BeforeAll
    static void start() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();            // puerto libre que se cierra enseguida: nadie escucha (conexión rechazada)
        }
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort2 = s.getLocalPort();
        }
        alive.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest r) {
                return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{\"ok\":true}");
            }
        });
        alive.start();
    }

    @AfterAll
    static void stop() throws IOException {
        alive.shutdown();
    }

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry r) {
        r.add("spring.ssl.bundle.jks.gateway-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.gateway-tls.truststore.location", TlsTestCerts::truststore);
        r.add("spring.cloud.discovery.client.simple.instances.customers-service[0].uri", () -> "http://localhost:" + deadPort);
        r.add("spring.cloud.discovery.client.simple.instances.customers-service[1].uri", () -> "http://localhost:" + deadPort2);
        r.add("spring.cloud.discovery.client.simple.instances.customers-service[2].uri", () -> "http://localhost:" + alive.getPort());
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.security.oauth2.jwt.ReactiveJwtDecoder jwtDecoder;
    @Autowired
    WebTestClient web;

    @Test
    void readsSucceedWhenTwoOfThreeReplicasAreDead() {
        WebTestClient as = web.mutateWith(mockJwt().jwt(j -> j.subject("ana").claim("scope", "web:read web:write")));
        int ok = 0;
        for (int i = 0; i < 12; i++) {           // el round-robin pasa por la réplica muerta varias veces
            int get = as.get().uri("/api/customers/1").exchange().returnResult(String.class).getStatus().value();
            if (get == 200) {
                ok++;
            }
        }
        assertThat(ok).as("lecturas atendidas con 2 de 3 réplicas muertas").isEqualTo(12);
    }
}

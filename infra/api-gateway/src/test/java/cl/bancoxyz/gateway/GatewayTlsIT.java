package cl.bancoxyz.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** El gateway sirve SOLO HTTPS (certificado de la CA del banco); la gestión queda en otro puerto HTTP interno. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:../config-repo/api-gateway.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "management.server.port=0", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD})
class GatewayTlsIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.ssl.bundle.jks.gateway-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.gateway-tls.truststore.location", TlsTestCerts::truststore);
    }

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.security.oauth2.jwt.ReactiveJwtDecoder jwtDecoder;

    private HttpClient trusting() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(TlsTestCerts.DIR.resolve("truststore.p12"))) {
            trust.load(in, TlsTestCerts.TRUSTSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    @Test
    void servesHttpsWithTheBankCertificateAndStillRequiresAToken() throws Exception {
        HttpResponse<String> r = trusting().send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/customers/1")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("Strict-Transport-Security")).isPresent();
    }

    @Test
    void aClientThatDoesNotTrustTheBankCaCannotConnect() {
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/customers/1")).build(),
                HttpResponse.BodyHandlers.ofString())).hasMessageContaining("PKIX");
    }

    @Test
    void plainHttpOnThePublicPortIsNotServed() {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);
        } catch (IOException | InterruptedException expected) {
            assertThat(expected).isInstanceOf(IOException.class);
        }
    }

    @Test
    void healthLivesOnlyOnTheInternalManagementPort() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        // por el puerto público HTTPS no hay actuator
        HttpResponse<String> pub = trusting().send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(pub.statusCode()).isIn(401, 404);
    }
}

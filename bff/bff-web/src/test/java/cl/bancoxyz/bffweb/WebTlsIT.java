package cl.bancoxyz.bffweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** El BFF web atiende SOLO por HTTPS (certificado firmado por la CA de desarrollo). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:../../infra/config-repo/bff-web.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "WEB_CLIENT_SECRET=web-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD, "management.server.port=0"})
class WebTlsIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @LocalServerPort
    int port;

    private static SSLContext trustingTheDevCa() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(TlsTestCerts.DIR.resolve("truststore.p12").toFile())) {
            trust.load(in, TlsTestCerts.TRUSTSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    @Test
    void servesHttpsWithACertificateSignedByTheDevCa() throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(trustingTheDevCa()).build();
        HttpResponse<String> docs = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/v3/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/web/dashboard");

        HttpResponse<String> api = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/web/dashboard")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(api.statusCode()).isEqualTo(401);
    }

    @Test
    void aClientThatDoesNotTrustTheCaRejectsTheConnection() {
        HttpClient plain = HttpClient.newHttpClient();
        assertThatThrownBy(() -> plain.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/v3/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString())).hasMessageContaining("PKIX");
    }

    @Test
    void plainHttpOnTheSamePortIsNotServed() {
        HttpClient plain = HttpClient.newHttpClient();
        try {
            HttpResponse<String> r = plain.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);        // Tomcat responde 400 a HTTP en un puerto TLS
        } catch (Exception e) {
            assertThat(e).isInstanceOf(java.io.IOException.class);        // o corta la conexión
        }
    }
}

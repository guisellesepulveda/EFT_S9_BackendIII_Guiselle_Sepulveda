package cl.bancoxyz.bffatm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Instant;
import java.util.UUID;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cl.bancoxyz.bffatm.domain.Repositories;

/**
 * mTLS REAL (sin MockMvc): el servidor exige certificado de cliente firmado por la CA del banco.
 * Cajero legítimo → pasa el handshake y opera; sin certificado o con un certificado de otra CA → el handshake falla.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:../../infra/config-repo/bff-atm.yml", "management.server.port=0",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "platform.outbox.enabled=false", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD})
class AtmMtlsIT {

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
        r.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @LocalServerPort
    int port;
    @MockitoBean
    JwtDecoder decoder;
    @Autowired
    Repositories repo;
    @Autowired
    PinService pins;

    private HttpClient client(String clientP12) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(TlsTestCerts.DIR.resolve("truststore.p12"))) {
            trust.load(in, TlsTestCerts.TRUSTSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        javax.net.ssl.KeyManager[] km = null;
        if (clientP12 != null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(TlsTestCerts.DIR.resolve(clientP12))) {
                ks.load(in, TlsTestCerts.KEYSTORE_PASSWORD.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, TlsTestCerts.KEYSTORE_PASSWORD.toCharArray());
            km = kmf.getKeyManagers();
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(km, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    private HttpRequest balanceRequest(String card) {
        when(decoder.decode("atm-token")).thenReturn(Jwt.withTokenValue("atm-token").header("alg", "none").subject("atm-client")
                .claim("scope", "atm:operate").claim("channel", "atm").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        return HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/atm/balance")).header("Authorization", "Bearer atm-token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"cardId\":\"" + card + "\",\"pin\":\"1234\"}")).build();
    }

    private String newCard() {
        String id = "40006666" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000));
        repo.insertCard(id, UUID.randomUUID(), pins.hash("1234"));
        return id;
    }

    @Test
    void aLegitimateAtmCompletesTheHandshakeAndIsIdentifiedByItsCertificate() throws Exception {
        HttpResponse<String> r = client("atm-0001.p12").send(balanceRequest(newCard()), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"account\":\"****0001\"");
        assertThat(r.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void aClientWithoutACertificateCannotEvenCompleteTheHandshake() throws Exception {
        String card = newCard();
        assertThatThrownBy(() -> client(null).send(balanceRequest(card), HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aCertificateSignedByAnotherCaIsRejectedAtTheHandshake() throws Exception {
        String card = newCard();
        assertThatThrownBy(() -> client("rogue-atm.p12").send(balanceRequest(card), HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void plainHttpIsNeverServed() throws Exception {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/atm/balance")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);
        } catch (IOException expected) {
            assertThat(expected).isInstanceOf(IOException.class);
        }
    }
}

package cl.bancoxyz.bffmobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Transporte REAL del canal móvil: HTTPS, compresión gzip, ETag (304) y Cache-Control. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:../../infra/config-repo/bff-mobile.yml", "management.server.port=0",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "MOBILE_CLIENT_SECRET=mobile-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD, "server.compression.min-response-size=128",
        "bff.mobile.rate-limit.burst=1000", "bff.mobile.rate-limit.requests-per-minute=6000"})
class MobileTransportIT {

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

    @LocalServerPort
    int port;
    @MockitoBean
    JwtDecoder decoder;

    private static int seq = 0;

    @BeforeEach
    void setUp() {
        fake.reset();
        String user = "ana-" + (++seq);                    // "mi cliente" se cachea por usuario: uno distinto por test
        FakeBackends.Cust c = fake.customer(user);
        for (int i = 1; i <= 6; i++) {
            fake.account(c.id(), "CL40000000" + i, "OPEN", "1000.50");
        }
        when(decoder.decode("good")).thenReturn(Jwt.withTokenValue("good").header("alg", "none").subject(user)
                .claim("scope", "mobile:read mobile:transfer").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }

    private HttpClient client() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(TlsTestCerts.DIR.resolve("truststore.p12").toFile())) {
            trust.load(in, TlsTestCerts.TRUSTSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("https://localhost:" + port + path)).header("Authorization", "Bearer good");
    }

    @Test
    void responsesAreGzippedOverHttps() throws Exception {
        HttpResponse<byte[]> plain = client().send(req("/mobile/accounts").build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> gz = client().send(req("/mobile/accounts").header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(gz.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(gz.body().length).isLessThan(plain.body().length);
        assertThat(new String(new GZIPInputStream(new ByteArrayInputStream(gz.body())).readAllBytes())).isEqualTo(new String(plain.body()));
    }

    @Test
    void conditionalGetsReturn304WithoutABodyAndReadsAreCacheablePrivately() throws Exception {
        HttpResponse<String> first = client().send(req("/mobile/accounts").build(), HttpResponse.BodyHandlers.ofString());
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(first.headers().firstValue("Cache-Control")).contains("private, max-age=10");

        HttpResponse<String> again = client().send(req("/mobile/accounts").header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
        // si el dato cambia, el ETag cambia y se entrega el cuerpo nuevo
        fake.accounts.values().stream().findFirst().ifPresent(a -> fake.accounts.put(a.id(), new FakeBackends.Acc(a.id(), a.customerId(), a.number(), a.type(), a.status(), "5.00")));
        HttpResponse<String> changed = client().send(req("/mobile/accounts").header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(changed.statusCode()).isEqualTo(200);
    }

    @Test
    void onlyHttpsIsServedAndAnUntrustingClientFails() throws Exception {
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(req("/mobile/accounts").build(), HttpResponse.BodyHandlers.ofString()))
                .hasMessageContaining("PKIX");
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mobile/accounts")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);
        } catch (IOException expected) {
            assertThat(expected).isInstanceOf(IOException.class);
        }
    }

    @Test
    void securityHeadersTravelWithEveryResponse() throws Exception {
        HttpResponse<String> r = client().send(req("/mobile/accounts").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(r.headers().allValues("Set-Cookie")).isEmpty();      // API sin estado: ninguna cookie
        assertThat(List.of(r.headers().firstValue("X-Instance-Id").orElse(""))).allMatch(s -> s.startsWith("bff-mobile"));
    }
}

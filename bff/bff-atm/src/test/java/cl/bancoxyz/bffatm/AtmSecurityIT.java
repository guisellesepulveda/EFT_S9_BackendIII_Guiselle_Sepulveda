package cl.bancoxyz.bffatm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cl.bancoxyz.bffatm.domain.Repositories;

/**
 * SEGURIDAD POR CANAL (evidencia clave): un token válido de OTRO canal (web, móvil) o sin el scope atm:operate es rechazado con 403,
 * y el intento queda como alerta en security.alerts. También: sin certificado de cliente, cajero revocado o certificado ajeno.
 */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-atm.yml", "management.server.port=0",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "platform.outbox.enabled=false", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD, "bff.atm.revoked-atms=ATM-0666"})
@AutoConfigureMockMvc
class AtmSecurityIT {

    static final FakeBackends fake = new FakeBackends();
    static final X509Certificate ATM_CERT = TlsTestCerts.clientCert("atm-0001.p12");
    static final X509Certificate ROGUE_CERT = TlsTestCerts.clientCert("rogue-atm.p12");

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

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Repositories repo;
    @Autowired
    PinService pins;

    private static RequestPostProcessor cert(X509Certificate c) {
        return request -> {
            request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {c});
            return request;
        };
    }

    private static RequestPostProcessor token(String channel, String... scopes) {
        return jwt().jwt(j -> j.subject(channel + "-user").tokenValue("t").claim("channel", channel))
                .authorities(java.util.Arrays.stream(scopes).<org.springframework.security.core.GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    private String card() {
        String id = "40008888" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000));
        repo.insertCard(id, UUID.randomUUID(), pins.hash("1234"));
        return id;
    }

    private String body(String card) {
        return "{\"cardId\":\"" + card + "\",\"pin\":\"1234\"}";
    }

    private int crossChannelAlerts() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE topic = 'security.alerts' AND payload LIKE '%CROSS_CHANNEL_TOKEN_REJECTED%'", Integer.class);
    }

    @Test
    void aMobileTokenAgainstTheAtmBffIsRejectedWith403AndRaisesAnAlert() throws Exception {
        String card = card();
        int before = crossChannelAlerts();
        mvc.perform(post("/atm/balance").with(cert(ATM_CERT)).with(token("mobile", "mobile:read", "mobile:transfer"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(card)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHANNEL_NOT_ALLOWED"));
        assertThat(crossChannelAlerts()).isEqualTo(before + 1);
        assertThat(fake.seen).isEmpty();                              // ni siquiera se llegó a los servicios
    }

    @Test
    void aWebTokenAndAnInternalTokenCannotOperateTheAtmEither() throws Exception {
        String card = card();
        mvc.perform(post("/atm/balance").with(cert(ATM_CERT)).with(token("web", "web:read", "web:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body(card))).andExpect(status().isForbidden());
        mvc.perform(post("/atm/withdrawals").with(cert(ATM_CERT)).with(token("internal", "internal")).header("Idempotency-Key", "abcdefgh-12345")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + card + "\",\"pin\":\"1234\",\"amount\":10000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAtmTokenCannotUseTheInternalCardIssuer() throws Exception {
        mvc.perform(post("/atm/internal/cards").with(cert(ATM_CERT)).with(token("atm", "atm:operate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"40007777000000001\",\"accountId\":\"" + UUID.randomUUID() + "\",\"pin\":\"1234\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutATokenTheAnswerIs401() throws Exception {
        mvc.perform(post("/atm/balance").with(cert(ATM_CERT)).contentType(MediaType.APPLICATION_JSON).content(body(card())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutAClientCertificateNothingIsServedEvenWithAValidToken() throws Exception {
        mvc.perform(post("/atm/balance").with(token("atm", "atm:operate")).contentType(MediaType.APPLICATION_JSON).content(body(card())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CLIENT_CERT_REQUIRED"));
        assertThat(fake.seen).isEmpty();
    }

    @Test
    void aCertificateWhoseSubjectIsNotAnAtmIsRefused() throws Exception {
        mvc.perform(post("/atm/balance").with(cert(ROGUE_CERT)).with(token("atm", "atm:operate")).contentType(MediaType.APPLICATION_JSON).content(body(card())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATM_NOT_AUTHORIZED"));
    }

    @Test
    void operationalEndpointsStayReachableWithoutCertificateOrToken() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().is2xxSuccessful());
    }

    @Test
    void anythingOutsideTheAtmApiIsDenied() throws Exception {
        mvc.perform(get("/web/accounts").with(cert(ATM_CERT)).with(token("atm", "atm:operate"))).andExpect(status().isForbidden());
    }
}

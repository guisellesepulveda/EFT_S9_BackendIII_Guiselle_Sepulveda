package cl.bancoxyz.bffatm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.cert.X509Certificate;

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

/** Un cajero en la lista de revocación (ATM-0001 aquí) queda fuera aunque su certificado siga siendo válido. */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-atm.yml", "management.server.port=0",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "platform.outbox.enabled=false", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD, "bff.atm.revoked-atms=ATM-0001"})
@AutoConfigureMockMvc
class AtmRevokedCertIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("clients.accounts.url", () -> "http://localhost:9");
        r.add("clients.customers.url", () -> "http://localhost:9");
        r.add("clients.payments.url", () -> "http://localhost:9");
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

    @Test
    void aRevokedAtmIsRefusedAndAHighAlertIsRaised() throws Exception {
        X509Certificate cert = TlsTestCerts.clientCert("atm-0001.p12");
        mvc.perform(post("/atm/balance").with(request -> {
                    request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {cert});
                    return request;
                }).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_atm:operate")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"4000123412340001\",\"pin\":\"1234\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATM_REVOKED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE payload LIKE '%ATM_REVOKED%' AND payload LIKE '%HIGH%'", Integer.class)).isPositive();
    }
}

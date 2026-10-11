package cl.bancoxyz.bffweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contexto PROPIO (propiedad extra): el post-procesador csrf() de Spring Security Test deja el repositorio CSRF en "modo
 * prueba" para todo el contexto compartido, lo que ocultaría la cookie real. Aquí no se usa csrf().
 */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-web.yml", "test.isolated-context=csrf-cookie",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "WEB_CLIENT_SECRET=web-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD})
@AutoConfigureMockMvc
class WebCsrfCookieIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theCsrfCookieIsReadableByTheSpaAndAccompaniesResponses() throws Exception {
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                Instant.now().plusSeconds(600), Set.of("web:read"));
        var response = mvc.perform(get("/web/me")
                .with(oauth2Login().attributes(a -> a.put("sub", "ana")).authorities(new SimpleGrantedAuthority("SCOPE_web:read")))
                .with(oauth2Client("web-client").accessToken(token))).andReturn().getResponse();

        String setCookie = response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElse(null);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).doesNotContain("HttpOnly");   // la SPA debe poder leerla para enviarla en X-XSRF-TOKEN
        assertThat(setCookie).contains("Path=/");
    }
}

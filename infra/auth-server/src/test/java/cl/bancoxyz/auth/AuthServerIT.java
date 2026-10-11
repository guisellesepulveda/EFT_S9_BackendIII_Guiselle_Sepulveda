package cl.bancoxyz.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.platform.events.payload.SecurityAlert;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "WEB_CLIENT_SECRET=web-secret", "MOBILE_CLIENT_SECRET=mobile-secret", "ATM_CLIENT_SECRET=atm-secret", "INTERNAL_SERVICE_CLIENT_SECRET=internal-secret",
        "DEMO_USER_PASSWORD=customer-pass", "ADMIN_PASSWORD=admin-pass", "BATCH_ADMIN_PASSWORD=batch-pass"})
@AutoConfigureMockMvc
class AuthServerIT {

    private static final String MOBILE_REDIRECT = "bancoxyz://callback";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    SecurityAlertPublisher alerts;

    // ------------------------------------------------------------------ helpers
    private JsonNode clientCredentials(String client, String secret, String scope) throws Exception {
        MvcResult r = mvc.perform(post("/oauth2/token").with(httpBasic(client, secret))
                .param("grant_type", "client_credentials").param("scope", scope)).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private static JsonNode claims(ObjectMapper mapper, String jwt) throws Exception {
        return mapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private static String challenge(String verifier) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    }

    /** SAS lee los parámetros de la query string (no de los parámetros del request), por eso se arma la URI completa. */
    private static java.net.URI authorizeUri(String clientId, String scope, String redirect, String challenge) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/oauth2/authorize").queryParam("response_type", "code")
                .queryParam("client_id", clientId).queryParam("scope", scope).queryParam("redirect_uri", redirect)
                .queryParam("state", "xyz");
        if (challenge != null) {
            b.queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
        }
        return b.build().encode().toUri();
    }

    private String authorizationCode(String clientId, String scope, String redirect, String challenge, String username,
                                     String role) throws Exception {
        MvcResult r = mvc.perform(get(authorizeUri(clientId, scope, redirect, challenge)).with(user(username).roles(role))).andReturn();
        assertThat(r.getResponse().getStatus()).as("authorize → %s %s", r.getResponse().getContentAsString(),
                r.getResponse().getErrorMessage()).isEqualTo(302);
        String location = r.getResponse().getHeader("Location");
        assertThat(location).startsWith(redirect);
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
    }

    // ------------------------------------------------------------------ canales: client_credentials
    @Test
    void internalServiceGetsAnInternalScopedToken() throws Exception {
        JsonNode token = clientCredentials("internal-service", "internal-secret", "internal");
        assertThat(token.get("token_type").asText()).isEqualTo("Bearer");
        JsonNode c = claims(mapper, token.get("access_token").asText());
        assertThat(c.get("scope").toString()).contains("internal");
        assertThat(c.get("channel").asText()).isEqualTo("internal");
        assertThat(token.get("expires_in").asLong()).isLessThanOrEqualTo(300);
    }

    @Test
    void atmClientOnlyGetsAtmOperate() throws Exception {
        JsonNode ok = clientCredentials("atm-client", "atm-secret", "atm:operate");
        assertThat(claims(mapper, ok.get("access_token").asText()).get("scope").toString()).contains("atm:operate");
        assertThat(claims(mapper, ok.get("access_token").asText()).get("channel").asText()).isEqualTo("atm");

        // un cajero NO puede pedir scopes de otros canales
        assertThat(clientCredentials("atm-client", "atm-secret", "web:read").get("error").asText()).isEqualTo("invalid_scope");
        assertThat(clientCredentials("atm-client", "atm-secret", "internal").get("error").asText()).isEqualTo("invalid_scope");
    }

    @Test
    void webAndMobileClientsCannotUseClientCredentials() throws Exception {
        assertThat(clientCredentials("web-client", "web-secret", "web:read").get("error").asText()).isEqualTo("unauthorized_client");
        assertThat(clientCredentials("mobile-client", "mobile-secret", "mobile:read").get("error").asText()).isEqualTo("unauthorized_client");
    }

    @Test
    void wrongClientSecretIsRejected() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("internal-service", "mala-clave"))
                .param("grant_type", "client_credentials").param("scope", "internal"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
    }

    // ------------------------------------------------------------------ mobile: PKCE + refresh
    @Test
    void mobileAuthorizationCodeWithPkceReturnsShortAccessTokenAndRefreshToken() throws Exception {
        String verifier = "v".repeat(64);
        String code = authorizationCode("mobile-client", "openid mobile:read mobile:transfer", MOBILE_REDIRECT,
                challenge(verifier), "customer1", "CUSTOMER");

        MvcResult res = mvc.perform(post("/oauth2/token").with(httpBasic("mobile-client", "mobile-secret"))
                .param("grant_type", "authorization_code").param("code", code).param("redirect_uri", MOBILE_REDIRECT)
                .param("code_verifier", verifier)).andExpect(status().isOk()).andReturn();
        JsonNode token = mapper.readTree(res.getResponse().getContentAsString());

        assertThat(token.get("refresh_token").asText()).isNotBlank();
        assertThat(token.get("id_token").asText()).isNotBlank();
        assertThat(token.get("expires_in").asLong()).isLessThanOrEqualTo(300); // access token corto
        JsonNode c = claims(mapper, token.get("access_token").asText());
        assertThat(c.get("scope").toString()).contains("mobile:read").contains("mobile:transfer");
        assertThat(c.get("channel").asText()).isEqualTo("mobile");
        assertThat(c.get("roles").toString()).contains("CUSTOMER");
        assertThat(c.get("sub").asText()).isEqualTo("customer1");

        // refresh token rotado: cada refresh entrega uno nuevo
        MvcResult refreshed = mvc.perform(post("/oauth2/token").with(httpBasic("mobile-client", "mobile-secret"))
                .param("grant_type", "refresh_token").param("refresh_token", token.get("refresh_token").asText()))
                .andExpect(status().isOk()).andReturn();
        JsonNode next = mapper.readTree(refreshed.getResponse().getContentAsString());
        assertThat(next.get("refresh_token").asText()).isNotEqualTo(token.get("refresh_token").asText());
    }

    @Test
    void pkceIsMandatoryAndTheVerifierMustMatch() throws Exception {
        // sin code_challenge el cliente público no obtiene código
        MvcResult noPkce = mvc.perform(get(authorizeUri("mobile-client", "mobile:read", MOBILE_REDIRECT, null))
                .with(user("customer1").roles("CUSTOMER"))).andReturn();
        assertThat(noPkce.getResponse().getHeader("Location")).contains("error=invalid_request");

        String code = authorizationCode("mobile-client", "mobile:read", MOBILE_REDIRECT, challenge("a".repeat(64)),
                "customer1", "CUSTOMER");
        mvc.perform(post("/oauth2/token").with(httpBasic("mobile-client", "mobile-secret"))
                .param("grant_type", "authorization_code").param("code", code).param("redirect_uri", MOBILE_REDIRECT)
                .param("code_verifier", "b".repeat(64)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void mobileCannotRequestAnotherChannelsScope() throws Exception {
        MvcResult r = mvc.perform(get(authorizeUri("mobile-client", "web:write atm:operate", MOBILE_REDIRECT,
                challenge("c".repeat(64)))).with(user("customer1").roles("CUSTOMER"))).andReturn();
        assertThat(r.getResponse().getHeader("Location")).contains("error=invalid_scope");
    }

    // ------------------------------------------------------------------ web: confidencial + roles
    @Test
    void webClientIssuesAdminRolesInTheTokenAndRequiresItsSecret() throws Exception {
        String verifier = "w".repeat(64);
        String redirect = "https://localhost:8443/login/oauth2/code/web-client";
        String code = authorizationCode("web-client", "openid web:read web:write", redirect, challenge(verifier), "admin", "ADMIN");

        // cliente confidencial: sin secreto no hay token
        mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "web-client")
                .param("code", code).param("redirect_uri", redirect).param("code_verifier", verifier))
                .andExpect(status().isUnauthorized());

        String code2 = authorizationCode("web-client", "openid web:read web:write", redirect, challenge(verifier), "admin", "ADMIN");
        MvcResult res = mvc.perform(post("/oauth2/token").with(httpBasic("web-client", "web-secret"))
                .param("grant_type", "authorization_code").param("code", code2).param("redirect_uri", redirect)
                .param("code_verifier", verifier)).andExpect(status().isOk()).andReturn();
        JsonNode c = claims(mapper, mapper.readTree(res.getResponse().getContentAsString()).get("access_token").asText());
        assertThat(c.get("roles").toString()).contains("ADMIN");
        assertThat(c.get("channel").asText()).isEqualTo("web");
    }

    // ------------------------------------------------------------------ metadata y alertas
    @Test
    void publishesJwksAndOidcDiscovery() throws Exception {
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].kty").value("RSA"));
        mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.token_endpoint").exists()).andExpect(jsonPath("$.jwks_uri").exists());
    }

    @Test
    void failedUserLoginEmitsASecurityAlert() throws Exception {
        mvc.perform(post("/login").with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "customer1").param("password", "incorrecta"));
        verify(alerts, timeout(2000)).publish(argThat((SecurityAlert a) -> a.source().equals("auth-server")
                && a.subject().equals("customer1") && a.alertType().equals("AUTHENTICATION_FAILED")));
    }

    @Test
    void repeatedFailuresEscalateToHighSeverity() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("username", "admin").param("password", "mala-" + i));
        }
        verify(alerts, timeout(2000)).publish(argThat((SecurityAlert a) -> a.subject().equals("admin")
                && a.severity().equals("HIGH") && a.alertType().equals("BRUTE_FORCE_SUSPECTED")));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

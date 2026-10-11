package cl.bancoxyz.bffweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.server.Session.Cookie;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import cl.bancoxyz.bffweb.security.JwtOAuth2UserService;

/** Seguridad del canal web: sesión/cookies, CSRF, CORS, cabeceras, PKCE y rechazo de tokens de otros canales. */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-web.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "WEB_CLIENT_SECRET=web-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD, "AUTH_PUBLIC_URL=http://auth.example:9000",
        "bff.web.allowed-origins=https://localhost:3000"})
@AutoConfigureMockMvc
class WebSecurityIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ServerProperties server;

    private static RequestPostProcessor loginWith(String... scopes) {
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                Instant.now().plusSeconds(600), Set.of(scopes));
        return request -> {
            request = oauth2Login().attributes(a -> a.put("sub", "intruso"))
                    .authorities(java.util.Arrays.stream(scopes).map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toArray(SimpleGrantedAuthority[]::new))
                    .postProcessRequest(request);
            return oauth2Client("web-client").accessToken(token).postProcessRequest(request);
        };
    }

    // ------------------------------------------------------------------ autenticación
    @Test
    void anonymousApiCallsGet401JsonNotARedirect() throws Exception {
        mvc.perform(get("/web/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/web/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/web/transfers").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void everythingOutsideTheApiIsDeniedByDefault() throws Exception {
        mvc.perform(get("/admin").with(loginWith("web:read", "web:write"))).andExpect(status().isForbidden());
        mvc.perform(get("/web-secreto").with(loginWith("web:read", "web:write"))).andExpect(status().isForbidden());
    }

    @Test
    void loginUsesAuthorizationCodeWithPkceAgainstTheAuthServer() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/web-client")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("http://auth.example:9000/oauth2/authorize?")   // URL pública (la abre el navegador)
                .contains("response_type=code").contains("client_id=web-client")
                .contains("code_challenge_method=S256").contains("code_challenge=")
                .contains("scope=web:read%20web:write").contains("state=")
                .contains("redirect_uri=").contains("/login/oauth2/code/web-client");
    }

    // ------------------------------------------------------------------ rechazo de tokens de otros canales
    @Test
    void aSessionWithOtherChannelsScopesCannotUseTheWebApi() throws Exception {
        mvc.perform(get("/web/dashboard").with(loginWith("mobile:read", "mobile:transfer"))).andExpect(status().isForbidden());
        mvc.perform(get("/web/accounts").with(loginWith("atm:operate"))).andExpect(status().isForbidden());
        mvc.perform(get("/web/me").with(loginWith("internal"))).andExpect(status().isForbidden());
    }

    @Test
    void readOnlyScopeCannotTransfer() throws Exception {
        mvc.perform(post("/web/transfers").with(loginWith("web:read")).with(csrf()).header("Idempotency-Key", "abc12345")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenFromAnotherChannelNeverOpensAWebSession() {
        JwtOAuth2UserService service = new JwtOAuth2UserService();
        assertThat(service.loadUser(request(jwt("web", List.of("CUSTOMER")), "web:read", "web:write")).getAuthorities())
                .extracting(Object::toString).contains("SCOPE_web:read", "SCOPE_web:write", "ROLE_CUSTOMER");
        for (String channel : new String[] {"mobile", "atm", "internal"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.loadUser(request(jwt(channel, List.of()), "mobile:read")))
                    .isInstanceOf(OAuth2AuthenticationException.class).hasMessageContaining("canal web");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.loadUser(request("no-es-un-jwt", "web:read")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private static String jwt(String channel, List<String> roles) {
        return new PlainJWT(new JWTClaimsSet.Builder().subject("customer1").claim("channel", channel).claim("roles", roles).build()).serialize();
    }

    private static OAuth2UserRequest request(String tokenValue, String... scopes) {
        ClientRegistration reg = ClientRegistration.withRegistrationId("web-client").clientId("web-client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://localhost/cb")
                .authorizationUri("http://a/authorize").tokenUri("http://a/token").build();
        return new OAuth2UserRequest(reg, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue, Instant.now(),
                Instant.now().plusSeconds(60), Set.of(scopes)));
    }

    // ------------------------------------------------------------------ CSRF, CORS y cabeceras
    @Test
    void stateChangingRequestsNeedTheCsrfToken() throws Exception {
        String body = "{\"fromAccountId\":\"" + java.util.UUID.randomUUID() + "\",\"toAccountId\":\"" + java.util.UUID.randomUUID() + "\",\"amount\":10}";
        mvc.perform(post("/web/transfers").with(loginWith("web:read", "web:write")).header("Idempotency-Key", "abc12345")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());                                   // sin X-XSRF-TOKEN
        // con CSRF válido la petición avanza hasta la lógica de negocio (aquí customers-service no existe → 503 con código)
        mvc.perform(post("/web/transfers").with(loginWith("web:read", "web:write")).with(csrf()).header("Idempotency-Key", "abc12345")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CUSTOMERS_UNAVAILABLE"));
    }

    @Test
    void corsOnlyAllowsTheConfiguredOriginWithCredentials() throws Exception {
        mvc.perform(options("/web/dashboard").header("Origin", "https://localhost:3000").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/web/dashboard").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/web/dashboard").header("Origin", "https://localhost:3000").header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void securityHeadersAreSent() throws Exception {
        mvc.perform(get("/web/me").secure(true).with(loginWith("web:read")))
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void sessionCookieIsHttpOnlySecureAndSameSite() {
        Cookie cookie = server.getServlet().getSession().getCookie();
        assertThat(cookie.getName()).isEqualTo("BFFWEB_SESSION");
        assertThat(cookie.getHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo(Cookie.SameSite.LAX);
    }

    @Test
    void logoutEndsTheSessionWithNoContent() throws Exception {
        mvc.perform(post("/web/logout").with(loginWith("web:read")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/web/logout").with(loginWith("web:read"))).andExpect(status().isForbidden()); // logout también exige CSRF
    }

    @Test
    void meNeverExposesTokens() throws Exception {
        String json = mvc.perform(get("/web/me").with(loginWith("web:read", "web:write"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("intruso")).andExpect(jsonPath("$.scopes[0]").value("web:read"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("access_token").doesNotContain("refresh_token").doesNotContain("Bearer");
    }
}

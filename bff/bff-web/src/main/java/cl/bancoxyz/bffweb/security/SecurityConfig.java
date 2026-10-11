package cl.bancoxyz.bffweb.security;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Patrón BFF para el navegador: los tokens viven SOLO en el servidor (en la sesión); el navegador recibe una cookie de
 * sesión HttpOnly + Secure + SameSite y nunca ve un access/refresh token.
 * <ul>
 *   <li>Login: OAuth2 Authorization Code + PKCE contra el auth-server (cliente web-client).</li>
 *   <li>CSRF: token en cookie XSRF-TOKEN (legible por la SPA) que debe volver en la cabecera X-XSRF-TOKEN.</li>
 *   <li>CORS: solo los orígenes configurados, con credenciales.</li>
 *   <li>Sin sesión, /web/** responde 401 JSON (no redirige): lo consume una SPA, no un navegador navegando.</li>
 * </ul>
 */
@Configuration
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity   // @PreAuthorize por scope: no depende de la librería común
class SecurityConfig {

    @Bean
    SecurityFilterChain webChain(HttpSecurity http, ClientRegistrationRepository clients,
                                 @Value("${bff.web.post-login-url:/web/me}") String postLoginUrl) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // token legible tal cual desde la cookie (patrón SPA)

        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/oauth2/**", "/login/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Autorización por scope del canal ANTES de validar el cuerpo (además de @PreAuthorize en cada método)
                        .requestMatchers(HttpMethod.GET, "/web/**").hasAuthority("SCOPE_web:read")
                        .requestMatchers(HttpMethod.POST, "/web/logout").authenticated()
                        .requestMatchers(HttpMethod.POST, "/web/**").hasAuthority("SCOPE_web:write")
                        .anyRequest().denyAll())
                .oauth2Login(o -> o
                        .authorizationEndpoint(e -> e.authorizationRequestResolver(pkceResolver(clients)))
                        .userInfoEndpoint(u -> u.userService(new JwtOAuth2UserService()))
                        .defaultSuccessUrl(postLoginUrl, true))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/web/**")))
                .logout(l -> l.logoutRequestMatcher(new AntPathRequestMatcher("/web/logout", "POST"))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("BFFWEB_SESSION", "XSRF-TOKEN"))
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(csrfHandler))
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
                .cors(Customizer.withDefaults())
                .headers(h -> h
                        .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(cspWriter())
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .build();
    }

    /** Spring solo activa PKCE por defecto en clientes públicos; el auth-server lo exige también para web-client. */
    private static OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository clients) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        Consumer<OAuth2AuthorizationRequest.Builder> pkce =
                org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers.withPkce();
        resolver.setAuthorizationRequestCustomizer(pkce);
        return resolver;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${bff.web.allowed-origins:https://localhost:3000}") List<String> origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins);                                   // lista cerrada: nunca "*" con credenciales
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
        cfg.setExposedHeaders(List.of("X-Trace-Id", "Idempotent-Replayed"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/web/**", cfg);
        return source;
    }
    /**
     * CSP estricta (default-src 'none') para TODA respuesta de la API; solo la documentación (Swagger UI) necesita cargar sus propios
     * scripts y estilos, por lo que ahí se permite únicamente 'self'.
     */
    static org.springframework.security.web.header.HeaderWriter cspWriter() {
        org.springframework.security.web.util.matcher.RequestMatcher docs = new org.springframework.security.web.util.matcher.OrRequestMatcher(
                new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/swagger-ui/**"),
                new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/swagger-ui.html"));
        org.springframework.security.web.header.HeaderWriter api = new org.springframework.security.web.header.writers.StaticHeadersWriter(
                "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        org.springframework.security.web.header.HeaderWriter ui = new org.springframework.security.web.header.writers.StaticHeadersWriter(
                "Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
        return (request, response) -> (docs.matches(request) ? ui : api).writeHeaders(request, response);
    }
}

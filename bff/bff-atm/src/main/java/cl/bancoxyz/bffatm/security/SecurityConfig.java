package cl.bancoxyz.bffatm.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import cl.bancoxyz.bffatm.AlertService;
import cl.bancoxyz.bffatm.AtmProperties;
import cl.bancoxyz.platform.security.PlatformJwtConverter;

/**
 * Doble factor de máquina: (1) mTLS → identidad del cajero; (2) JWT client_credentials con scope atm:operate.
 * Un token de otro canal (web, móvil) sí es un JWT válido pero NO tiene atm:operate: se responde 403 y se
 * emite una alerta CROSS_CHANNEL_TOKEN_REJECTED a security.alerts.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain atmChain(HttpSecurity http, AtmProperties props, AlertService alerts) throws Exception {
        JwtAuthenticationConverter converter = PlatformJwtConverter.create();
        return http
                .csrf(AbstractHttpConfigurer::disable) // M2M sin cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/atm/internal/**").hasAuthority("SCOPE_internal")
                        .requestMatchers("/atm/**").hasAuthority("SCOPE_atm:operate")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .addFilterBefore(new ClientCertificateFilter(props, alerts), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(e -> e.accessDeniedHandler(crossChannelHandler(alerts)))
                .headers(h -> h
                        .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .cacheControl(c -> { })          // respuestas con datos bancarios: nunca cacheables
                        .addHeaderWriter(cspWriter()))
                .build();
    }

    private static AccessDeniedHandler crossChannelHandler(AlertService alerts) {
        return (request, response, ex) -> {
            Object atm = request.getAttribute(ClientCertificateFilter.ATM_ATTR);
            String channel = "?";
            if (request.getUserPrincipal() instanceof JwtAuthenticationToken jwt) {
                Object c = jwt.getToken().getClaims().get("channel");
                channel = c == null ? "desconocido" : c.toString();
            }
            alerts.raise("MEDIUM", "CROSS_CHANNEL_TOKEN_REJECTED", atm == null ? "atm" : atm.toString(),
                    "Token del canal '" + channel + "' rechazado en " + request.getRequestURI());
            ClientCertificateFilter.reject(response, HttpStatus.FORBIDDEN, "CHANNEL_NOT_ALLOWED",
                    "El token no pertenece al canal ATM (se requiere el scope atm:operate)");
        };
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

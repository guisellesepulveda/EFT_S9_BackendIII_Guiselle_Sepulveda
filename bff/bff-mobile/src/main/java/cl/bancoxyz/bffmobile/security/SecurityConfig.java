package cl.bancoxyz.bffmobile.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import cl.bancoxyz.bffmobile.MobileProperties;
import cl.bancoxyz.platform.security.PlatformJwtConverter;

/**
 * API móvil sin estado: la app envía el access token (JWT de 5 min) en cada llamada. Autorización por SCOPE del canal:
 * un token web, ATM o interno no tiene mobile:* y recibe 403. Los endpoints de login/refresh son públicos (con rate limit por IP).
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain mobileChain(HttpSecurity http, MobileProperties props) throws Exception {
        JwtAuthenticationConverter converter = PlatformJwtConverter.create();
        return http
                .csrf(AbstractHttpConfigurer::disable) // sin cookies de sesión: no hay CSRF que mitigar
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/mobile/auth/token", "/mobile/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/mobile/**").hasAuthority("SCOPE_mobile:read")
                        .requestMatchers(HttpMethod.POST, "/mobile/transfers").hasAuthority("SCOPE_mobile:transfer")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .addFilterAfter(new RateLimitFilter(props.rateLimit()), BearerTokenAuthenticationFilter.class)
                .headers(h -> h
                        .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(cspWriter()))
                .build();
    }

    /** ETag débil calculado sobre el cuerpo: un GET repetido con If-None-Match devuelve 304 sin cuerpo. */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        ShallowEtagHeaderFilter etag = new ShallowEtagHeaderFilter();
        etag.setWriteWeakETag(true); // un ETag FUERTE impide que Tomcat comprima la respuesta (gzip)
        FilterRegistrationBean<ShallowEtagHeaderFilter> reg = new FilterRegistrationBean<>(etag);
        reg.setUrlPatterns(List.of("/mobile/accounts", "/mobile/accounts/*", "/mobile/home"));
        reg.setName("mobileEtagFilter");
        return reg;
    }

    /** Cache-Control privado y corto en las lecturas (la respuesta es de un usuario: nunca cacheable en proxies). */
    @Bean
    FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> cacheControlFilter(
            @Value("${bff.mobile.max-age-seconds:10}") int maxAge) {
        org.springframework.web.filter.OncePerRequestFilter f = new org.springframework.web.filter.OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
                                            jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                if ("GET".equals(req.getMethod())) {
                    res.setHeader("Cache-Control", "private, max-age=" + maxAge);
                }
                chain.doFilter(req, res);
            }
        };
        FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> reg = new FilterRegistrationBean<>(f);
        reg.setUrlPatterns(List.of("/mobile/accounts", "/mobile/accounts/*", "/mobile/home"));
        reg.setName("mobileCacheControl");
        return reg;
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

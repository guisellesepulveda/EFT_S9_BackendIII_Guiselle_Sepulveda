package cl.bancoxyz.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server JWT por defecto para los microservicios: stateless, solo endpoints operativos públicos,
 * y autorización fina con @PreAuthorize (@CanRead, @CanWrite, @InternalOnly, @AdminOnly).
 * Un servicio puede reemplazarlo declarando su propio SecurityFilterChain.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtAuthenticationConverter.class})
@ConditionalOnProperty(prefix = "platform.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableMethodSecurity
public class PlatformSecurityAutoConfiguration {

    /**
     * Endpoints operativos de solo lectura. Los servicios solo son alcanzables dentro de la red interna (el API Gateway
     * no publica /actuator), así que no exponen nada al exterior; Prometheus y los estados de Resilience4j
     * (/actuator/circuitbreakers, retries, bulkheads) los consumen herramientas internas sin token.
     */
    private static final String[] PUBLIC = {"/actuator/health/**", "/actuator/info", "/actuator/prometheus",
            "/actuator/circuitbreakers/**", "/actuator/circuitbreakerevents/**", "/actuator/retries/**",
            "/actuator/retryevents/**", "/actuator/bulkheads/**", "/actuator/timelimiters/**",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter platformJwtAuthenticationConverter() {
        return PlatformJwtConverter.create();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain platformResourceServerChain(HttpSecurity http, JwtAuthenticationConverter converter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable) // API stateless con bearer token: sin cookies, sin CSRF
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLIC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }
}

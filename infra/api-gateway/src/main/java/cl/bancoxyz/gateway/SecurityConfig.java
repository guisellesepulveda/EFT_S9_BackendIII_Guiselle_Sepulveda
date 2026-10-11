package cl.bancoxyz.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * El gateway es un resource server: valida el JWT (firma, expiración) emitido por el auth-server y rechaza con 401
 * todo lo que no lo traiga. La autorización fina (scopes y roles por endpoint) la aplica cada microservicio.
 * Solo los endpoints operativos básicos son públicos.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain gatewayChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // API stateless con bearer token
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/actuator/circuitbreakers/**").permitAll()   // el puerto de gestión no es público
                        .pathMatchers("/fallback/**").permitAll() // destino interno (forward) del circuit breaker
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(org.springframework.security.config.Customizer.withDefaults()))
                .build();
    }
}

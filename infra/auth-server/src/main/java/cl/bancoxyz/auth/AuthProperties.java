package cl.bancoxyz.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** auth.*: emisor, secretos de clientes (desde variables de entorno), URIs de redirección y contraseñas de usuarios demo. */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        @DefaultValue("http://localhost:9000") String issuer,
        Clients clients,
        Users users,
        @DefaultValue Alerts alerts) {

    public record Clients(
            Client web,
            Client mobile,
            Client atm,
            Client internal) {
    }

    /** secret: viene de variables de entorno (nunca del repo). */
    public record Client(String secret, List<String> redirectUris, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Users(String customerPassword, String adminPassword, String batchAdminPassword) {
    }

    public record Alerts(@DefaultValue("5") int highSeverityThreshold, @DefaultValue("5m") Duration window) {
    }
}

package cl.bancoxyz.bffmobile;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bff.mobile")
public record MobileProperties(
        @DefaultValue Auth auth,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("10") int maxAgeSeconds) {

    /** El BFF es el cliente confidencial mobile-client: guarda el secreto, la app nunca lo ve. */
    public record Auth(
            @DefaultValue("http://localhost:9000/oauth2/token") String tokenUri,
            @DefaultValue("mobile-client") String clientId,
            String clientSecret,
            @DefaultValue({"bancoxyz://callback"}) List<String> allowedRedirectUris) {
    }

    public record RateLimit(
            @DefaultValue("120") int requestsPerMinute,
            @DefaultValue("30") int burst,
            @DefaultValue("10") int authRequestsPerMinute) {
    }
}

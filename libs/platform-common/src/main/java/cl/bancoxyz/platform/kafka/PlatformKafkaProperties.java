package cl.bancoxyz.platform.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** platform.kafka.*: reintentos de consumidores antes de enviar a la DLT. */
@ConfigurationProperties(prefix = "platform.kafka")
public record PlatformKafkaProperties(
        @DefaultValue("3") int maxRetries,
        @DefaultValue("500ms") Duration initialBackoff,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("5s") Duration maxBackoff) {
}

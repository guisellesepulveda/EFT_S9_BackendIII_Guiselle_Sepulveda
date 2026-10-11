package cl.bancoxyz.payments.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "payments")
public record PaymentsProperties(
        /** Un pago PENDING sin avance durante este tiempo se reenvía (evento transaction.requested). */
        @DefaultValue("30s") Duration pendingRetryAfter,
        @DefaultValue("5") int maxRequestAttempts,
        @DefaultValue("10s") Duration retrierInterval,
        @DefaultValue("true") boolean retrierEnabled,
        @DefaultValue("10m") Duration customerCacheTtl,
        @DefaultValue("CLP") String currency) {
}

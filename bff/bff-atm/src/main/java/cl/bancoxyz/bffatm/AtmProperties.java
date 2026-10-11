package cl.bancoxyz.bffatm;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bff.atm")
public record AtmProperties(
        /** Exige el certificado de cliente (mTLS) en /atm/**; false solo para pruebas o detrás de un balanceador que lo valida. */
        @DefaultValue("true") boolean requireClientCert,
        @DefaultValue("^ATM-[0-9]{4}$") String atmIdPattern,
        @DefaultValue List<String> revokedAtms,
        @DefaultValue Limits limits,
        @DefaultValue Pin pin,
        @DefaultValue("3s") Duration confirmationTimeout,
        @DefaultValue("150ms") Duration pollInterval) {

    public record Limits(
            @DefaultValue("1000") BigDecimal denomination,
            @DefaultValue("200000") BigDecimal maxPerWithdrawal,
            @DefaultValue("500000") BigDecimal dailyLimit) {
    }

    public record Pin(@DefaultValue("3") int maxAttempts, @DefaultValue("15m") Duration lockDuration) {
    }
}

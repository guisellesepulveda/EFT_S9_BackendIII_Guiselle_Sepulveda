package cl.bancoxyz.gateway;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

@Configuration
class RateLimitConfig {

    @Bean
    InMemoryRateLimiter inMemoryRateLimiter(ConfigurationService configurationService,
                                            @Value("${gateway.rate-limit.replenish-rate:20}") int replenishRate,
                                            @Value("${gateway.rate-limit.burst-capacity:40}") int burstCapacity) {
        return new InMemoryRateLimiter(configurationService,
                new InMemoryRateLimiter.Config().setReplenishRate(replenishRate).setBurstCapacity(burstCapacity));
    }

    /** Un límite por usuario autenticado (sub del JWT); si no hay principal, por IP de origen. */
    @Bean
    KeyResolver principalKeyResolver() {
        return exchange -> exchange.getPrincipal().map(Principal::getName).switchIfEmpty(Mono.fromSupplier(() ->
                exchange.getRequest().getRemoteAddress() == null ? "anonymous"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()));
    }
}

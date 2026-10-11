package cl.bancoxyz.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;

import reactor.core.publisher.Mono;

/**
 * Rate limiter por token bucket en memoria (reemplaza al RedisRateLimiter para no depender de Redis en la demo).
 * Cada clave (usuario o IP) tiene su cubeta: capacidad burstCapacity, se rellena a replenishRate tokens/segundo.
 * Limitación conocida: el estado es por instancia del gateway; con varias réplicas el límite efectivo se multiplica.
 * En producción se usa RedisRateLimiter (estado compartido) — ver docs/adr/ADR-004.
 */
public class InMemoryRateLimiter extends AbstractRateLimiter<InMemoryRateLimiter.Config> {

    public static final String CONFIGURATION_PROPERTY_NAME = "in-memory-rate-limiter";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String BURST_HEADER = "X-RateLimit-Burst-Capacity";
    public static final String REPLENISH_HEADER = "X-RateLimit-Replenish-Rate";

    public static class Config {
        private int replenishRate = 20;
        private int burstCapacity = 40;

        public int getReplenishRate() {
            return replenishRate;
        }

        public Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }

        synchronized double tryConsume(double replenishPerSecond, double capacity, long now) {
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 1_000_000_000.0 * replenishPerSecond);
            lastRefillNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return tokens;
            }
            return -1;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Config defaults;

    public InMemoryRateLimiter(ConfigurationService configurationService, Config defaults) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.defaults = defaults;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config config = getConfig().getOrDefault(routeId, defaults);
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(routeId + "|" + id, k -> new Bucket(config.getBurstCapacity(), now));
        double remaining = bucket.tryConsume(config.getReplenishRate(), config.getBurstCapacity(), now);
        boolean allowed = remaining >= 0;
        Map<String, String> headers = Map.of(
                REMAINING_HEADER, String.valueOf(allowed ? (long) remaining : 0),
                REPLENISH_HEADER, String.valueOf(config.getReplenishRate()),
                BURST_HEADER, String.valueOf(config.getBurstCapacity()));
        return Mono.just(new Response(allowed, headers));
    }
}

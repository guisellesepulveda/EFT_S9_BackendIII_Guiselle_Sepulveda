package cl.bancoxyz.bffmobile.security;

import java.io.IOException;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import cl.bancoxyz.bffmobile.MobileProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rate limiting POR USUARIO (sub del JWT) para la API móvil y por IP para los endpoints de login/refresh (que son anónimos).
 * Token bucket en memoria: con varias réplicas del BFF el límite efectivo se multiplica (producción: Redis).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final class Bucket {
        private double tokens;
        private long last;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.last = now;
        }

        synchronized boolean tryConsume(double perSecond, double capacity, long now) {
            tokens = Math.min(capacity, tokens + (now - last) / 1e9 * perSecond);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        synchronized long retryAfterSeconds(double perSecond) {
            return Math.max(1, (long) Math.ceil((1 - tokens) / perSecond));
        }
    }

    private final MobileProperties.RateLimit limits;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).maximumSize(100_000).build();

    public RateLimitFilter(MobileProperties.RateLimit limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mobile/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean auth = request.getRequestURI().startsWith("/mobile/auth/");
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        String key = auth ? "ip:" + request.getRemoteAddr() : (a != null && a.isAuthenticated() ? "user:" + a.getName() : "ip:" + request.getRemoteAddr());
        double perSecond = (auth ? limits.authRequestsPerMinute() : limits.requestsPerMinute()) / 60.0;
        double capacity = auth ? limits.authRequestsPerMinute() : limits.burst();
        Bucket bucket = buckets.get((auth ? "auth|" : "api|") + key, k -> new Bucket(capacity, System.nanoTime()));
        if (bucket.tryConsume(perSecond, capacity, System.nanoTime())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(bucket.retryAfterSeconds(perSecond)));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Demasiadas solicitudes\",\"status\":429,"
                + "\"detail\":\"Superó el límite de solicitudes; reintente en unos segundos\",\"code\":\"RATE_LIMITED\"}");
    }
}

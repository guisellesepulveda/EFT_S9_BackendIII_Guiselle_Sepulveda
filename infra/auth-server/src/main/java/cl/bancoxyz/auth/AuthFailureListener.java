package cl.bancoxyz.auth;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.stereotype.Component;

import cl.bancoxyz.platform.events.payload.SecurityAlert;

/**
 * Convierte cada autenticación fallida (usuario o cliente) en una alerta a security.alerts.
 * La severidad sube a HIGH cuando un mismo sujeto acumula N fallos dentro de la ventana (posible fuerza bruta).
 */
@Component
class AuthFailureListener {

    private static final Logger log = LoggerFactory.getLogger(AuthFailureListener.class);

    private final SecurityAlertPublisher publisher;
    private final AuthProperties props;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    AuthFailureListener(SecurityAlertPublisher publisher, AuthProperties props) {
        this.publisher = publisher;
        this.props = props;
    }

    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent event) {
        String subject = String.valueOf(event.getAuthentication().getName());
        int count = register(subject);
        boolean high = count >= props.alerts().highSeverityThreshold();
        log.warn("Autenticación fallida de '{}' ({} en la ventana): {}", subject, count, event.getException().getClass().getSimpleName());
        publisher.publish(new SecurityAlert("auth-server", high ? "HIGH" : "MEDIUM",
                high ? "BRUTE_FORCE_SUSPECTED" : "AUTHENTICATION_FAILED", subject,
                event.getException().getClass().getSimpleName() + " (" + count + " fallos en la ventana)", Instant.now()));
    }

    private int register(String subject) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(props.alerts().window());
        Deque<Instant> q = failures.computeIfAbsent(subject, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                q.pollFirst();
            }
            q.addLast(now);
            return q.size();
        }
    }
}

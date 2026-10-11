package cl.bancoxyz.platform.events.payload;

import java.time.Instant;

/** source: auth-server | bff-atm | batch-service ...; severity: LOW | MEDIUM | HIGH. */
public record SecurityAlert(String source, String severity, String alertType, String subject, String detail,
                            Instant occurredAt) {
}

package cl.bancoxyz.platform.events.payload;

import java.time.Instant;
import java.util.UUID;

public record AccountLifecycle(UUID accountId, UUID customerId, String accountType, Instant occurredAt) {
}

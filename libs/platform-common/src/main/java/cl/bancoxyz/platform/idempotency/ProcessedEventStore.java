package cl.bancoxyz.platform.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumidores idempotentes: registra (consumidor, eventId) en la misma transacción que el efecto de negocio.
 * Si el evento ya fue procesado devuelve false y el consumidor debe ignorarlo. Tabla processed_event.
 */
public class ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public ProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markIfFirst(String consumer, String eventId) {
        return jdbc.update("""
                INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, consumer, eventId) == 1;
    }
}

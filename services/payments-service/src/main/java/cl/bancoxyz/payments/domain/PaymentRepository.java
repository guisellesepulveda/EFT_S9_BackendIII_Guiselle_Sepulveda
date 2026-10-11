package cl.bancoxyz.payments.domain;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import cl.bancoxyz.platform.events.payload.TransactionType;

/** SQL explícito: las transiciones de estado son UPDATE condicionales (seguras con varias instancias y reentregas). */
@Repository
public class PaymentRepository {

    private static final RowMapper<Payment> MAPPER = (rs, i) -> new Payment(
            rs.getObject("id", UUID.class), TransactionType.valueOf(rs.getString("type")),
            rs.getObject("from_account_id", UUID.class), rs.getObject("to_account_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getString("currency"), PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("description"), rs.getString("idempotency_key"), rs.getString("request_hash"),
            rs.getBoolean("degraded"), rs.getString("degraded_reason"), rs.getString("failure_code"),
            rs.getString("failure_reason"), rs.getInt("request_attempts"), instant(rs.getTimestamp("requested_at")),
            instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("completed_at")));

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** @throws DuplicateKeyException si la Idempotency-Key ya existe (carrera entre dos peticiones iguales) */
    public void insert(Payment p) {
        jdbc.update("""
                INSERT INTO payment (id, type, from_account_id, to_account_id, amount, currency, status, description,
                                     idempotency_key, request_hash, degraded, degraded_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, p.id(), p.type().name(), p.fromAccountId(), p.toAccountId(), p.amount(), p.currency(),
                p.status().name(), p.description(), p.idempotencyKey(), p.requestHash(), p.degraded(), p.degradedReason());
    }

    public Optional<Payment> findById(UUID id) {
        return jdbc.query("SELECT * FROM payment WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Payment> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM payment WHERE idempotency_key = ?", MAPPER, key).stream().findFirst();
    }

    public List<Payment> findByAccount(UUID accountId, int limit) {
        return jdbc.query("""
                SELECT * FROM payment WHERE from_account_id = ? OR to_account_id = ?
                ORDER BY requested_at DESC LIMIT ?
                """, MAPPER, accountId, accountId, Math.min(Math.max(limit, 1), 200));
    }

    public List<Map<String, Object>> history(UUID paymentId) {
        return jdbc.queryForList("SELECT status, detail, created_at FROM payment_history WHERE payment_id = ? ORDER BY id", paymentId);
    }

    public void addHistory(UUID paymentId, PaymentStatus status, String detail) {
        jdbc.update("INSERT INTO payment_history (payment_id, status, detail) VALUES (?, ?, ?)", paymentId, status.name(), detail);
    }

    /**
     * Transición condicional: solo si el estado actual es uno de {@code from}. Devuelve false si el pago ya avanzó
     * (reentrega, evento tardío o carrera entre consumidores) y el llamador debe ignorar el evento.
     */
    public boolean transition(UUID id, PaymentStatus to, String failureCode, String failureReason, PaymentStatus... from) {
        String placeholders = String.join(",", Arrays.stream(from).map(s -> "'" + s.name() + "'").toList());
        int rows = jdbc.update("UPDATE payment SET status = ?, failure_code = COALESCE(?, failure_code), "
                + "failure_reason = COALESCE(?, failure_reason), updated_at = now(), "
                + "completed_at = CASE WHEN ? THEN now() ELSE completed_at END "
                + "WHERE id = ? AND status IN (" + placeholders + ")", to.name(), failureCode, failureReason, to.terminal(), id);
        return rows == 1;
    }

    /** Pagos PENDING sin avance, bloqueados para esta instancia (SKIP LOCKED: varias instancias no se pisan). */
    public List<Payment> lockStalePending(Duration olderThan, int limit) {
        return jdbc.query("""
                SELECT * FROM payment WHERE status = 'PENDING' AND updated_at < now() - make_interval(secs => ?)
                ORDER BY updated_at LIMIT ? FOR UPDATE SKIP LOCKED
                """, MAPPER, olderThan.toSeconds(), limit);
    }

    public List<Payment> lockStaleCompensating(Duration olderThan, int limit) {
        return jdbc.query("""
                SELECT * FROM payment WHERE status = 'COMPENSATING' AND updated_at < now() - make_interval(secs => ?)
                ORDER BY updated_at LIMIT ? FOR UPDATE SKIP LOCKED
                """, MAPPER, olderThan.toSeconds(), limit);
    }

    public void bumpAttempt(UUID id) {
        jdbc.update("UPDATE payment SET request_attempts = request_attempts + 1, updated_at = now() WHERE id = ?", id);
    }

    public void touch(UUID id) {
        jdbc.update("UPDATE payment SET updated_at = now() WHERE id = ?", id);
    }

    public List<Map<String, Object>> stuck(int limit) {
        return jdbc.queryForList("""
                SELECT id, type, status, request_attempts, failure_code, requested_at, updated_at FROM payment
                WHERE status IN ('PENDING', 'DEBITED', 'COMPENSATING') ORDER BY updated_at LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> out = new java.util.TreeMap<>();
        jdbc.query("SELECT status, count(*) FROM payment GROUP BY status", rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }
}

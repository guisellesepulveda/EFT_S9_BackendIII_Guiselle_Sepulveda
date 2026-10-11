package cl.bancoxyz.bffatm.domain;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL explícito sobre la base propia del cajero (atmdb). */
@Repository
public class Repositories {

    public record Card(String cardId, UUID accountId, String pinHash, int failedAttempts, Instant lockedUntil) {
    }

    public record Withdrawal(String key, String atmId, String cardId, UUID accountId, BigDecimal amount, UUID paymentId,
                             String status, String reason) {
    }

    private final JdbcTemplate jdbc;

    public Repositories(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ tarjetas
    public void insertCard(String cardId, UUID accountId, String pinHash) {
        jdbc.update("INSERT INTO atm_card (card_id, account_id, pin_hash) VALUES (?, ?, ?)", cardId, accountId, pinHash);
    }

    /** Bloquea la fila de la tarjeta durante la transacción: los intentos de PIN concurrentes se serializan. */
    public Optional<Card> lockCard(String cardId) {
        return jdbc.query("SELECT * FROM atm_card WHERE card_id = ? FOR UPDATE", (rs, i) -> new Card(rs.getString("card_id"),
                rs.getObject("account_id", UUID.class), rs.getString("pin_hash"), rs.getInt("failed_attempts"),
                rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant()), cardId).stream().findFirst();
    }

    public void updateAttempts(String cardId, int attempts, Instant lockedUntil) {
        jdbc.update("UPDATE atm_card SET failed_attempts = ?, locked_until = ? WHERE card_id = ?", attempts,
                lockedUntil == null ? null : Timestamp.from(lockedUntil), cardId);
    }

    /** Serializa los retiros de UNA tarjeta (límite diario correcto aunque lleguen en paralelo). */
    public void lockCardForWithdrawal(String cardId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, cardId);
    }

    // ------------------------------------------------------------------ retiros
    public Optional<Withdrawal> findWithdrawal(String key) {
        return jdbc.query("SELECT * FROM atm_withdrawal WHERE idempotency_key = ?", (rs, i) -> new Withdrawal(rs.getString("idempotency_key"),
                rs.getString("atm_id"), rs.getString("card_id"), rs.getObject("account_id", UUID.class), rs.getBigDecimal("amount"),
                rs.getObject("payment_id", UUID.class), rs.getString("status"), rs.getString("reason")), key).stream().findFirst();
    }

    public void insertWithdrawal(String key, String atmId, String cardId, UUID accountId, BigDecimal amount) {
        jdbc.update("INSERT INTO atm_withdrawal (idempotency_key, atm_id, card_id, account_id, amount, status) VALUES (?, ?, ?, ?, ?, 'REQUESTED')",
                key, atmId, cardId, accountId, amount);
    }

    public void updateWithdrawal(String key, UUID paymentId, String status, String reason) {
        jdbc.update("UPDATE atm_withdrawal SET payment_id = COALESCE(?, payment_id), status = ?, reason = ?, updated_at = now() WHERE idempotency_key = ?",
                paymentId, status, reason, key);
    }

    /** Total ya comprometido hoy (UTC) por la tarjeta: retiros solicitados, pendientes o aprobados (los rechazados no cuentan). */
    public BigDecimal withdrawnToday(String cardId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM atm_withdrawal
                WHERE card_id = ? AND status IN ('REQUESTED', 'PENDING', 'APPROVED')
                  AND created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """, BigDecimal.class, cardId);
    }

    // ------------------------------------------------------------------ auditoría
    public void audit(String atmId, String operation, String cardId, BigDecimal amount, String outcome, String detail,
                      UUID paymentId, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO atm_audit (atm_id, operation, card_ref, amount, outcome, detail, payment_id, idempotency_key, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, atmId, operation, mask(cardId), amount, outcome, detail, paymentId, idempotencyKey, MDC.get("traceId"));
    }

    public List<Map<String, Object>> auditTrail(int limit) {
        return jdbc.queryForList("SELECT at, atm_id, operation, card_ref, amount, outcome, detail, payment_id FROM atm_audit ORDER BY id DESC LIMIT ?", limit);
    }

    public static String mask(String cardId) {
        if (cardId == null) {
            return null;
        }
        return "****" + (cardId.length() <= 4 ? cardId : cardId.substring(cardId.length() - 4));
    }
}

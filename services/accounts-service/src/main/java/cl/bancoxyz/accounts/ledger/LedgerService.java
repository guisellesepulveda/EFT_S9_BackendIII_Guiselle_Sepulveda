package cl.bancoxyz.accounts.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Libro mayor de saldos. Garantías:
 * <ul>
 *   <li><b>Idempotencia</b>: la misma operationId nunca mueve dinero dos veces; devuelve el resultado ya registrado.</li>
 *   <li><b>Sin sobregiro</b>: el débito es un UPDATE condicional (balance >= monto) atómico en la base, correcto aunque
 *       haya varias instancias del servicio procesando a la vez.</li>
 *   <li><b>Rechazos también quedan registrados</b> (outcome REJECTED) para poder repetir la misma respuesta.</li>
 * </ul>
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OperationResult apply(String operationId, UUID accountId, OperationType type, BigDecimal amount,
                                 String reference, String description) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("El monto debe ser positivo");
        }
        // "Reclamar" la operación: ON CONFLICT espera a una transacción concurrente con la misma clave y luego no inserta
        int claimed = jdbc.update("""
                INSERT INTO account_operation (operation_id, account_id, op_type, amount, outcome, reference, description)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?) ON CONFLICT (operation_id) DO NOTHING
                """, operationId, accountId, type.name(), amount, reference, description);
        if (claimed == 0) {
            return existing(operationId);
        }

        BigDecimal newBalance = mutate(accountId, type, amount);
        if (newBalance != null) {
            jdbc.update("UPDATE account_operation SET outcome = 'APPLIED', balance_after = ? WHERE operation_id = ?",
                    newBalance, operationId);
            return new OperationResult(operationId, true, null, newBalance, false);
        }
        String reason = rejectionReason(accountId);
        jdbc.update("UPDATE account_operation SET outcome = 'REJECTED', reason_code = ? WHERE operation_id = ?",
                reason, operationId);
        log.info("Operación {} rechazada: {}", operationId, reason);
        return new OperationResult(operationId, false, reason, null, false);
    }

    private BigDecimal mutate(UUID accountId, OperationType type, BigDecimal amount) {
        String sql = type == OperationType.DEBIT
                ? "UPDATE account SET balance = balance - ?, version = version + 1 WHERE id = ? AND status = 'OPEN' AND balance >= ? RETURNING balance"
                : "UPDATE account SET balance = balance + ?, version = version + 1 WHERE id = ? AND status = 'OPEN' RETURNING balance";
        try {
            return type == OperationType.DEBIT
                    ? jdbc.queryForObject(sql, BigDecimal.class, amount, accountId, amount)
                    : jdbc.queryForObject(sql, BigDecimal.class, amount, accountId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String rejectionReason(UUID accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM account WHERE id = ?", accountId);
        if (rows.isEmpty()) {
            return OperationResult.ACCOUNT_NOT_FOUND;
        }
        return "CLOSED".equals(rows.get(0).get("status")) ? OperationResult.ACCOUNT_CLOSED : OperationResult.INSUFFICIENT_FUNDS;
    }

    private OperationResult existing(String operationId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT outcome, reason_code, balance_after FROM account_operation WHERE operation_id = ?", operationId);
        boolean applied = "APPLIED".equals(row.get("outcome"));
        return new OperationResult(operationId, applied, (String) row.get("reason_code"), (BigDecimal) row.get("balance_after"), true);
    }

    /** Proyección: al completarse el pago, sus movimientos pasan de PENDING a CONFIRMED. */
    @Transactional
    public int confirmMovements(UUID paymentId) {
        return jdbc.update("UPDATE account_operation SET movement_status = 'CONFIRMED' WHERE reference = ? AND outcome = 'APPLIED'",
                paymentId.toString());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> movements(UUID accountId, int limit) {
        return jdbc.queryForList("""
                SELECT operation_id, op_type, amount, balance_after, reference, movement_status, description, created_at
                FROM account_operation WHERE account_id = ? AND outcome = 'APPLIED'
                ORDER BY created_at DESC, operation_id LIMIT ?
                """, accountId, Math.min(Math.max(limit, 1), 200));
    }
}

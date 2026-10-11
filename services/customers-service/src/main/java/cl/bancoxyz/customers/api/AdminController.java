package cl.bancoxyz.customers.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.platform.security.AdminOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Endpoints de administración, separados bajo /admin y restringidos a ROLE_ADMIN. */
@RestController
@RequestMapping("/admin")
@Tag(name = "Administración", description = "Solo ROLE_ADMIN")
public class AdminController {

    private final CustomerService service;
    private final JdbcTemplate jdbc;

    public AdminController(CustomerService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @PatchMapping("/customers/{id}/deactivate")
    @AdminOnly
    @Operation(summary = "Desactiva un cliente (baja lógica)")
    public CustomerResponse deactivate(@PathVariable UUID id) {
        return CustomerResponse.of(service.deactivate(id));
    }

    @DeleteMapping("/customers/{id}")
    @AdminOnly
    @Operation(summary = "Elimina físicamente un cliente")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/security-alerts")
    @AdminOnly
    @Operation(summary = "Últimas alertas de seguridad recibidas por Kafka (security.alerts)")
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT source, severity, alert_type, subject, detail, occurred_at
                FROM security_alert ORDER BY occurred_at DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/notifications")
    @AdminOnly
    @Operation(summary = "Notificaciones registradas (transactions.completed)")
    public List<Map<String, Object>> notifications(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("SELECT payment_id, channel, message, created_at FROM notification_log ORDER BY id DESC LIMIT ?",
                Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/batch-events")
    @AdminOnly
    @Operation(summary = "Eventos de monitoreo de batch (batch.job.events)")
    public List<Map<String, Object>> batchEvents(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT event_type, job_name, status, attempt, will_retry, occurred_at
                FROM batch_job_event ORDER BY id DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }
}

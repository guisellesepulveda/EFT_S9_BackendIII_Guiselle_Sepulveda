package cl.bancoxyz.payments.api;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.payments.domain.PaymentRepository;
import cl.bancoxyz.platform.security.AdminOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/admin")
@Tag(name = "Administración", description = "Solo ROLE_ADMIN")
public class AdminController {

    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;

    public AdminController(PaymentRepository payments, JdbcTemplate jdbc) {
        this.payments = payments;
        this.jdbc = jdbc;
    }

    @GetMapping("/payments/summary")
    @AdminOnly
    @Operation(summary = "Cantidad de pagos por estado")
    public Map<String, Long> summary() {
        return payments.countByStatus();
    }

    @GetMapping("/payments/stuck")
    @AdminOnly
    @Operation(summary = "Pagos aún en curso (PENDING, DEBITED, COMPENSATING), los más antiguos primero")
    public List<Map<String, Object>> stuck(@RequestParam(defaultValue = "50") int limit) {
        return payments.stuck(limit);
    }

    @GetMapping("/outbox")
    @AdminOnly
    @Operation(summary = "Eventos pendientes de publicar en Kafka (outbox)")
    public Map<String, Object> outbox() {
        return Map.of("pending", jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class));
    }
}

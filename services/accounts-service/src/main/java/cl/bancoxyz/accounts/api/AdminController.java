package cl.bancoxyz.accounts.api;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.platform.security.AdminOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/admin")
@Tag(name = "Administración", description = "Solo ROLE_ADMIN")
public class AdminController {

    private final AccountService service;
    private final JdbcTemplate jdbc;

    public AdminController(AccountService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @GetMapping("/accounts")
    @AdminOnly
    @Operation(summary = "Todas las cuentas (paginado)")
    public Map<String, Object> accounts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Page<AccountResponse> p = service.list(page, size).map(AccountResponse::of);
        return Map.of("items", p.getContent(), "page", p.getNumber(), "totalItems", p.getTotalElements());
    }

    @GetMapping("/outbox")
    @AdminOnly
    @Operation(summary = "Eventos pendientes de publicar en Kafka (outbox)")
    public Map<String, Object> outbox() {
        return Map.of("pending", jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class));
    }
}

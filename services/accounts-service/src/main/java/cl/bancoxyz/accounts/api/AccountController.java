package cl.bancoxyz.accounts.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.accounts.domain.Account;
import cl.bancoxyz.accounts.ledger.LedgerService;
import cl.bancoxyz.platform.security.CanRead;
import cl.bancoxyz.platform.security.CanWrite;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Cuentas", description = "Apertura, cierre, consulta y saldo")
public class AccountController {

    private final AccountService service;
    private final LedgerService ledger;

    public AccountController(AccountService service, LedgerService ledger) {
        this.service = service;
        this.ledger = ledger;
    }

    @PostMapping
    @CanWrite
    @Operation(summary = "Abre una cuenta (verifica al cliente en customers-service con Resilience4j) y emite accounts.lifecycle")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody AccountRequest request) {
        AccountResponse created = AccountResponse.of(service.open(request));
        return ResponseEntity.created(URI.create("/accounts/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @CanRead
    @Operation(summary = "Obtiene una cuenta")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.of(service.get(id));
    }

    @GetMapping("/{id}/balance")
    @CanRead
    @Operation(summary = "Saldo actual")
    public BalanceResponse balance(@PathVariable UUID id) {
        Account a = service.get(id);
        return new BalanceResponse(a.getId(), a.getBalance(), a.getCurrency(), Instant.now());
    }

    @PatchMapping("/{id}/close")
    @CanWrite
    @Operation(summary = "Cierra la cuenta (solo con saldo 0) y emite accounts.lifecycle")
    public AccountResponse close(@PathVariable UUID id) {
        return AccountResponse.of(service.close(id));
    }

    @GetMapping
    @CanRead
    @Operation(summary = "Cuentas de un cliente")
    public List<AccountResponse> byCustomer(@RequestParam UUID customerId) {
        return service.byCustomer(customerId).stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{id}/movements")
    @CanRead
    @Operation(summary = "Últimos movimientos aplicados (estado PENDING hasta que el pago se completa; luego CONFIRMED)")
    public List<Map<String, Object>> movements(@PathVariable UUID id, @RequestParam(defaultValue = "5") int limit) {
        service.get(id); // 404 si la cuenta no existe
        return ledger.movements(id, limit);
    }
}

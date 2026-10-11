package cl.bancoxyz.bffmobile.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.bff.common.clients.AccountsBackend;
import cl.bancoxyz.bff.common.clients.Dtos.AccountDto;
import cl.bancoxyz.bff.common.clients.Dtos.CustomerDto;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentCommand;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentDto;
import cl.bancoxyz.bff.common.clients.PaymentsBackend;
import cl.bancoxyz.bff.common.resilience.Await;
import cl.bancoxyz.bff.common.security.CustomerResolver;
import cl.bancoxyz.bffmobile.api.MobileDtos.Account;
import cl.bancoxyz.bffmobile.api.MobileDtos.Home;
import cl.bancoxyz.bffmobile.api.MobileDtos.Movement;
import cl.bancoxyz.bffmobile.api.MobileDtos.RefreshRequest;
import cl.bancoxyz.bffmobile.api.MobileDtos.TokenRequest;
import cl.bancoxyz.bffmobile.api.MobileDtos.Tokens;
import cl.bancoxyz.bffmobile.api.MobileDtos.Transfer;
import cl.bancoxyz.bffmobile.api.MobileDtos.TransferRequest;
import cl.bancoxyz.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/mobile")
@Tag(name = "BFF móvil", description = "API mínima: DTOs reducidos, gzip, ETag/Cache-Control y respuestas parciales (fields=)")
public class MobileController {

    static final Set<String> ACCOUNT_FIELDS = Set.of("id", "number", "type", "balance");
    static final Set<String> MOVEMENT_FIELDS = Set.of("at", "type", "amount", "status");
    static final int MAX_MOVEMENTS = 20;

    private final CustomerResolver resolver;
    private final AccountsBackend accounts;
    private final PaymentsBackend payments;
    private final AuthProxy auth;
    private final ObjectMapper mapper;

    public MobileController(CustomerResolver resolver, AccountsBackend accounts, PaymentsBackend payments, AuthProxy auth,
                            ObjectMapper mapper) {
        this.resolver = resolver;
        this.accounts = accounts;
        this.payments = payments;
        this.auth = auth;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ autenticación (públicos, con rate limit por IP)
    @PostMapping("/auth/token")
    @Operation(summary = "Canjea el código de autorización (PKCE) por tokens; el BFF aporta el secreto del cliente confidencial")
    public Tokens token(@Valid @RequestBody TokenRequest request) {
        return auth.exchangeCode(request.code(), request.codeVerifier(), request.redirectUri());
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "Renueva el access token (5 min) con el refresh token; cada refresh entrega uno NUEVO (rotación)")
    public Tokens refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    // ------------------------------------------------------------------ consultas
    @GetMapping("/home")
    @PreAuthorize("hasAuthority('SCOPE_mobile:read')")
    @Operation(summary = "Pantalla de inicio en UNA llamada: saldo total y cuentas abiertas (solo campos esenciales)")
    public Home home(@AuthenticationPrincipal Jwt jwt) {
        List<Account> open = openAccounts(bearer(jwt), jwt);
        return new Home(open.stream().map(Account::balance).reduce(BigDecimal.ZERO, BigDecimal::add), open);
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('SCOPE_mobile:read')")
    @Operation(summary = "Mis cuentas abiertas. fields=id,number,type,balance permite pedir solo algunos campos")
    public List<Map<String, Object>> accounts(@AuthenticationPrincipal Jwt jwt,
                                              @Parameter(description = "Campos separados por coma") @RequestParam(required = false) String fields) {
        return PartialFields.filter(mapper, openAccounts(bearer(jwt), jwt), fields, ACCOUNT_FIELDS);
    }

    @GetMapping("/accounts/{id}/movements")
    @PreAuthorize("hasAuthority('SCOPE_mobile:read')")
    @Operation(summary = "Últimos movimientos de una cuenta propia (máx. 20). fields=at,type,amount,status")
    public List<Map<String, Object>> movements(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable UUID id, @RequestParam(defaultValue = "10") int limit,
                                               @RequestParam(required = false) String fields) {
        PartialFields.parse(fields, MOVEMENT_FIELDS); // valida antes de llamar a los servicios
        String bearer = bearer(jwt);
        CustomerDto me = resolver.current(bearer, jwt.getSubject());
        resolver.ownAccount(bearer, me, id);
        List<Movement> list = Await.of(accounts.movements(bearer, id, Math.min(Math.max(limit, 1), MAX_MOVEMENTS)), "accounts").stream()
                .map(m -> new Movement(m.createdAt(), m.type(), m.amount(), m.status())).toList();
        return PartialFields.filter(mapper, list, fields, MOVEMENT_FIELDS);
    }

    // ------------------------------------------------------------------ transferencias
    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('SCOPE_mobile:transfer')")
    @Operation(summary = "Transferencia desde una cuenta propia (Idempotency-Key obligatoria). Responde 202 con id y estado")
    public ResponseEntity<Transfer> transfer(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @Valid @RequestBody TransferRequest request) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "La cabecera Idempotency-Key es obligatoria (8 a 100 caracteres)");
        }
        String bearer = bearer(jwt);
        CustomerDto me = resolver.current(bearer, jwt.getSubject());
        resolver.ownAccount(bearer, me, request.from());
        PaymentDto p = Await.of(payments.transfer(bearer, "mobile-" + me.id() + "-" + key,
                new PaymentCommand(request.from(), request.to(), request.amount(), request.note())), "payments");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Transfer(p.paymentId(), p.status()));
    }

    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasAuthority('SCOPE_mobile:read')")
    @Operation(summary = "Estado de una transferencia propia (id y estado)")
    public Transfer transferStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        String bearer = bearer(jwt);
        CustomerDto me = resolver.current(bearer, jwt.getSubject());
        PaymentDto p = Await.of(payments.get(bearer, id), "payments");
        if (p.fromAccountId() == null) {
            throw ApiException.notFound("Transferencia", id);
        }
        resolver.ownAccount(bearer, me, p.fromAccountId());
        return new Transfer(p.paymentId(), p.status());
    }

    /** Token relay: el access token del propio usuario se reenvía a los servicios (que autorizan por scope del canal). */
    private static String bearer(Jwt jwt) {
        return "Bearer " + jwt.getTokenValue();
    }

    private List<Account> openAccounts(String bearer, Jwt jwt) {
        CustomerDto me = resolver.current(bearer, jwt.getSubject());
        List<AccountDto> list = Await.of(accounts.byCustomer(bearer, me.id()), "accounts");
        return list.stream().filter(a -> "OPEN".equals(a.status())).map(a -> new Account(a.id(), a.number(), a.type(), a.balance())).toList();
    }
}

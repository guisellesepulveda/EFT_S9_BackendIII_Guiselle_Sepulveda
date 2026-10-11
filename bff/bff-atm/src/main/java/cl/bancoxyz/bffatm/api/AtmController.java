package cl.bancoxyz.bffatm.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.bffatm.AtmService;
import cl.bancoxyz.bffatm.PinService;
import cl.bancoxyz.bffatm.security.ClientCertificateFilter;
import cl.bancoxyz.bffatm.api.AtmDtos.BalanceView;
import cl.bancoxyz.bffatm.api.AtmDtos.CardPin;
import cl.bancoxyz.bffatm.api.AtmDtos.CardRequest;
import cl.bancoxyz.bffatm.api.AtmDtos.MovementItem;
import cl.bancoxyz.bffatm.api.AtmDtos.WithdrawalRequest;
import cl.bancoxyz.bffatm.api.AtmDtos.WithdrawalResult;
import cl.bancoxyz.bffatm.domain.Repositories;
import cl.bancoxyz.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/atm")
@Tag(name = "BFF ATM", description = "mTLS + token atm:operate. Solo consulta de saldo, retiro y últimos 5 movimientos")
public class AtmController {


    private final AtmService service;
    private final PinService pins;
    private final Repositories repo;

    public AtmController(AtmService service, PinService pins, Repositories repo) {
        this.service = service;
        this.pins = pins;
        this.repo = repo;
    }

    private static String bearer(Jwt jwt) {
        return "Bearer " + jwt.getTokenValue();
    }

    @PostMapping("/balance")
    @PreAuthorize("hasAuthority('SCOPE_atm:operate')")
    @Operation(summary = "Consulta de saldo (tarjeta + PIN en el cuerpo)")
    public BalanceView balance(@AuthenticationPrincipal Jwt jwt, @RequestAttribute(ClientCertificateFilter.ATM_ATTR) String atmId, @Valid @RequestBody CardPin request) {
        return service.balance(bearer(jwt), atmId, request.cardId(), request.pin());
    }

    @PostMapping("/movements")
    @PreAuthorize("hasAuthority('SCOPE_atm:operate')")
    @Operation(summary = "Últimos 5 movimientos (solo fecha, tipo y monto)")
    public List<MovementItem> movements(@AuthenticationPrincipal Jwt jwt, @RequestAttribute(ClientCertificateFilter.ATM_ATTR) String atmId, @Valid @RequestBody CardPin request) {
        return service.lastMovements(bearer(jwt), atmId, request.cardId(), request.pin());
    }

    @PostMapping("/withdrawals")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "APPROVED: pago confirmado, el cajero puede dispensar (dispense=true)"),
            @ApiResponse(responseCode = "202", description = "PENDING: sin confirmación dentro del plazo; NO dispensar, consultar GET /atm/withdrawals/{key}"),
            @ApiResponse(responseCode = "400", description = "Idempotency-Key ausente o cuerpo inválido"),
            @ApiResponse(responseCode = "401", description = "Token ausente, certificado ausente o tarjeta/PIN incorrectos"),
            @ApiResponse(responseCode = "403", description = "Token de otro canal (sin atm:operate) o cajero revocado"),
            @ApiResponse(responseCode = "422", description = "DECLINED, límite por retiro/diario superado o Idempotency-Key reutilizada con otra solicitud"),
            @ApiResponse(responseCode = "423", description = "Tarjeta bloqueada por PIN incorrecto"),
            @ApiResponse(responseCode = "503", description = "UNAVAILABLE: servicio temporalmente no disponible; nunca se dispensa")})
    @PreAuthorize("hasAuthority('SCOPE_atm:operate')")
    @Operation(summary = "Retiro. Idempotency-Key OBLIGATORIA. dispense=true solo si el pago está confirmado (200); 202 si sigue en proceso; 422 si se rechaza; 503 si no hay servicio")
    public ResponseEntity<WithdrawalResult> withdraw(@AuthenticationPrincipal Jwt jwt, @RequestAttribute(ClientCertificateFilter.ATM_ATTR) String atmId,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     @Valid @RequestBody WithdrawalRequest request) {
        if (key == null || !key.matches("^[A-Za-z0-9._-]{8,64}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "La cabecera Idempotency-Key es obligatoria en retiros (8 a 64 caracteres: letras, dígitos, . _ -)");
        }
        return respond(service.withdraw(bearer(jwt), atmId, key, request.cardId(), request.pin(), request.amount()));
    }

    @GetMapping("/withdrawals/{key}")
    @PreAuthorize("hasAuthority('SCOPE_atm:operate')")
    @Operation(summary = "Estado de un retiro por su Idempotency-Key (para cuando se recibió 202 PENDING)")
    public ResponseEntity<WithdrawalResult> status(@AuthenticationPrincipal Jwt jwt, @RequestAttribute(ClientCertificateFilter.ATM_ATTR) String atmId, @PathVariable String key) {
        return respond(service.status(bearer(jwt), atmId, key));
    }

    private static ResponseEntity<WithdrawalResult> respond(WithdrawalResult r) {
        HttpStatus status = switch (r.status()) {
            case "APPROVED" -> HttpStatus.OK;
            case "PENDING" -> HttpStatus.ACCEPTED;
            case "DECLINED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            b.header("Retry-After", "2");
        }
        return b.body(r);
    }

    // ------------------------------------------------------------------ emisor de tarjetas SIMULADO (machine-to-machine)
    @PostMapping("/internal/cards")
    @PreAuthorize("hasAuthority('SCOPE_internal')")
    @Operation(summary = "Emisión simulada de tarjeta (solo scope internal; el PIN se guarda como hash BCrypt)")
    public ResponseEntity<Map<String, String>> issueCard(@Valid @RequestBody CardRequest request) {
        repo.insertCard(request.cardId(), request.accountId(), pins.hash(request.pin()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("card", Repositories.mask(request.cardId())));
    }

    @GetMapping("/internal/audit")
    @PreAuthorize("hasAuthority('SCOPE_internal')")
    @Operation(summary = "Últimos registros de auditoría (solo scope internal)")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        return repo.auditTrail(Math.min(Math.max(limit, 1), 500));
    }
}

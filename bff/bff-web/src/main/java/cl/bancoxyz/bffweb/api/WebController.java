package cl.bancoxyz.bffweb.api;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import cl.bancoxyz.bff.common.PageResponse;
import cl.bancoxyz.bff.common.clients.AccountsBackend;
import cl.bancoxyz.bff.common.clients.Dtos.AccountDto;
import cl.bancoxyz.bff.common.clients.Dtos.CustomerDto;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentCommand;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentDto;
import cl.bancoxyz.bff.common.clients.PaymentsBackend;
import cl.bancoxyz.bff.common.resilience.Await;
import cl.bancoxyz.bff.common.security.CustomerResolver;
import cl.bancoxyz.bffweb.api.WebDtos.AccountView;
import cl.bancoxyz.bffweb.api.WebDtos.AccountsPage;
import cl.bancoxyz.bffweb.api.WebDtos.Dashboard;
import cl.bancoxyz.bffweb.api.WebDtos.Me;
import cl.bancoxyz.bffweb.api.WebDtos.MovementView;
import cl.bancoxyz.bffweb.api.WebDtos.MovementsPage;
import cl.bancoxyz.bffweb.api.WebDtos.TransferRequest;
import cl.bancoxyz.bffweb.api.WebDtos.TransferView;
import cl.bancoxyz.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/web")
@Tag(name = "BFF web", description = "Sesión por cookie HttpOnly/Secure; datos completos y agregados")
public class WebController {

    private static final String CLIENT = "web-client";

    private final CustomerResolver resolver;
    private final DashboardService dashboard;
    private final AccountsBackend accounts;
    private final PaymentsBackend payments;

    public WebController(CustomerResolver resolver, DashboardService dashboard, AccountsBackend accounts, PaymentsBackend payments) {
        this.resolver = resolver;
        this.dashboard = dashboard;
        this.accounts = accounts;
        this.payments = payments;
    }

    private static String bearer(OAuth2AuthorizedClient client) {
        return "Bearer " + client.getAccessToken().getTokenValue();
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    @Operation(summary = "Usuario de la sesión (nunca expone tokens)")
    public Me me(@AuthenticationPrincipal OAuth2User user) {
        List<String> roles = user.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).sorted().toList();
        List<String> scopes = user.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_")).map(a -> a.substring(6)).sorted().toList();
        return new Me(user.getName(), roles, scopes, "web");
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    @Operation(summary = "Tablero agregado: cliente + perfil + cuentas + últimos movimientos (consultas en paralelo)")
    public Dashboard dashboard(@RegisteredOAuth2AuthorizedClient(CLIENT) OAuth2AuthorizedClient client,
                               @AuthenticationPrincipal OAuth2User user,
                               @RequestParam(defaultValue = "5") int movements) {
        String bearer = bearer(client);
        CustomerDto me = resolver.current(bearer, user.getName());
        return dashboard.build(bearer, me, Math.min(Math.max(movements, 1), 20));
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    @Operation(summary = "Mis cuentas con paginación y filtros (status, type)")
    public AccountsPage accounts(@RegisteredOAuth2AuthorizedClient(CLIENT) OAuth2AuthorizedClient client,
                                 @AuthenticationPrincipal OAuth2User user,
                                 @RequestParam(required = false) String status, @RequestParam(required = false) String type,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        String bearer = bearer(client);
        CustomerDto me = resolver.current(bearer, user.getName());
        List<AccountView> all = Await.of(accounts.byCustomer(bearer, me.id()), "accounts").stream()
                .filter(a -> status == null || status.equalsIgnoreCase(a.status()))
                .filter(a -> type == null || type.equalsIgnoreCase(a.type()))
                .map(a -> DashboardService.view(a, List.of())).toList();
        PageResponse<AccountView> p = PageResponse.of(all, page, size, 50);
        return new AccountsPage(p.items(), p.page(), p.size(), p.totalItems(), p.totalPages());
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    @Operation(summary = "Detalle de una cuenta propia con sus últimos movimientos (cuenta y movimientos en paralelo)")
    public AccountView account(@RegisteredOAuth2AuthorizedClient(CLIENT) OAuth2AuthorizedClient client,
                               @AuthenticationPrincipal OAuth2User user, @PathVariable UUID id,
                               @RequestParam(defaultValue = "10") int movements) {
        String bearer = bearer(client);
        CustomerDto me = resolver.current(bearer, user.getName());
        var movF = accounts.movements(bearer, id, Math.min(Math.max(movements, 1), 50));
        AccountDto a = resolver.ownAccount(bearer, me, id);
        try {
            return DashboardService.view(a, Await.of(movF, "accounts"));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.SERVICE_UNAVAILABLE) {
                return DashboardService.view(a, null); // la cuenta se muestra aunque los movimientos no estén disponibles
            }
            throw e;
        }
    }

    @GetMapping("/accounts/{id}/movements")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    @Operation(summary = "Movimientos de una cuenta propia con filtros (type, status, from, to) y paginación")
    public MovementsPage movements(@RegisteredOAuth2AuthorizedClient(CLIENT) OAuth2AuthorizedClient client,
                                   @AuthenticationPrincipal OAuth2User user, @PathVariable UUID id,
                                   @RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                   @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        String bearer = bearer(client);
        CustomerDto me = resolver.current(bearer, user.getName());
        resolver.ownAccount(bearer, me, id);
        List<MovementView> all = Await.of(accounts.movements(bearer, id, 200), "accounts").stream()
                .map(DashboardService::movement)
                .filter(m -> type == null || type.equalsIgnoreCase(m.type()))
                .filter(m -> status == null || status.equalsIgnoreCase(m.status()))
                .filter(m -> from == null || (m.at() != null && !m.at().atOffset(ZoneOffset.UTC).toLocalDate().isBefore(from)))
                .filter(m -> to == null || (m.at() != null && !m.at().atOffset(ZoneOffset.UTC).toLocalDate().isAfter(to)))
                .sorted(DashboardService.NEWEST_FIRST).toList();
        PageResponse<MovementView> p = PageResponse.of(all, page, size, 100);
        return new MovementsPage(p.items(), p.page(), p.size(), p.totalItems(), p.totalPages());
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    @Operation(summary = "Transferencia desde una cuenta propia. Idempotency-Key obligatoria; responde 202 (la saga es asíncrona)")
    public ResponseEntity<TransferView> transfer(@RegisteredOAuth2AuthorizedClient(CLIENT) OAuth2AuthorizedClient client,
                                                 @AuthenticationPrincipal OAuth2User user,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @Valid @RequestBody TransferRequest request) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "La cabecera Idempotency-Key es obligatoria (8 a 100 caracteres)");
        }
        String bearer = bearer(client);
        CustomerDto me = resolver.current(bearer, user.getName());
        resolver.ownAccount(bearer, me, request.fromAccountId());                  // solo se transfiere desde cuentas propias
        PaymentDto p = Await.of(payments.transfer(bearer, "web-" + me.id() + "-" + key,
                new PaymentCommand(request.fromAccountId(), request.toAccountId(), request.amount(), request.description())), "payments");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(transfer(p));
    }

    @GetMapping("/transfers/{paymentId}")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    @Operation(summary = "Estado de una transferencia propia")
    public TransferView transfer(@RegisteredOAuth2AuthorizedClient(CLIENT) OAuth2AuthorizedClient client,
                                 @AuthenticationPrincipal OAuth2User user, @PathVariable UUID paymentId) {
        String bearer = bearer(client);
        CustomerDto me = resolver.current(bearer, user.getName());
        PaymentDto p = Await.of(payments.get(bearer, paymentId), "payments");
        if (p.fromAccountId() == null) {
            throw ApiException.notFound("Transferencia", paymentId);
        }
        resolver.ownAccount(bearer, me, p.fromAccountId());   // 404 si el pago no sale de una cuenta propia
        return transfer(p);
    }

    private static TransferView transfer(PaymentDto p) {
        return new TransferView(p.paymentId(), p.status(), p.fromAccountId(), p.toAccountId(), p.amount(), p.currency(),
                p.degraded(), p.failureCode(), p.requestedAt(), p.completedAt());
    }
}

package cl.bancoxyz.bffweb.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import cl.bancoxyz.bff.common.clients.AccountsBackend;
import cl.bancoxyz.bff.common.clients.CustomersBackend;
import cl.bancoxyz.bff.common.clients.Dtos.AccountDto;
import cl.bancoxyz.bff.common.clients.Dtos.CustomerDto;
import cl.bancoxyz.bff.common.clients.Dtos.MovementDto;
import cl.bancoxyz.bff.common.clients.Dtos.ProfileDto;
import cl.bancoxyz.bff.common.resilience.Await;
import cl.bancoxyz.bffweb.api.WebDtos.AccountView;
import cl.bancoxyz.bffweb.api.WebDtos.CustomerCard;
import cl.bancoxyz.bffweb.api.WebDtos.Dashboard;
import cl.bancoxyz.bffweb.api.WebDtos.MovementView;
import cl.bancoxyz.bffweb.api.WebDtos.ProfileCard;

/**
 * Tablero agregado. Las llamadas a customers-service (perfil), accounts-service (cuentas) y, por cada cuenta, sus movimientos
 * se lanzan EN PARALELO (CompletableFuture sobre el pool de Resilience4j): la latencia es la de la más lenta, no la suma.
 * Degradación elegante: si el perfil o los movimientos fallan, el tablero se entrega igual (marcado degraded) con lo
 * disponible; solo la lista de cuentas es imprescindible.
 */
@Service
public class DashboardService {

    private final AccountsBackend accounts;
    private final CustomersBackend customers;

    public DashboardService(AccountsBackend accounts, CustomersBackend customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    public Dashboard build(String bearer, CustomerDto me, int movementsPerAccount) {
        List<String> unavailable = new CopyOnWriteArrayList<>();

        CompletableFuture<ProfileDto> profileF = customers.profile(bearer, me.id()).exceptionally(t -> {
            unavailable.add("profile");
            return null;
        });
        CompletableFuture<List<AccountDto>> accountsF = accounts.byCustomer(bearer, me.id());

        // En cuanto llega la lista de cuentas, los movimientos de TODAS se piden a la vez
        CompletableFuture<List<AccountView>> viewsF = accountsF.thenCompose(list -> {
            List<CompletableFuture<AccountView>> parts = new ArrayList<>();
            for (AccountDto a : list) {
                if ("OPEN".equals(a.status())) {
                    parts.add(accounts.movements(bearer, a.id(), movementsPerAccount)
                            .handle((movs, t) -> {
                                if (t != null) {
                                    unavailable.add("movements:" + a.number());
                                    return view(a, null);
                                }
                                return view(a, movs);
                            }));
                } else {
                    parts.add(CompletableFuture.completedFuture(view(a, List.of())));
                }
            }
            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                    .thenApply(v -> parts.stream().map(CompletableFuture::join).toList());
        });

        List<AccountView> views = Await.of(viewsF, "accounts");   // sin cuentas no hay tablero: 503 seguro
        ProfileDto profile = profileF.join();
        BigDecimal total = views.stream().filter(v -> "OPEN".equals(v.status()))
                .map(AccountView::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> missing = new ArrayList<>(unavailable);
        return new Dashboard(new CustomerCard(me.id(), me.firstName() + " " + me.lastName(), me.email(), me.phone()),
                profile == null ? null : new ProfileCard(profile.accountsOpen(), profile.accountsClosed(), profile.lastAccountEventAt()),
                views, total, !missing.isEmpty(), missing, Instant.now());
    }

    public static AccountView view(AccountDto a, List<MovementDto> movements) {
        List<MovementView> recent = movements == null ? null : movements.stream().map(DashboardService::movement).toList();
        return new AccountView(a.id(), a.number(), a.type(), a.currency(), a.balance(), a.status(), a.openedAt(), recent,
                movements != null);
    }

    public static MovementView movement(MovementDto m) {
        UUID payment = null;
        try {
            payment = m.reference() == null ? null : UUID.fromString(m.reference());
        } catch (IllegalArgumentException ignored) {
            // referencia que no es un pago
        }
        return new MovementView(m.type(), m.amount(), m.balanceAfter(), m.status(), m.description(), m.createdAt(), payment);
    }

    static final Comparator<MovementView> NEWEST_FIRST = Comparator.comparing(MovementView::at,
            Comparator.nullsLast(Comparator.reverseOrder()));
}

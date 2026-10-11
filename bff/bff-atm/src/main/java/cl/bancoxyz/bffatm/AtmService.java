package cl.bancoxyz.bffatm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.bff.common.clients.AccountsBackend;
import cl.bancoxyz.bff.common.clients.Dtos.AccountDto;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentCommand;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentDto;
import cl.bancoxyz.bff.common.clients.PaymentsBackend;
import cl.bancoxyz.bff.common.resilience.Await;
import cl.bancoxyz.bffatm.PinService.Status;
import cl.bancoxyz.bffatm.api.AtmDtos.BalanceView;
import cl.bancoxyz.bffatm.api.AtmDtos.MovementItem;
import cl.bancoxyz.bffatm.api.AtmDtos.WithdrawalResult;
import cl.bancoxyz.bffatm.domain.Repositories;
import cl.bancoxyz.bffatm.domain.Repositories.Withdrawal;
import cl.bancoxyz.platform.web.ApiException;

/**
 * Operaciones del cajero. Principios:
 * <ul>
 *   <li><b>Nunca se dispensa sin confirmación</b>: dispense=true solo cuando payments-service informa COMPLETED.</li>
 *   <li><b>Idempotencia</b>: la Idempotency-Key del cajero identifica el retiro; reintentarla no duplica ni el retiro ni el
 *       pago (la clave se propaga a payments-service).</li>
 *   <li><b>Seguridad</b>: PIN validado en servidor con bloqueo, límite por retiro y diario (serializado por tarjeta),
 *       auditoría de cada operación y alertas a security.alerts. Errores neutros: ningún detalle interno llega al cajero.</li>
 * </ul>
 */
@Service
public class AtmService {

    static final String UNAVAILABLE_MESSAGE = "Servicio temporalmente no disponible. No se dispensará dinero.";

    private final PinService pin;
    private final Repositories repo;
    private final AccountsBackend accounts;
    private final PaymentsBackend payments;
    private final AlertService alerts;
    private final AtmProperties props;
    private final TransactionTemplate tx;

    public AtmService(PinService pin, Repositories repo, AccountsBackend accounts, PaymentsBackend payments, AlertService alerts,
                      AtmProperties props, PlatformTransactionManager txManager) {
        this.pin = pin;
        this.repo = repo;
        this.accounts = accounts;
        this.payments = payments;
        this.alerts = alerts;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ consultas
    public BalanceView balance(String bearer, String atmId, String cardId, String pinValue) {
        UUID accountId = authenticate(atmId, cardId, pinValue);
        try {
            AccountDto a = Await.of(accounts.get(bearer, accountId), "accounts");
            audit(atmId, "BALANCE", cardId, null, "OK", null, null, null);
            return new BalanceView(Repositories.mask(a.number()), a.balance(), a.currency(), Instant.now());
        } catch (ApiException e) {
            audit(atmId, "BALANCE", cardId, null, "ERROR", e.code(), null, null);
            throw e;
        }
    }

    public List<MovementItem> lastMovements(String bearer, String atmId, String cardId, String pinValue) {
        UUID accountId = authenticate(atmId, cardId, pinValue);
        try {
            List<MovementItem> list = Await.of(accounts.movements(bearer, accountId, 5), "accounts").stream()
                    .limit(5).map(m -> new MovementItem(m.createdAt(), m.type(), m.amount())).toList();
            audit(atmId, "MOVEMENTS", cardId, null, "OK", null, null, null);
            return list;
        } catch (ApiException e) {
            audit(atmId, "MOVEMENTS", cardId, null, "ERROR", e.code(), null, null);
            throw e;
        }
    }

    // ------------------------------------------------------------------ retiro
    public WithdrawalResult withdraw(String bearer, String atmId, String key, String cardId, String pinValue, BigDecimal amount) {
        checkAmount(atmId, cardId, amount, key);

        Optional<Withdrawal> existing = repo.findWithdrawal(key);
        if (existing.isPresent()) {
            return replay(bearer, atmId, existing.get(), cardId, amount);
        }

        UUID accountId = authenticate(atmId, cardId, pinValue);
        Reservation reservation = tx.execute(status -> reserve(atmId, key, cardId, accountId, amount));
        if (reservation.rejection != null) {
            throw reservation.rejection;
        }
        return settle(bearer, atmId, reservation.withdrawal);
    }

    /** Estado de un retiro ya solicitado (el cajero lo consulta si recibió PENDING). Solo el mismo cajero que lo creó. */
    public WithdrawalResult status(String bearer, String atmId, String key) {
        Withdrawal w = repo.findWithdrawal(key).filter(x -> x.atmId().equals(atmId))
                .orElseThrow(() -> ApiException.notFound("Retiro", key));
        return switch (w.status()) {
            case "APPROVED", "DECLINED" -> result(w);
            default -> settle(bearer, atmId, w);
        };
    }

    private record Reservation(Withdrawal withdrawal, ApiException rejection) {
    }

    private Reservation reserve(String atmId, String key, String cardId, UUID accountId, BigDecimal amount) {
        repo.lockCardForWithdrawal(cardId);                            // serializa los retiros de esta tarjeta
        Optional<Withdrawal> raced = repo.findWithdrawal(key);
        if (raced.isPresent()) {
            return new Reservation(raced.get(), null);
        }
        BigDecimal today = repo.withdrawnToday(cardId);
        if (today.add(amount).compareTo(props.limits().dailyLimit()) > 0) {
            repo.audit(atmId, "WITHDRAWAL", cardId, amount, "REJECTED", "DAILY_LIMIT_EXCEEDED", null, key);
            alerts.raiseInTx("LOW", "DAILY_LIMIT_EXCEEDED", Repositories.mask(cardId), "Retiro de " + amount + " supera el límite diario en " + atmId);
            return new Reservation(null, new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DAILY_LIMIT_EXCEEDED",
                    "Superó el límite diario de retiros"));
        }
        repo.insertWithdrawal(key, atmId, cardId, accountId, amount);
        repo.audit(atmId, "WITHDRAWAL", cardId, amount, "REQUESTED", null, null, key);
        return new Reservation(new Withdrawal(key, atmId, cardId, accountId, amount, null, "REQUESTED", null), null);
    }

    /** Repite una solicitud idempotente: mismo cajero, tarjeta y monto → misma decisión; cualquier otra cosa → 422. */
    private WithdrawalResult replay(String bearer, String atmId, Withdrawal w, String cardId, BigDecimal amount) {
        if (!w.atmId().equals(atmId) || !w.cardId().equals(cardId) || w.amount().compareTo(amount) != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "La Idempotency-Key ya se usó con una solicitud distinta");
        }
        return switch (w.status()) {
            case "APPROVED", "DECLINED" -> result(w);
            default -> settle(bearer, atmId, w);
        };
    }

    /** Pide el pago (idempotente), espera la confirmación con timeout estricto y decide. */
    private WithdrawalResult settle(String bearer, String atmId, Withdrawal w) {
        UUID paymentId = w.paymentId();
        if (paymentId == null) {
            try {
                PaymentDto p = Await.of(payments.withdraw(bearer, "atm-" + w.key(),
                        new PaymentCommand(w.accountId(), null, w.amount(), "Retiro cajero " + atmId)), "payments");
                paymentId = p.paymentId();
                repo.updateWithdrawal(w.key(), paymentId, "PENDING", null);
            } catch (ApiException e) {
                if (e.status().is4xxClientError()) {                    // regla de negocio (p. ej. fondos insuficientes)
                    return finish(w, null, "DECLINED", e.code(), "Operación rechazada");
                }
                audit(atmId, "WITHDRAWAL", w.cardId(), w.amount(), "UNAVAILABLE", e.code(), null, w.key());
                return new WithdrawalResult("UNAVAILABLE", false, money(w.amount()), null, UNAVAILABLE_MESSAGE);
            }
        }

        PaymentDto last = awaitConfirmation(bearer, paymentId);
        if (last != null && "COMPLETED".equals(last.status())) {
            return finish(w, paymentId, "APPROVED", null, "Operación aprobada");
        }
        if (last != null && ("FAILED".equals(last.status()) || "COMPENSATED".equals(last.status()))) {
            return finish(w, paymentId, "DECLINED", last.failureCode(), "Operación rechazada");
        }
        audit(atmId, "WITHDRAWAL", w.cardId(), w.amount(), "PENDING", "Sin confirmación dentro del plazo", paymentId, w.key());
        return new WithdrawalResult("PENDING", false, money(w.amount()), paymentId,
                "Operación en proceso. No se dispensará dinero hasta confirmarla.");
    }

    private PaymentDto awaitConfirmation(String bearer, UUID paymentId) {
        long deadline = System.nanoTime() + props.confirmationTimeout().toNanos();
        PaymentDto last = null;
        while (true) {
            try {
                last = Await.of(payments.get(bearer, paymentId), "payments");
                if (last.terminal()) {
                    return last;
                }
            } catch (ApiException ignored) {
                // una consulta fallida no es una decisión: se sigue esperando hasta el plazo
            }
            if (System.nanoTime() >= deadline) {
                return last;
            }
            try {
                Thread.sleep(props.pollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
    }

    private WithdrawalResult finish(Withdrawal w, UUID paymentId, String status, String reason, String message) {
        tx.executeWithoutResult(s -> {
            repo.updateWithdrawal(w.key(), paymentId, status, reason);
            repo.audit(w.atmId(), "WITHDRAWAL", w.cardId(), w.amount(), status, reason, paymentId, w.key());
        });
        return new WithdrawalResult(status, "APPROVED".equals(status), money(w.amount()), paymentId, message);
    }

    /** Mismo formato monetario (2 decimales) venga el monto de la solicitud o de la base. */
    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static WithdrawalResult result(Withdrawal w) {
        boolean approved = "APPROVED".equals(w.status());
        return new WithdrawalResult(w.status(), approved, money(w.amount()), w.paymentId(), approved ? "Operación aprobada" : "Operación rechazada");
    }

    // ------------------------------------------------------------------ validaciones
    private void checkAmount(String atmId, String cardId, BigDecimal amount, String key) {
        AtmProperties.Limits l = props.limits();
        if (amount.remainder(l.denomination()).signum() != 0 || amount.compareTo(l.denomination()) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT",
                    "El monto debe ser múltiplo de " + l.denomination().toPlainString());
        }
        if (amount.compareTo(l.maxPerWithdrawal()) > 0) {
            audit(atmId, "WITHDRAWAL", cardId, amount, "REJECTED", "MAX_PER_WITHDRAWAL_EXCEEDED", null, key);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED",
                    "El monto supera el máximo por retiro (" + l.maxPerWithdrawal().toPlainString() + ")");
        }
    }

    private UUID authenticate(String atmId, String cardId, String pinValue) {
        PinService.Result r = pin.verify(cardId, pinValue, atmId);
        if (r.status() == Status.OK) {
            return r.accountId();
        }
        if (r.status() == Status.LOCKED) {
            throw new ApiException(HttpStatus.LOCKED, "CARD_LOCKED", "Tarjeta bloqueada temporalmente. Contacte a su banco.");
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CARD_OR_PIN", "Tarjeta o PIN incorrectos");
    }

    private void audit(String atmId, String op, String cardId, BigDecimal amount, String outcome, String detail, UUID paymentId, String key) {
        tx.executeWithoutResult(s -> repo.audit(atmId, op, cardId, amount, outcome, detail, paymentId, key));
    }
}

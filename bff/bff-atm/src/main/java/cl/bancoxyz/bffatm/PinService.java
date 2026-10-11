package cl.bancoxyz.bffatm;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.bffatm.domain.Repositories;

/**
 * Validación de PIN del lado del SERVIDOR (simulada: en producción lo haría un HSM). El PIN llega por el cuerpo (nunca en la
 * URL), se compara con BCrypt (tiempo constante), y los intentos fallidos bloquean la tarjeta y generan alertas.
 * Tarjeta inexistente y PIN incorrecto son indistinguibles para el cajero (sin oráculo de existencia).
 */
@Service
public class PinService {

    public enum Status { OK, INVALID, LOCKED }

    public record Result(Status status, UUID accountId, Instant lockedUntil) {
    }

    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("0000");

    private final Repositories repo;
    private final AlertService alerts;
    private final AtmProperties props;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public PinService(Repositories repo, AlertService alerts, AtmProperties props, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.alerts = alerts;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public String hash(String pin) {
        return encoder.encode(pin);
    }

    /** Transacción PROPIA: el contador de intentos se confirma aunque la operación posterior falle. */
    public Result verify(String cardId, String pin, String atmId) {
        return tx.execute(status -> {
            var card = repo.lockCard(cardId);
            if (card.isEmpty()) {
                encoder.matches(pin, DUMMY_HASH);                       // mismo costo que una tarjeta real
                repo.audit(atmId, "PIN_CHECK", cardId, null, "DENIED", "Tarjeta desconocida", null, null);
                alerts.raiseInTx("MEDIUM", "UNKNOWN_CARD", Repositories.mask(cardId), "Intento con tarjeta desconocida desde " + atmId);
                return new Result(Status.INVALID, null, null);
            }
            Repositories.Card c = card.get();
            Instant now = Instant.now();
            if (c.lockedUntil() != null && now.isBefore(c.lockedUntil())) {
                repo.audit(atmId, "PIN_CHECK", cardId, null, "LOCKED", "Tarjeta bloqueada hasta " + c.lockedUntil(), null, null);
                return new Result(Status.LOCKED, null, c.lockedUntil());
            }
            if (encoder.matches(pin, c.pinHash())) {
                if (c.failedAttempts() > 0 || c.lockedUntil() != null) {
                    repo.updateAttempts(cardId, 0, null);
                }
                return new Result(Status.OK, c.accountId(), null);
            }
            int attempts = c.failedAttempts() + 1;
            if (attempts >= props.pin().maxAttempts()) {
                Instant until = now.plus(props.pin().lockDuration());
                repo.updateAttempts(cardId, 0, until);
                repo.audit(atmId, "PIN_CHECK", cardId, null, "CARD_LOCKED", "Tarjeta bloqueada tras " + attempts + " intentos", null, null);
                alerts.raiseInTx("HIGH", "CARD_LOCKED", Repositories.mask(cardId), "Tarjeta bloqueada por " + attempts + " PIN incorrectos en " + atmId);
                return new Result(Status.LOCKED, null, until);
            }
            repo.updateAttempts(cardId, attempts, null);
            repo.audit(atmId, "PIN_CHECK", cardId, null, "WRONG_PIN", "Intento " + attempts + " de " + props.pin().maxAttempts(), null, null);
            alerts.raiseInTx("MEDIUM", "PIN_FAILED", Repositories.mask(cardId), "PIN incorrecto (" + attempts + "/" + props.pin().maxAttempts() + ") en " + atmId);
            return new Result(Status.INVALID, null, null);
        });
    }
}

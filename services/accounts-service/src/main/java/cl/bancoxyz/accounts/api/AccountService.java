package cl.bancoxyz.accounts.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.accounts.clients.CustomerGateway;
import cl.bancoxyz.accounts.clients.CustomerView;
import cl.bancoxyz.accounts.domain.Account;
import cl.bancoxyz.accounts.domain.AccountRepository;
import cl.bancoxyz.accounts.domain.AccountStatus;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.AccountLifecycle;
import cl.bancoxyz.platform.outbox.OutboxService;
import cl.bancoxyz.platform.web.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerGateway customers;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public AccountService(AccountRepository accounts, CustomerGateway customers, OutboxService outbox, JdbcTemplate jdbc,
                          PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.customers = customers;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** La verificación del cliente (llamada remota) va FUERA de la transacción de base de datos. */
    public Account open(AccountRequest request) {
        CustomerView customer = customers.find(request.customerId());
        if (!customer.active()) {
            throw ApiException.unprocessable("CUSTOMER_INACTIVE", "El cliente " + request.customerId() + " está inactivo");
        }
        return tx.execute(status -> {
            Long seq = jdbc.queryForObject("SELECT nextval('account_number_seq')", Long.class);
            Account account = accounts.save(new Account(request.customerId(), "CL" + seq, request.type()));
            // La cuenta y su evento se confirman juntos (Transactional Outbox)
            outbox.publish(Topics.ACCOUNTS_LIFECYCLE, request.customerId().toString(), EventEnvelope.of(
                    EventTypes.ACCOUNT_OPENED, new AccountLifecycle(account.getId(), account.getCustomerId(),
                            account.getType().name(), Instant.now())));
            return account;
        });
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Cuenta", id));
    }

    @Transactional(readOnly = true)
    public List<Account> byCustomer(UUID customerId) {
        return accounts.findByCustomerIdOrderByOpenedAtDesc(customerId);
    }

    /** Cierre atómico en SQL: solo si sigue abierta y con saldo 0 (una carrera con un crédito no puede perder dinero). */
    @Transactional
    public Account close(UUID id) {
        Account account = get(id);
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw ApiException.conflict("ALREADY_CLOSED", "La cuenta " + account.getNumber() + " ya está cerrada");
        }
        int closed = jdbc.update("""
                UPDATE account SET status = 'CLOSED', closed_at = now(), version = version + 1
                WHERE id = ? AND status = 'OPEN' AND balance = 0
                """, id);
        if (closed == 0) {
            throw ApiException.unprocessable("ACCOUNT_NOT_EMPTY", "Solo se puede cerrar una cuenta con saldo 0");
        }
        outbox.publish(Topics.ACCOUNTS_LIFECYCLE, account.getCustomerId().toString(), EventEnvelope.of(EventTypes.ACCOUNT_CLOSED,
                new AccountLifecycle(account.getId(), account.getCustomerId(), account.getType().name(), Instant.now())));
        em.refresh(account); // la fila se actualizó por SQL: se relee el estado
        return account;
    }

    @Transactional(readOnly = true)
    public Page<Account> list(int page, int size) {
        return accounts.findAll(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("openedAt").descending()));
    }
}

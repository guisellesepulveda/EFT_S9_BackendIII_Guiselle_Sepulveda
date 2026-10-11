package cl.bancoxyz.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.accounts.ledger.LedgerService;
import cl.bancoxyz.accounts.ledger.OperationResult;
import cl.bancoxyz.accounts.ledger.OperationType;

/** El libro mayor bajo concurrencia real (varios hilos ≈ varias instancias del servicio contra la misma base). */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "platform.outbox.enabled=false",
        "management.zipkin.tracing.export.enabled=false"})
class LedgerConcurrencyIT extends AbstractAccountsIT {

    @Autowired
    LedgerService ledger;
    @Autowired
    PlatformTransactionManager txManager;

    private OperationResult apply(String op, UUID acc, OperationType type, String amount) {
        return new TransactionTemplate(txManager).execute(s -> ledger.apply(op, acc, type, new BigDecimal(amount), "ref", null));
    }

    @Test
    void parallelDebitsNeverOverdraw() throws Exception {
        UUID acc = account("100");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OperationResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String op = "debit-" + i;
            results.add(pool.submit(() -> {
                start.await();
                return apply(op, acc, OperationType.DEBIT, "10");
            }));
        }
        start.countDown();
        long applied = 0;
        for (Future<OperationResult> f : results) {
            applied += f.get().applied() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(applied).isEqualTo(10);               // 100 / 10: ni más ni menos
        assertThat(balance(acc)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_operation WHERE outcome = 'REJECTED' AND reason_code = 'INSUFFICIENT_FUNDS'", Long.class))
                .isEqualTo(14);
    }

    @Test
    void sameOperationIdRacingInParallelMovesMoneyOnce() throws Exception {
        UUID acc = account("100");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OperationResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return apply("same-op", acc, OperationType.DEBIT, "30");
            }));
        }
        start.countDown();
        long fresh = 0;
        for (Future<OperationResult> f : results) {
            OperationResult r = f.get();
            assertThat(r.applied()).isTrue();
            fresh += r.replayed() ? 0 : 1;
        }
        pool.shutdown();

        assertThat(fresh).isEqualTo(1);                  // solo una ejecución real; el resto, reproducciones
        assertThat(balance(acc)).isEqualByComparingTo("70");
    }

    @Test
    void balanceCheckConstraintIsTheLastLineOfDefense() {
        UUID acc = account("5");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE account SET balance = -1 WHERE id = ?", acc))
                .hasMessageContaining("balance");
    }
}

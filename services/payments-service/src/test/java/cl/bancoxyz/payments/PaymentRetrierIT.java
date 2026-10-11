package cl.bancoxyz.payments;

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

import cl.bancoxyz.payments.saga.PaymentRetrier;

/** Reintento de pagos sin avance y de compensaciones a medias (se invoca a mano para ser determinista). */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/payments-service.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "platform.outbox.enabled=false",
        "management.zipkin.tracing.export.enabled=false", "INTERNAL_SERVICE_CLIENT_SECRET=internal-secret",
        "payments.retrier-interval=1h", "payments.pending-retry-after=1s", "payments.max-request-attempts=3",
        "resilience4j.retry.instances.accounts-compensation.wait-duration=20ms"})
class PaymentRetrierIT extends AbstractPaymentsIT {

    @Autowired
    PaymentRetrier retrier;

    private UUID insert(String status, int attempts, int ageSeconds, UUID from) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment (id, type, from_account_id, to_account_id, amount, status, request_attempts, requested_at, updated_at)
                VALUES (?, 'TRANSFER', ?, ?, ?, ?, ?, now() - make_interval(secs => ?), now() - make_interval(secs => ?))
                """, id, from, UUID.randomUUID(), new BigDecimal("10"), status, attempts, ageSeconds, ageSeconds);
        return id;
    }

    private long requestedEvents(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE topic = 'transactions.requested' AND msg_key = ?", Long.class, id.toString());
    }

    @Test
    void stalePendingPaymentsAreReEmittedAndFreshOnesAreLeftAlone() {
        UUID stale = insert("PENDING", 1, 60, UUID.randomUUID());
        UUID fresh = insert("PENDING", 1, 0, UUID.randomUUID());

        retrier.retryStalePayments();

        assertThat(requestedEvents(stale)).isEqualTo(1);
        assertThat(requestedEvents(fresh)).isZero();
        assertThat(jdbc.queryForObject("SELECT request_attempts FROM payment WHERE id = ?", Integer.class, stale)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT detail FROM payment_history WHERE payment_id = ? ORDER BY id DESC LIMIT 1", String.class, stale))
                .contains("Reintento #2");
    }

    @Test
    void afterTheMaximumAttemptsThePaymentIsFailedForManualReview() {
        UUID id = insert("PENDING", 3, 120, UUID.randomUUID());
        retrier.retryStalePayments();

        assertThat(jdbc.queryForMap("SELECT status, failure_code FROM payment WHERE id = ?", id))
                .containsEntry("status", "FAILED").containsEntry("failure_code", "TIMEOUT_REVIEW_REQUIRED");
        assertThat(requestedEvents(id)).isZero();
    }

    @Test
    void severalInstancesNeverReEmitTheSamePaymentTwice() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(insert("PENDING", 1, 60, UUID.randomUUID()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4); // 4 "instancias" ejecutando el reintentador a la vez
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                retrier.retryStalePayments();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : runs) {
            f.get();
        }
        pool.shutdown();

        for (UUID id : ids) {
            assertThat(requestedEvents(id)).as("eventos de %s", id).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT request_attempts FROM payment WHERE id = ?", Integer.class, id)).isEqualTo(2);
        }
    }

    @Test
    void anInterruptedCompensationIsResumedWhenAccountsIsBack() {
        UUID from = fake.account("0");
        UUID stuck = insert("COMPENSATING", 1, 60, from);

        fake.creditMode = "error";
        retrier.retryStalePayments();     // accounts sigue caído: no explota y el pago sigue COMPENSATING
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, stuck)).isEqualTo("COMPENSATING");

        jdbc.update("UPDATE payment SET updated_at = now() - interval '1 minute' WHERE id = ?", stuck);
        fake.creditMode = "ok";
        retrier.retryStalePayments();     // accounts volvió: se completa el reembolso
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, stuck)).isEqualTo("COMPENSATED");
        assertThat(fake.creditBodies.get(fake.creditBodies.size() - 1)).contains(stuck + ":REFUND");
    }
}

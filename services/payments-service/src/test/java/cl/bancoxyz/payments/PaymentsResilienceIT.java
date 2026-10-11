package cl.bancoxyz.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Fallbacks de Resilience4j de payments-service con la configuración REAL de infra/config-repo:
 * accounts caído → el pago se acepta PENDING (degradado) y no se pierde; customers caído → respuesta desde caché.
 */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/payments-service.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "platform.outbox.enabled=false", "payments.retrier-enabled=false",
        "management.zipkin.tracing.export.enabled=false", "INTERNAL_SERVICE_CLIENT_SECRET=internal-secret",
        "resilience4j.circuitbreaker.instances.accounts.wait-duration-in-open-state=1500ms",
        "resilience4j.circuitbreaker.instances.customers.wait-duration-in-open-state=1500ms",
        "resilience4j.timelimiter.instances.accounts.timeout-duration=700ms",
        "resilience4j.retry.instances.accounts.wait-duration=20ms", "resilience4j.retry.instances.customers.wait-duration=20ms"})
@AutoConfigureMockMvc
class PaymentsResilienceIT extends AbstractPaymentsIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    CircuitBreakerRegistry breakers;

    @BeforeEach
    void resetBreakers() {
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private ResultActions withdraw(UUID account) throws Exception {
        return mvc.perform(post("/payments/withdraw").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"" + account + "\",\"amount\":10}"));
    }

    @Test
    void accountsDownAcceptsThePaymentAsPendingDegradedAndKeepsItsEvent() throws Exception {
        UUID acc = fake.account("500");
        fake.accountsMode = "error";

        withdraw(acc).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.degraded").value(true)).andExpect(jsonPath("$.degradedReason").value("ACCOUNTS_UNAVAILABLE"));

        assertThat(fake.accountCalls.get()).isEqualTo(3); // Retry: 3 intentos antes del fallback
        // el pago NO se perdió: existe y su evento está en el outbox para procesarse cuando accounts vuelva
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE status = 'PENDING' AND degraded", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE topic = 'transactions.requested'", Long.class)).isEqualTo(1);
    }

    @Test
    void theCircuitOpensFailsFastAndRecovers() throws Exception {
        UUID acc = fake.account("500");
        fake.accountsMode = "error";
        withdraw(acc).andExpect(status().isAccepted());
        withdraw(acc).andExpect(status().isAccepted());
        assertThat(breakers.circuitBreaker("accounts").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = fake.accountCalls.get();
        withdraw(acc).andExpect(status().isAccepted()).andExpect(jsonPath("$.degraded").value(true)); // sin llamar a accounts
        assertThat(fake.accountCalls.get()).isEqualTo(callsBefore);
        mvc.perform(get("/actuator/circuitbreakers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreakers.accounts.state").value("OPEN"));

        fake.accountsMode = "ok";
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                withdraw(acc).andExpect(jsonPath("$.degraded").value(false)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE NOT degraded", Long.class)).isPositive();
    }

    @Test
    void slowAccountsIsCutByTheTimeLimiterAndDegrades() throws Exception {
        UUID acc = fake.account("500");
        fake.accountsDelayMs = 3000;
        long t0 = System.nanoTime();
        withdraw(acc).andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.degraded").value(true));
        assertThat(Duration.ofNanos(System.nanoTime() - t0).toMillis()).isLessThan(6_000); // 3 × 700 ms, no 3 × 3 s
    }

    @Test
    void customersDownAnswersFromTheLocalCacheWithTheDegradedFlag() throws Exception {
        UUID acc = fake.account("500");

        withdraw(acc).andExpect(status().isAccepted()).andExpect(jsonPath("$.degraded").value(false)); // llena la caché

        fake.customersMode = "error";
        withdraw(acc).andExpect(status().isAccepted()).andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradedReason").value("CUSTOMERS_UNAVAILABLE(CACHE)"));

        UUID neverSeen = fake.account("500"); // otro cliente, sin dato en caché
        withdraw(neverSeen).andExpect(status().isAccepted()).andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradedReason").value("CUSTOMERS_UNAVAILABLE(NONE)"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(3);
    }

    @Test
    void businessErrorsAreNotOutages() throws Exception {
        withdraw(UUID.randomUUID()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(fake.accountCalls.get()).isEqualTo(1); // un 404 no se reintenta
        assertThat(breakers.circuitBreaker("accounts").getMetrics().getNumberOfFailedCalls()).isZero();
    }
}

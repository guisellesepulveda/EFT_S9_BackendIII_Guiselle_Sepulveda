package cl.bancoxyz.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Bulkhead del alta de pagos: con capacidad 1, la solicitud concurrente excedente recibe 503 + Retry-After. */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/payments-service.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "platform.outbox.enabled=false", "payments.retrier-enabled=false",
        "management.zipkin.tracing.export.enabled=false", "INTERNAL_SERVICE_CLIENT_SECRET=internal-secret",
        "resilience4j.bulkhead.instances.payments.max-concurrent-calls=1",
        "resilience4j.bulkhead.instances.payments.max-wait-duration=0ms"})
@AutoConfigureMockMvc
class PaymentsBulkheadIT extends AbstractPaymentsIT {

    @Autowired
    MockMvc mvc;

    @Test
    void excessConcurrentRequestsAreShedWith503() throws Exception {
        UUID acc = fake.account("5000");
        fake.accountsDelayMs = 1200; // mantiene ocupado el único permiso
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/payments/withdraw").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + acc + "\",\"amount\":10}")).andReturn();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        String retryAfter = null;
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            codes.add(r.getResponse().getStatus());
            if (r.getResponse().getStatus() == 503) {
                retryAfter = r.getResponse().getHeader("Retry-After");
                assertThat(r.getResponse().getContentAsString()).contains("BULKHEAD_FULL");
            }
        }
        pool.shutdown();

        assertThat(codes).containsExactlyInAnyOrder(202, 503);
        assertThat(retryAfter).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(1);
    }
}

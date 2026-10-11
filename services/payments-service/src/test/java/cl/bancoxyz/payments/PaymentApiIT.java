package cl.bancoxyz.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/payments-service.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "platform.outbox.enabled=false", "payments.retrier-enabled=false",
        "management.zipkin.tracing.export.enabled=false", "INTERNAL_SERVICE_CLIENT_SECRET=internal-secret",
        "resilience4j.retry.instances.accounts.wait-duration=20ms", "resilience4j.retry.instances.customers.wait-duration=20ms"})
@AutoConfigureMockMvc
class PaymentApiIT extends AbstractPaymentsIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    private String transferBody(UUID from, UUID to, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amount\":" + amount + ",\"description\":\"test\"}";
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void transferIsAcceptedAsPendingWithItsRequestedEventInTheOutbox() throws Exception {
        UUID from = fake.account("1000");
        UUID to = fake.account("0");

        mvc.perform(post("/payments/transfer").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "250.75")))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andExpect(header().exists("X-Instance-Id"))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.type").value("TRANSFER")).andExpect(jsonPath("$.history.length()").value(1));

        assertThat(jdbc.queryForList("SELECT topic, event_type, msg_key FROM outbox_event")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("topic", "transactions.requested").containsEntry("event_type", "transaction.requested"));
        assertThat(fake.accountCalls.get()).isEqualTo(2); // origen y destino
    }

    @Test
    void depositAndWithdrawalShapeRules() throws Exception {
        UUID acc = fake.account("500");
        mvc.perform(post("/payments/deposit").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toAccountId\":\"" + acc + "\",\"amount\":100}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.type").value("DEPOSIT"));
        mvc.perform(post("/payments/withdraw").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + acc + "\",\"amount\":100}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.type").value("WITHDRAWAL"));
        // forma incorrecta
        mvc.perform(post("/payments/deposit").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + acc + "\",\"toAccountId\":\"" + acc + "\",\"amount\":100}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/payments/transfer").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(transferBody(acc, acc, "10")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidAmountsAreRejectedByBeanValidation() throws Exception {
        UUID acc = fake.account("500");
        for (String amount : new String[] {"0", "-1", "1.005", "null"}) {
            mvc.perform(post("/payments/withdraw").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fromAccountId\":\"" + acc + "\",\"amount\":" + amount + "}")).andExpect(status().isBadRequest());
        }
        assertThat(count("payment")).isZero();
    }

    @Test
    void businessRulesFailFastWithoutCreatingAPayment() throws Exception {
        UUID rich = fake.account("1000");
        UUID poor = fake.account("5");
        UUID closed = fake.account("0", "CLOSED", "ACTIVE");
        UUID inactiveCustomer = fake.account("1000", "OPEN", "INACTIVE");

        mvc.perform(post("/payments/withdraw").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + poor + "\",\"amount\":50}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(post("/payments/withdraw").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":50}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(post("/payments/deposit").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toAccountId\":\"" + closed + "\",\"amount\":50}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        mvc.perform(post("/payments/withdraw").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + inactiveCustomer + "\",\"amount\":50}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        mvc.perform(post("/payments/transfer").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(rich, UUID.randomUUID(), "10")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DESTINATION_NOT_FOUND"));
        mvc.perform(post("/payments/transfer").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(rich, closed, "10")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DESTINATION_CLOSED"));

        assertThat(count("payment")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    // ------------------------------------------------------------------ idempotencia
    @Test
    void sameIdempotencyKeyAndBodyReturnsTheSamePaymentWithoutDuplicating() throws Exception {
        UUID acc = fake.account("500");
        String body = "{\"fromAccountId\":\"" + acc + "\",\"amount\":100}";
        MvcResult first = mvc.perform(post("/payments/withdraw").with(WEB_WRITE).header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn();
        MvcResult second = mvc.perform(post("/payments/withdraw").with(WEB_WRITE).header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(mapper.readTree(second.getResponse().getContentAsString()).get("paymentId").asText())
                .isEqualTo(mapper.readTree(first.getResponse().getContentAsString()).get("paymentId").asText());
        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() throws Exception {
        UUID acc = fake.account("500");
        mvc.perform(post("/payments/withdraw").with(WEB_WRITE).header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fromAccountId\":\"" + acc + "\",\"amount\":100}")).andExpect(status().isAccepted());
        mvc.perform(post("/payments/withdraw").with(WEB_WRITE).header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromAccountId\":\"" + acc + "\",\"amount\":101}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("payment")).isEqualTo(1);
    }

    @Test
    void parallelRequestsWithTheSameKeyCreateExactlyOnePayment() throws Exception {
        UUID acc = fake.account("5000");
        String body = "{\"fromAccountId\":\"" + acc + "\",\"amount\":10}";
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            statuses.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/payments/withdraw").with(WEB_WRITE).header("Idempotency-Key", "race-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : statuses) {
            codes.add(f.get());
        }
        pool.shutdown();

        assertThat(codes).allMatch(c -> c == 202 || c == 200);
        assertThat(codes.stream().filter(c -> c == 202).count()).isEqualTo(1);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ seguridad y consultas
    @Test
    void writeScopeIsRequiredToCreatePayments() throws Exception {
        UUID acc = fake.account("500");
        String body = "{\"fromAccountId\":\"" + acc + "\",\"amount\":10}";
        mvc.perform(post("/payments/withdraw").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/withdraw").with(WEB_READ).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/payments/withdraw").with(scope("mobile:transfer")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        mvc.perform(post("/payments/withdraw").with(scope("atm:operate")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        assertThat(count("payment")).isEqualTo(2);
    }

    @Test
    void getAndListPayments() throws Exception {
        UUID from = fake.account("1000");
        UUID to = fake.account("0");
        MvcResult r = mvc.perform(post("/payments/transfer").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(from, to, "10"))).andReturn();
        String id = mapper.readTree(r.getResponse().getContentAsString()).get("paymentId").asText();

        mvc.perform(get("/payments/" + id).with(WEB_READ)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.history[0].status").value("PENDING"));
        mvc.perform(get("/payments?accountId=" + to).with(WEB_READ)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/payments/" + UUID.randomUUID()).with(WEB_READ)).andExpect(status().isNotFound());
        mvc.perform(get("/admin/payments/summary").with(WEB_READ)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/payments/summary").with(ADMIN)).andExpect(jsonPath("$.PENDING").value(1));
        mvc.perform(get("/admin/payments/stuck").with(ADMIN)).andExpect(jsonPath("$.length()").value(1));
    }
}

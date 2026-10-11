package cl.bancoxyz.bffatm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cl.bancoxyz.bffatm.domain.Repositories;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/** Operaciones del cajero contra una BD real (Testcontainers) y backends simulados. */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-atm.yml", "management.server.port=0",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "platform.outbox.enabled=false", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD,
        "bff.atm.limits.max-per-withdrawal=200000", "bff.atm.limits.daily-limit=500000",
        "bff.atm.confirmation-timeout=600ms", "bff.atm.poll-interval=50ms",
        "resilience4j.retry.instances.accounts.wait-duration=20ms", "resilience4j.retry.instances.payments.wait-duration=20ms"})
@AutoConfigureMockMvc
class AtmApiIT {

    static final FakeBackends fake = new FakeBackends();
    static final X509Certificate ATM_CERT = TlsTestCerts.clientCert("atm-0001.p12");

    @BeforeAll
    static void start() throws IOException {
        fake.start();
    }

    @AfterAll
    static void stop() throws IOException {
        fake.server.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("clients.accounts.url", fake::baseUrl);
        r.add("clients.customers.url", fake::baseUrl);
        r.add("clients.payments.url", fake::baseUrl);
        r.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Repositories repo;
    @Autowired
    PinService pins;
    @Autowired
    CircuitBreakerRegistry breakers;

    private static int seq = 1000;

    @BeforeEach
    void reset() {
        fake.reset();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // ------------------------------------------------------------------ utilidades
    /** Tarjeta nueva (cada test la suya: los límites y bloqueos son por tarjeta). */
    private String card(String pin) {
        String id = "40009999" + String.format("%08d", ++seq);
        repo.insertCard(id, UUID.randomUUID(), pins.hash(pin));
        return id;
    }

    private static RequestPostProcessor atm() {
        return request -> {
            request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {ATM_CERT});
            return request;
        };
    }

    private static RequestPostProcessor token(String... scopes) {
        return jwt().jwt(j -> j.subject("atm-client").tokenValue("atm-token").claim("channel", "atm"))
                .authorities(java.util.Arrays.stream(scopes).<org.springframework.security.core.GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    private static final RequestPostProcessor ATM_TOKEN = token("atm:operate");

    private ResultActions balance(String card, String pin) throws Exception {
        return mvc.perform(post("/atm/balance").with(atm()).with(ATM_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardId\":\"" + card + "\",\"pin\":\"" + pin + "\"}"));
    }

    private MockHttpServletRequestBuilder withdrawal(String key, String card, String pin, String amount) {
        MockHttpServletRequestBuilder b = post("/atm/withdrawals").with(atm()).with(ATM_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardId\":\"" + card + "\",\"pin\":\"" + pin + "\",\"amount\":" + amount + "}");
        return key == null ? b : b.header("Idempotency-Key", key);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private int alerts(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE topic = 'security.alerts' AND payload LIKE ?", Integer.class, "%" + type + "%");
    }

    private int audits(String operation, String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM atm_audit WHERE operation = ? AND outcome = ?", Integer.class, operation, outcome);
    }

    // ------------------------------------------------------------------ consultas
    @Test
    void balanceIsMinimalAndTheAccountNumberIsMasked() throws Exception {
        String card = card("1234");
        String body = balance(card, "1234").andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value("****0001")).andExpect(jsonPath("$.balance").value(7499.5))
                .andExpect(jsonPath("$.currency").value("CLP")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("customerId").doesNotContain("CL400000000001").doesNotContain(card);
        assertThat(audits("BALANCE", "OK")).isPositive();
    }

    @Test
    void lastMovementsAreCappedAtFiveWithOnlyDateTypeAndAmount() throws Exception {
        String card = card("1234");
        String body = mvc.perform(post("/atm/movements").with(atm()).with(ATM_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardId\":\"" + card + "\",\"pin\":\"1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].type").exists()).andExpect(jsonPath("$[0].amount").exists()).andExpect(jsonPath("$[0].at").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("dato interno").doesNotContain("balance_after").doesNotContain("operation_id");
    }

    @Test
    void aWrongPinAndAnUnknownCardLookTheSameAndNeverReachTheBackend() throws Exception {
        String card = card("1234");
        int before = alerts("UNKNOWN_CARD");
        String wrongPin = balance(card, "9999").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CARD_OR_PIN"))
                .andReturn().getResponse().getContentAsString();
        String unknown = balance("40000000000000", "1234").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CARD_OR_PIN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(unknown.replaceAll("\"traceId\":\"[0-9a-f]+\"", "")).isEqualTo(wrongPin.replaceAll("\"traceId\":\"[0-9a-f]+\"", ""));                 // sin enumeración de tarjetas
        assertThat(fake.calls("/accounts")).isZero();
        assertThat(alerts("UNKNOWN_CARD")).isEqualTo(before + 1);
    }

    @Test
    void threeWrongPinsLockTheCardEvenForTheRightPinAndRaiseAHighAlert() throws Exception {
        String card = card("1234");
        int locked = alerts("CARD_LOCKED");
        balance(card, "1111").andExpect(status().isUnauthorized());
        balance(card, "2222").andExpect(status().isUnauthorized());
        balance(card, "3333").andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("CARD_LOCKED"));   // el 3.º ya bloquea
        balance(card, "1234").andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("CARD_LOCKED"));
        assertThat(alerts("CARD_LOCKED")).isEqualTo(locked + 1);
        assertThat(audits("PIN_CHECK", "WRONG_PIN")).isGreaterThanOrEqualTo(2);
        assertThat(audits("PIN_CHECK", "CARD_LOCKED")).isPositive();
    }

    @Test
    void aSuccessfulPinResetsTheFailedAttempts() throws Exception {
        String card = card("1234");
        balance(card, "1111").andExpect(status().isUnauthorized());
        balance(card, "2222").andExpect(status().isUnauthorized());
        balance(card, "1234").andExpect(status().isOk());
        balance(card, "1111").andExpect(status().isUnauthorized());
        balance(card, "2222").andExpect(status().isUnauthorized());
        balance(card, "1234").andExpect(status().isOk());       // no se bloqueó: el contador se reinició
    }

    // ------------------------------------------------------------------ retiros
    @Test
    void anApprovedWithdrawalDispensesOnlyAfterPaymentsConfirmsIt() throws Exception {
        String card = card("1234");
        fake.paymentStatus = "COMPLETED";
        String key = key();
        mvc.perform(withdrawal(key, card, "1234", "20000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.dispense").value(true))
                .andExpect(jsonPath("$.reference").exists());
        assertThat(fake.withdrawKeys).containsExactly("atm-" + key);          // la clave del cajero viaja a payments
        assertThat(jdbc.queryForObject("SELECT status FROM atm_withdrawal WHERE idempotency_key = ?", String.class, key)).isEqualTo("APPROVED");
        assertThat(audits("WITHDRAWAL", "APPROVED")).isPositive();
    }

    @Test
    void withoutAnIdempotencyKeyTheWithdrawalIsRejectedBeforeAnythingHappens() throws Exception {
        String card = card("1234");
        mvc.perform(withdrawal(null, card, "1234", "20000")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(withdrawal("corta", card, "1234", "20000")).andExpect(status().isBadRequest());
        assertThat(fake.calls("/payments")).isZero();
    }

    @Test
    void retryingTheSameKeyNeverDuplicatesThePaymentAndReturnsTheSameDecision() throws Exception {
        String card = card("1234");
        String key = key();
        String first = mvc.perform(withdrawal(key, card, "1234", "20000")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(withdrawal(key, card, "1234", "20000")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(fake.paymentCreates.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atm_withdrawal WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithADifferentAmountOrCardIsRefused() throws Exception {
        String card = card("1234");
        String key = key();
        mvc.perform(withdrawal(key, card, "1234", "20000")).andExpect(status().isOk());
        mvc.perform(withdrawal(key, card, "1234", "30000")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        String other = card("1234");
        mvc.perform(withdrawal(key, other, "1234", "20000")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void perWithdrawalLimitAndDenominationAreEnforced() throws Exception {
        String card = card("1234");
        mvc.perform(withdrawal(key(), card, "1234", "200500")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));       // no es múltiplo de 1.000
        mvc.perform(withdrawal(key(), card, "1234", "210000")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
        mvc.perform(withdrawal(key(), card, "1234", "500")).andExpect(status().isBadRequest());   // bajo el mínimo (validación)
        assertThat(fake.calls("/payments")).isZero();
    }

    @Test
    void theDailyLimitIsPerCardAndCountsOnlyApprovedOrInFlightWithdrawals() throws Exception {
        String card = card("1234");
        mvc.perform(withdrawal(key(), card, "1234", "200000")).andExpect(status().isOk());
        mvc.perform(withdrawal(key(), card, "1234", "200000")).andExpect(status().isOk());
        mvc.perform(withdrawal(key(), card, "1234", "150000")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));
        mvc.perform(withdrawal(key(), card, "1234", "100000")).andExpect(status().isOk());      // 500.000 exactos: permitido
        assertThat(alerts("DAILY_LIMIT_EXCEEDED")).isPositive();
        String other = card("1234");
        mvc.perform(withdrawal(key(), other, "1234", "200000")).andExpect(status().isOk());      // otra tarjeta: su propio cupo
    }

    @Test
    void concurrentWithdrawalsCannotBeatTheDailyLimit() throws Exception {
        String card = card("1234");
        int threads = 6;                                           // 6 x 100.000 = 600.000 > 500.000
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String k = key();
                results.add(pool.submit(() -> mvc.perform(withdrawal(k, card, "1234", "100000")).andReturn().getResponse().getStatus()));
            }
            long approved = 0;
            for (var f : results) {
                if (f.get() == 200) {
                    approved++;
                }
            }
            assertThat(approved).isEqualTo(5);
            assertThat(repo.withdrawnToday(card)).isEqualByComparingTo(new BigDecimal("500000"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whenPaymentsDoesNotConfirmInTimeNothingIsDispensedAndTheStatusCanBePolled() throws Exception {
        String card = card("1234");
        fake.paymentStatus = "PENDING";
        String key = key();
        mvc.perform(withdrawal(key, card, "1234", "20000")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.dispense").value(false));
        mvc.perform(get("/atm/withdrawals/" + key).with(atm()).with(ATM_TOKEN)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.dispense").value(false));
        fake.paymentStatus = "COMPLETED";                                       // llega la confirmación
        mvc.perform(get("/atm/withdrawals/" + key).with(atm()).with(ATM_TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.dispense").value(true));
        assertThat(fake.paymentCreates.get()).isEqualTo(1);                      // nunca se pidió un segundo pago
    }

    @Test
    void aFailedPaymentIsDeclinedAndNeverDispenses() throws Exception {
        String card = card("1234");
        fake.paymentStatus = "FAILED";
        mvc.perform(withdrawal(key(), card, "1234", "20000")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("DECLINED")).andExpect(jsonPath("$.dispense").value(false));
    }

    @Test
    void aBusinessRejectionFromPaymentsIsDeclinedWithoutDetails() throws Exception {
        String card = card("1234");
        fake.withdrawMode = "insufficient";
        String body = mvc.perform(withdrawal(key(), card, "1234", "20000")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("DECLINED")).andExpect(jsonPath("$.dispense").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("INSUFFICIENT_FUNDS").doesNotContain("Saldo");   // el cajero no aprende el motivo interno
    }

    @Test
    void whenPaymentsIsDownTheAtmGetsASafeMessageAndTheSameKeyCanBeRetriedLater() throws Exception {
        String card = card("1234");
        fake.withdrawMode = "down";
        String key = key();
        mvc.perform(withdrawal(key, card, "1234", "20000")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.dispense").value(false)).andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Servicio temporalmente no disponible. No se dispensará dinero."));
        fake.withdrawMode = "ok";
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        mvc.perform(withdrawal(key, card, "1234", "20000")).andExpect(status().isOk()).andExpect(jsonPath("$.dispense").value(true));
        assertThat(fake.paymentCreates.get()).isEqualTo(1);
    }

    @Test
    void whenAccountsIsDownTheBalanceIsNotAvailableAndNoInternalDetailLeaks() throws Exception {
        String card = card("1234");
        fake.accountsDown = true;
        String body = balance(card, "1234").andExpect(status().is5xxServerError()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Feign").doesNotContain("Exception").doesNotContain("localhost");
    }

    // ------------------------------------------------------------------ emisión simulada de tarjetas
    @Test
    void cardsAreIssuedOnlyByTheInternalClientAndThePinIsStoredHashed() throws Exception {
        String card = "40001111" + String.format("%08d", ++seq);
        String payload = "{\"cardId\":\"" + card + "\",\"accountId\":\"" + UUID.randomUUID() + "\",\"pin\":\"4321\"}";
        mvc.perform(post("/atm/internal/cards").with(atm()).with(token("internal")).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.card").value("****" + card.substring(card.length() - 4)));
        String hash = jdbc.queryForObject("SELECT pin_hash FROM atm_card WHERE card_id = ?", String.class, card);
        assertThat(hash).startsWith("$2").doesNotContain("4321");
        mvc.perform(post("/atm/internal/cards").with(atm()).with(ATM_TOKEN).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM atm_card WHERE card_id = ?", card);
        assertThat(row.get("pin_hash").toString()).isEqualTo(hash);
    }

    @Test
    void theAuditTrailRecordsEveryOperationWithTheAtmAndAMaskedCard() throws Exception {
        String card = card("1234");
        balance(card, "1234").andExpect(status().isOk());
        mvc.perform(withdrawal(key(), card, "1234", "20000")).andExpect(status().isOk());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM atm_audit WHERE card_ref = ? ORDER BY id", "****" + card.substring(card.length() - 4));
        assertThat(rows).extracting(r -> r.get("operation")).contains("BALANCE", "WITHDRAWAL");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("atm_id")).isEqualTo("ATM-0001"));
        assertThat(jdbc.queryForList("SELECT detail FROM atm_audit").toString()).doesNotContain(card);
    }
}

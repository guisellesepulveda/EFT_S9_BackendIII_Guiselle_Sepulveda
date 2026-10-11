package cl.bancoxyz.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.accounts.clients.CustomerGateway;
import cl.bancoxyz.accounts.clients.CustomerView;
import cl.bancoxyz.platform.web.ApiException;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "platform.outbox.enabled=false",
        "management.zipkin.tracing.export.enabled=false"})
@AutoConfigureMockMvc
class AccountApiIT extends AbstractAccountsIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    CustomerGateway customers;

    private UUID openFor(UUID customerId) throws Exception {
        when(customers.find(customerId)).thenReturn(new CustomerView(customerId, "ACTIVE", "Ana", "Pérez"));
        MvcResult r = mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId + "\",\"type\":\"SAVINGS\"}")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void openingAnAccountVerifiesTheCustomerAndEmitsALifecycleEvent() throws Exception {
        UUID customerId = UUID.randomUUID();
        when(customers.find(customerId)).thenReturn(new CustomerView(customerId, "ACTIVE", "Ana", "Pérez"));

        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"type\":\"CHECKING\"}"))
                .andExpect(status().isCreated()).andExpect(header().exists("X-Instance-Id"))
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.number").value(org.hamcrest.Matchers.startsWith("CL")));

        assertThat(jdbc.queryForList("SELECT event_type, topic, msg_key FROM outbox_event"))
                .singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("event_type", "account.opened").containsEntry("topic", "accounts.lifecycle")
                            .containsEntry("msg_key", customerId.toString());
                });
    }

    @Test
    void inactiveOrMissingCustomersCannotOpenAccounts() throws Exception {
        UUID inactive = UUID.randomUUID();
        when(customers.find(inactive)).thenReturn(new CustomerView(inactive, "INACTIVE", "X", "Y"));
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + inactive + "\",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));

        UUID ghost = UUID.randomUUID();
        when(customers.find(ghost)).thenThrow(ApiException.unprocessable("CUSTOMER_NOT_FOUND", "no existe"));
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + ghost + "\",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Long.class)).isZero();
    }

    @Test
    void customersDownMeansA503WithAProblemDetail() throws Exception {
        UUID c = UUID.randomUUID();
        when(customers.find(any())).thenThrow(ApiException.unavailable("CUSTOMERS_UNAVAILABLE", "caído"));
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + c + "\",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CUSTOMERS_UNAVAILABLE"));
    }

    @Test
    void validationErrorsAreProblemDetails() throws Exception {
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"LOAN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/accounts").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Validación fallida"));
    }

    @Test
    void balanceAndLookupByCustomer() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID a1 = account(customerId, "1500.50", "OPEN");
        account(customerId, "0", "OPEN");
        account("10"); // de otro cliente

        mvc.perform(get("/accounts/" + a1 + "/balance").with(WEB_READ)).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1500.50)).andExpect(jsonPath("$.currency").value("CLP"));
        mvc.perform(get("/accounts?customerId=" + customerId).with(WEB_READ)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/accounts/" + UUID.randomUUID()).with(WEB_READ)).andExpect(status().isNotFound());
    }

    @Test
    void closeRequiresZeroBalanceAndIsReportedOnce() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID withMoney = account(customerId, "100", "OPEN");
        mvc.perform(patch("/accounts/" + withMoney + "/close").with(WEB_WRITE)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_EMPTY"));

        UUID empty = openFor(customerId);
        mvc.perform(patch("/accounts/" + empty + "/close").with(WEB_WRITE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.closedAt").exists());
        mvc.perform(patch("/accounts/" + empty + "/close").with(WEB_WRITE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CLOSED"));
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event ORDER BY id", String.class))
                .containsExactly("account.opened", "account.closed");
    }

    // ------------------------------------------------------------------ operaciones internas
    private String op(String id, String amount) {
        return "{\"operationId\":\"" + id + "\",\"amount\":" + amount + ",\"reference\":\"ref-1\"}";
    }

    @Test
    void internalDebitAndCreditAreIdempotentByOperationId() throws Exception {
        UUID acc = account("100");
        mvc.perform(post("/accounts/" + acc + "/debit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("op-1", "30.25")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balanceAfter").value(69.75)).andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/accounts/" + acc + "/debit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("op-1", "30.25")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/accounts/" + acc + "/credit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("op-2", "10")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balanceAfter").value(79.75));
        assertThat(balance(acc)).isEqualByComparingTo("79.75");
    }

    @Test
    void rejectedOperationsKeepReturningTheSameAnswer() throws Exception {
        UUID acc = account("50");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/accounts/" + acc + "/debit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("big", "80")))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        }
        assertThat(balance(acc)).isEqualByComparingTo("50");

        UUID closed = account(UUID.randomUUID(), "0", "CLOSED");
        mvc.perform(post("/accounts/" + closed + "/credit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("c1", "5")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        mvc.perform(post("/accounts/" + UUID.randomUUID() + "/credit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("c2", "5")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void invalidAmountsAreRejectedByValidation() throws Exception {
        UUID acc = account("50");
        for (String amount : new String[] {"0", "-5", "1.005", "\"x\""}) {
            mvc.perform(post("/accounts/" + acc + "/debit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("v", amount)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------ seguridad
    @Test
    void channelScopesCannotMoveMoneyDirectlyOnlyTheInternalClientCan() throws Exception {
        UUID acc = account("50");
        for (var token : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {WEB_WRITE, scope("mobile:transfer"), scope("atm:operate")}) {
            mvc.perform(post("/accounts/" + acc + "/debit").with(token).contentType(MediaType.APPLICATION_JSON).content(op("x", "1")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/accounts/" + acc + "/debit")).andExpect(status().isUnauthorized());
        assertThat(balance(acc)).isEqualByComparingTo("50");
    }

    @Test
    void readScopesAndAdminRole() throws Exception {
        UUID acc = account("1");
        mvc.perform(get("/accounts/" + acc).with(scope("mobile:read"))).andExpect(status().isOk());
        mvc.perform(get("/accounts/" + acc).with(scope("web:write"))).andExpect(status().isForbidden()); // escribir no implica leer
        mvc.perform(get("/accounts/" + acc).with(scope("openid"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/accounts").with(WEB_READ)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/accounts").with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/admin/outbox").with(ADMIN)).andExpect(jsonPath("$.pending").value(0));
    }

    @Test
    void movementsListsAppliedOperationsNewestFirst() throws Exception {
        UUID acc = account("100");
        mvc.perform(post("/accounts/" + acc + "/debit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("m1", "10")));
        mvc.perform(post("/accounts/" + acc + "/credit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("m2", "5")));
        mvc.perform(post("/accounts/" + acc + "/debit").with(INTERNAL).contentType(MediaType.APPLICATION_JSON).content(op("m3", "9999"))); // rechazada
        mvc.perform(get("/accounts/" + acc + "/movements?limit=5").with(WEB_READ)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].op_type").value("CREDIT"))
                .andExpect(jsonPath("$[1].movement_status").value("PENDING"));
    }
}

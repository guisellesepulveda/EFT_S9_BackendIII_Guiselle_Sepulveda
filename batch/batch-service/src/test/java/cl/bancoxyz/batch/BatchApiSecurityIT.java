package cl.bancoxyz.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

/** POST /api/batch/jobs/{jobName} exige el rol BATCH_ADMIN (claim "roles" del JWT). */
@AutoConfigureMockMvc
class BatchApiSecurityIT extends AbstractIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("admin")).thenReturn(jwt("admin", List.of("BATCH_ADMIN")));
        when(jwtDecoder.decode("teller")).thenReturn(jwt("teller", List.of("TELLER")));
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue(subject).header("alg", "none").subject(subject).claim("roles", roles)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    private String body() throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("parameters",
                java.util.Map.of("inputFile", defaultInput(INTEREST).toString(), "periodo", "TRIMESTRAL")));
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mvc.perform(post("/api/batch/jobs/monthlyInterestJob")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutBatchAdminRoleIs403() throws Exception {
        mvc.perform(post("/api/batch/jobs/monthlyInterestJob").header("Authorization", "Bearer teller"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/batch/executions/1").header("Authorization", "Bearer teller"))
                .andExpect(status().isForbidden());
    }

    @Test
    void batchAdminLaunchesJobAndPollsItsExecution() throws Exception {
        MvcResult res = mvc.perform(post("/api/batch/jobs/monthlyInterestJob").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("monthlyInterestJob"))
                .andExpect(jsonPath("$.parameters.periodo").value("TRIMESTRAL"))
                .andReturn();
        long id = mapper.readTree(res.getResponse().getContentAsString()).get("executionId").asLong();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            MvcResult poll = mvc.perform(get("/api/batch/executions/" + id).header("Authorization", "Bearer admin"))
                    .andExpect(status().isOk()).andReturn();
            assertThat(mapper.readTree(poll.getResponse().getContentAsString()).get("status").asText()).isEqualTo("COMPLETED");
        });

        mvc.perform(get("/api/batch/executions/" + id + "/rejected?limit=5").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].reason_code").exists());
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        mvc.perform(post("/api/batch/jobs/noSuchJob").header("Authorization", "Bearer admin"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Job desconocido: noSuchJob"));
        mvc.perform(post("/api/batch/jobs/monthlyInterestJob").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parameters\":{\"periodo\":\"ANUAL\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/batch/executions/99999").header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

package cl.bancoxyz.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "platform.kafka.topics-enabled=false",
        "spring.kafka.listener.auto-startup=false", "management.zipkin.tracing.export.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureObservability // en tests Boot desactiva los exportadores de métricas; aquí se prueba /actuator/prometheus
class CustomerApiIT {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    static RequestPostProcessor scope(String... scopes) {
        return jwt().authorities(java.util.Arrays.stream(scopes)
                .<org.springframework.security.core.GrantedAuthority>map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
    }

    static final RequestPostProcessor WEB_WRITE = scope("web:write");
    static final RequestPostProcessor WEB_READ = scope("web:read");
    static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE customer CASCADE");
    }

    private static String rut(int n) {
        return String.format("%08d-%d", 10_000_000 + n, n % 10);
    }

    private String body(int n) {
        return """
                {"documentNumber":"%s","firstName":"Ana %d","lastName":"Pérez","email":"ana%d@example.com","phone":"+56912345678"}
                """.formatted(rut(n), n, n);
    }

    private UUID create(int n) throws Exception {
        MvcResult r = mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(body(n)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void createAndGetCustomer() throws Exception {
        MvcResult r = mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(body(1)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/customers/")))
                .andExpect(header().exists("X-Instance-Id"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").value("ana1@example.com")).andReturn();
        String id = mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/customers/" + id).with(WEB_READ)).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentNumber").value(rut(1)));
    }

    @Test
    void invalidBodyReturnsProblemDetailWithFieldErrors() throws Exception {
        mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentNumber\":\"123\",\"firstName\":\"\",\"lastName\":\"X\",\"email\":\"no-es-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Validación fallida"))
                .andExpect(jsonPath("$.errors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    void duplicatesAreConflicts() throws Exception {
        create(2);
        mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(body(2)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"));
        String sameEmail = body(3).replace("ana3@example.com", "ana2@example.com");
        mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(sameEmail))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void unknownCustomerIsA404ProblemDetail() throws Exception {
        mvc.perform(get("/customers/" + UUID.randomUUID()).with(WEB_READ)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateChangesContactDataButNeverTheDocument() throws Exception {
        UUID id = create(4);
        String changed = body(4).replace("Ana 4", "Ana María").replace("+56912345678", "+56987654321");
        mvc.perform(put("/customers/" + id).with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Ana María"))
                .andExpect(jsonPath("$.phone").value("+56987654321"));
        mvc.perform(put("/customers/" + id).with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content(body(5).replace("ana5@", "ana4@")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
    }

    @Test
    void listIsPaginatedAndFilterableByStatus() throws Exception {
        UUID first = create(10);
        create(11);
        create(12);
        mvc.perform(patch("/admin/customers/" + first + "/deactivate").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mvc.perform(get("/customers?size=2").with(WEB_READ)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/customers?status=ACTIVE").with(WEB_READ)).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/customers?status=INACTIVE").with(WEB_READ)).andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void profileStartsEmpty() throws Exception {
        UUID id = create(20);
        mvc.perform(get("/customers/" + id + "/profile").with(WEB_READ)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ana 20 Pérez")).andExpect(jsonPath("$.accountsOpen").value(0))
                .andExpect(jsonPath("$.accountsClosed").value(0));
    }

    // ------------------------------------------------------------------ OAuth2: scopes y roles
    @Test
    void withoutTokenIs401() throws Exception {
        mvc.perform(get("/customers")).andExpect(status().isUnauthorized());
    }

    @Test
    void scopesDecideReadVersusWrite() throws Exception {
        mvc.perform(post("/customers").with(WEB_READ).contentType(MediaType.APPLICATION_JSON).content(body(30)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/customers").with(scope("internal"))).andExpect(status().isOk());
        mvc.perform(get("/customers").with(scope("openid"))).andExpect(status().isForbidden());
        mvc.perform(get("/customers").with(scope("atm:operate"))).andExpect(status().isOk());
    }

    @Test
    void adminEndpointsRequireTheAdminRoleNotJustAScope() throws Exception {
        UUID id = create(31);
        mvc.perform(delete("/admin/customers/" + id).with(WEB_WRITE)).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/customers/" + id).with(scope("internal"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/security-alerts").with(WEB_READ)).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/customers/" + id).with(ADMIN)).andExpect(status().isNoContent());
        mvc.perform(get("/admin/security-alerts").with(ADMIN)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Long.class)).isZero();
    }

    // ------------------------------------------------------------------ operabilidad
    @Test
    void exposesOpenApiHealthAndPrometheus() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/customers/{id}/profile']").exists());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ vínculo con el usuario de inicio de sesión
    @Test
    void aCustomerCanBeLinkedToALoginUserAndFoundByIt() throws Exception {
        String body = body(40).replace("\"phone\"", "\"username\":\"customer40\",\"phone\"");
        mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("customer40"));

        mvc.perform(get("/customers/by-username/customer40").with(WEB_READ)).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentNumber").value(rut(40)));
        mvc.perform(get("/customers/by-username/nadie").with(WEB_READ)).andExpect(status().isNotFound());

        String other = body(41).replace("\"phone\"", "\"username\":\"customer40\",\"phone\"");
        mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(other))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"));
        mvc.perform(post("/customers").with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON)
                        .content(body(42).replace("\"phone\"", "\"username\":\"MAYUS Inválido\",\"phone\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnlinkedCustomerCanBeLinkedLaterButNeverRelinked() throws Exception {
        UUID id = create(43);
        String linked = body(43).replace("\"phone\"", "\"username\":\"later43\",\"phone\"");
        mvc.perform(put("/customers/" + id).with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(linked))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("later43"));
        String changed = body(43).replace("\"phone\"", "\"username\":\"otro43\",\"phone\"");
        mvc.perform(put("/customers/" + id).with(WEB_WRITE).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("USERNAME_IMMUTABLE"));
    }
}

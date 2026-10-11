package cl.bancoxyz.bffatm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Contrato OpenAPI VERSIONADO (docs/openapi/bff-atm.json): permite al equipo de frontend trabajar en paralelo contra un mock
 * (scripts/openapi_mock.py). Si el código cambia el contrato, este test falla hasta que se actualice el archivo:
 *   mvn -pl bff/bff-atm verify -Dit.test=OpenApiContractIT -Dopenapi.update=true
 */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-atm.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD, "platform.outbox.enabled=false", "management.server.port=0"})
@AutoConfigureMockMvc
class OpenApiContractIT {

    static final Path CONTRACT = Path.of("../../docs/openapi/bff-atm.json").toAbsolutePath().normalize();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("clients.accounts.url", () -> "http://localhost:9");
        r.add("clients.customers.url", () -> "http://localhost:9");
        r.add("clients.payments.url", () -> "http://localhost:9");
        r.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        r.add("spring.ssl.bundle.jks.bff-tls.keystore.location", TlsTestCerts::keystore);
        r.add("spring.ssl.bundle.jks.bff-tls.truststore.location", TlsTestCerts::truststore);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void committedContractMatchesTheRunningApi() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        JsonNode node = mapper.readTree(raw);
        ((ObjectNode) node).putArray("servers").addObject().put("url", "https://localhost:8445").put("description", "BFF ATM (desarrollo)");
        // normaliza el orden de claves para que el archivo sea estable
        String generated = mapper.writeValueAsString(mapper.treeToValue(node, java.util.Map.class)) + "\n";

        assertThat(node.get("paths").fieldNames()).toIterable().contains("/atm/balance", "/atm/withdrawals", "/atm/movements");
        if (Boolean.getBoolean("openapi.update") || !Files.exists(CONTRACT)) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, generated);
        }
        assertThat(Files.readString(CONTRACT)).as("docs/openapi/bff-atm.json desactualizado: ejecute con -Dopenapi.update=true")
                .isEqualTo(generated);
    }
}

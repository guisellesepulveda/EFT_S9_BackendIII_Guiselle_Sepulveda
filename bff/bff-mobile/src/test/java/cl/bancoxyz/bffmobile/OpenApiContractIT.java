package cl.bancoxyz.bffmobile;

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
 * Contrato OpenAPI VERSIONADO (docs/openapi/bff-mobile.json): permite al equipo de frontend trabajar en paralelo contra un mock
 * (scripts/openapi_mock.py). Si el código cambia el contrato, este test falla hasta que se actualice el archivo:
 *   mvn -pl bff/bff-mobile verify -Dit.test=OpenApiContractIT -Dopenapi.update=true
 */
@SpringBootTest(properties = {
        "spring.config.import=file:../../infra/config-repo/bff-mobile.yml",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "management.zipkin.tracing.export.enabled=false",
        "MOBILE_CLIENT_SECRET=mobile-secret", "KEYSTORE_PASSWORD=" + TlsTestCerts.KEYSTORE_PASSWORD,
        "TRUSTSTORE_PASSWORD=" + TlsTestCerts.TRUSTSTORE_PASSWORD})
@AutoConfigureMockMvc
class OpenApiContractIT {

    static final Path CONTRACT = Path.of("../../docs/openapi/bff-mobile.json").toAbsolutePath().normalize();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
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
        ((ObjectNode) node).putArray("servers").addObject().put("url", "https://localhost:8444").put("description", "BFF móvil (desarrollo)");
        // normaliza el orden de claves para que el archivo sea estable
        String generated = mapper.writeValueAsString(mapper.treeToValue(node, java.util.Map.class)) + "\n";

        assertThat(node.get("paths").fieldNames()).toIterable().contains("/mobile/accounts", "/mobile/transfers", "/mobile/auth/token");
        if (Boolean.getBoolean("openapi.update") || !Files.exists(CONTRACT)) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, generated);
        }
        assertThat(Files.readString(CONTRACT)).as("docs/openapi/bff-mobile.json desactualizado: ejecute con -Dopenapi.update=true")
                .isEqualTo(generated);
    }
}

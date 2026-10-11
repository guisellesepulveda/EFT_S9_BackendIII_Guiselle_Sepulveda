package cl.bancoxyz.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Sirve la configuración REAL de infra/config-repo, protegida y por perfil. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"CONFIG_SERVER_PASSWORD=test-secret", "CONFIG_SERVER_USER=config"})
class ConfigServerIT {

    @DynamicPropertySource
    static void repo(DynamicPropertyRegistry r) {
        r.add("spring.cloud.config.server.native.search-locations",
                () -> "file:" + Path.of("../config-repo").toAbsolutePath().normalize());
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void rejectsAnonymousClients() {
        ResponseEntity<String> r = rest.getForEntity("/batch-service/default", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void servesServiceConfigurationToAuthenticatedClients() {
        ResponseEntity<String> r = rest.withBasicAuth("config", "test-secret").getForEntity("/batch-service/default", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("batch-service").contains("chunk-size");
    }

    @Test
    void profilesOverrideTheSharedDefaults() {
        var auth = rest.withBasicAuth("config", "test-secret");
        String local = auth.getForObject("/accounts-service/local", String.class);
        String docker = auth.getForObject("/accounts-service/docker", String.class);
        String aws = auth.getForObject("/accounts-service/aws", String.class);
        assertThat(local).contains("localhost");
        assertThat(docker).contains("postgres");
        assertThat(aws).contains("KAFKA_BOOTSTRAP_SERVERS").contains("POSTGRES_HOST").contains("logstash");   // endpoints configurables por entorno
    }

    @Test
    void healthIsPublic() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

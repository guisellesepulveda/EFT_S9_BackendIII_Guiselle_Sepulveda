package cl.bancoxyz.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"EUREKA_PASSWORD=test-secret", "EUREKA_USER=eureka"})
class DiscoveryServerIT {

    @Autowired
    TestRestTemplate rest;

    @Test
    void dashboardAndRegistryRequireCredentials() {
        assertThat(rest.getForEntity("/", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/eureka/apps", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authenticatedClientSeesDashboardAndAppsApi() {
        var auth = rest.withBasicAuth("eureka", "test-secret");
        var dashboard = auth.getForEntity("/", String.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dashboard.getBody()).contains("Instances currently registered with Eureka");
        assertThat(auth.getForEntity("/eureka/apps", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthIsPublic() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

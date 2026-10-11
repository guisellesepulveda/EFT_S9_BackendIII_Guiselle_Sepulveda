package cl.bancoxyz.accounts;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Contenedores compartidos por toda la JVM de tests; Kafka solo arranca si un test lo pide. */
final class TestContainers {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300");
    private static KafkaContainer kafka;

    static {
        POSTGRES.start();
    }

    private TestContainers() {
    }

    static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
            kafka.start();
        }
        return kafka;
    }
}

package cl.bancoxyz.bffatm;

import org.testcontainers.containers.PostgreSQLContainer;

/** PostgreSQL compartido por toda la JVM de tests (la base propia del cajero, con Flyway real). */
final class TestContainers {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }

    private TestContainers() {
    }
}

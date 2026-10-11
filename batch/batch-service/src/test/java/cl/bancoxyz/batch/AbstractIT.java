package cl.bancoxyz.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de los tests de integración: un PostgreSQL real (Testcontainers) compartido por toda la JVM y
 * limpieza de tablas entre tests. Las subclases añaden/cambian propiedades con @TestPropertySource.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "batch.events.enabled=false",
        "batch.auto-retry.enabled=false",
        "spring.datasource.hikari.minimum-idle=2",
        "batch.chunk-size=50",
        "batch.backoff.initial=10ms",
        "batch.backoff.max=50ms"
})
public abstract class AbstractIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300"); // varios contextos Spring cacheados, cada uno con su pool
    static final Path OUTPUT_DIR;

    static {
        POSTGRES.start();
        try {
            OUTPUT_DIR = Files.createTempDirectory("batch-it-out");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("batch.output-dir", OUTPUT_DIR::toString);
    }

    public static final String DAILY = "dailyTransactionReportJob";
    public static final String INTEREST = "monthlyInterestJob";
    public static final String ANNUAL = "annualStatementJob";

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JobLauncher jobLauncher; // síncrono: el test espera el resultado
    @Autowired
    protected ApplicationContext ctx;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("""
                TRUNCATE batch_rejected_record, job_execution_report, daily_transaction, daily_transaction_summary,
                         transaction_anomaly, interest_calculation, account_balance, annual_transaction,
                         annual_statement, batch_step_execution_context, batch_step_execution,
                         batch_job_execution_context, batch_job_execution_params, batch_job_execution,
                         batch_job_instance RESTART IDENTITY CASCADE
                """);
    }

    // ------------------------------------------------------------------ ejecución de jobs
    protected static Path legacy(String file) {
        return Path.of("../../data/legacy").resolve(file).toAbsolutePath().normalize();
    }

    protected static Path defaultInput(String jobName) {
        return legacy(switch (jobName) {
            case DAILY -> "movimientos_financieros_diarios.csv";
            case INTEREST -> "intereses_trimestrales.csv";
            default -> "estados_financieros_anuales.csv";
        });
    }

    protected JobParametersBuilder params(String jobName, Path input) {
        JobParametersBuilder b = new JobParametersBuilder()
                .addString("runId", UUID.randomUUID().toString(), true)
                .addString("inputFile", input.toString(), true);
        if (INTEREST.equals(jobName)) {
            b.addString("periodo", "MENSUAL", true);
        }
        return b;
    }

    protected JobExecution run(String jobName, JobParameters parameters) throws Exception {
        return jobLauncher.run(ctx.getBean(jobName, Job.class), parameters);
    }

    protected JobExecution run(String jobName) throws Exception {
        return run(jobName, params(jobName, defaultInput(jobName)).toJobParameters());
    }

    protected static StepExecution step(JobExecution je, String name) {
        return je.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No hay step " + name + " en " + je.getStepExecutions()));
    }

    protected static void assertCompleted(JobExecution je) {
        if (je.getStatus() != BatchStatus.COMPLETED) {
            throw new AssertionError("Se esperaba COMPLETED pero fue " + je.getStatus() + ": "
                    + je.getExitStatus().getExitDescription());
        }
    }

    protected long instanceId(JobExecution je) {
        return je.getJobInstance().getInstanceId();
    }

    protected long countRejected(JobExecution je, String disposition) {
        return jdbc.queryForObject("SELECT count(*) FROM batch_rejected_record WHERE job_instance_id = ? AND disposition = ?",
                Long.class, instanceId(je), disposition);
    }

    // ------------------------------------------------------------------ comparación de tablas
    /** Normaliza un decimal para comparar sin depender de la escala (8040.00 == 8040). */
    protected static String norm(Object value) {
        if (value instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        String s = String.valueOf(value);
        try {
            return new BigDecimal(s).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return s;
        }
    }

    protected static List<Map<String, String>> expectedCsv(String name) {
        try (var in = AbstractIT.class.getResourceAsStream("/equivalence/" + name)) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            String[] header = lines.get(0).split(",");
            List<Map<String, String>> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] c = line.split(",", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++) {
                    row.put(header[i], c[i]);
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package cl.bancoxyz.batch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Benchmark 1 hilo vs N hilos/particiones sobre CSV sintéticos grandes (scripts/generate_synthetic_csv.py).
 * Solo corre con -Dbenchmark=true (ver scripts/benchmark-batch.sh) y escribe evidencias/local/batch_benchmark.md.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class BatchBenchmarkIT {

    record Result(String job, String config, long jobMs, long stepMs, long read, long written, long skipped, long filtered) {
        double rowsPerSecond() {
            return stepMs == 0 ? 0 : read * 1000.0 / stepMs;
        }
    }

    static PostgreSQLContainer<?> pg;
    static Path dataDir;
    static Path outDir;
    static final int REPEATS = Integer.getInteger("benchmark.repeats", 2);
    static final List<String> sections = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c", "shared_buffers=256MB",
                "-c", "max_connections=200", "-c", "synchronous_commit=on");
        pg.start();
        dataDir = Path.of(System.getProperty("benchmark.dir", "../../data/synthetic")).toAbsolutePath().normalize();
        outDir = Files.createTempDirectory("batch-bench-out");
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    private Result run(String job, String file, String label, String... props) throws Exception {
        List<String> p = new ArrayList<>(List.of(
                "spring.datasource.url=" + pg.getJdbcUrl(), "spring.datasource.username=" + pg.getUsername(),
                "spring.datasource.password=" + pg.getPassword(), "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "batch.events.enabled=false", "batch.auto-retry.enabled=false", "batch.skip-limit=100000000",
                "batch.output-dir=" + outDir, "spring.main.banner-mode=off", "logging.level.root=WARN",
                "logging.level.cl.bancoxyz.batch=WARN", "logging.level.org.springframework.batch=WARN"));
        p.addAll(List.of(props));
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BatchServiceApplication.class)
                .web(WebApplicationType.NONE).run(p.stream().map(x -> "--" + x).toArray(String[]::new))) {
            ctx.getBean(JdbcTemplate.class).execute("""
                    TRUNCATE batch_rejected_record, job_execution_report, daily_transaction, daily_transaction_summary,
                             transaction_anomaly, interest_calculation, account_balance, annual_transaction,
                             annual_statement, batch_step_execution_context, batch_step_execution,
                             batch_job_execution_context, batch_job_execution_params, batch_job_execution,
                             batch_job_instance RESTART IDENTITY CASCADE
                    """);
            JobParametersBuilder jp = new JobParametersBuilder()
                    .addString("runId", UUID.randomUUID().toString(), true)
                    .addString("inputFile", dataDir.resolve(file).toString(), true);
            if (job.equals("monthlyInterestJob")) {
                jp.addString("periodo", "MENSUAL", true);
            }
            long t0 = System.nanoTime();
            JobExecution je = ctx.getBean(JobLauncher.class).run(ctx.getBean(job, Job.class), jp.toJobParameters());
            long jobMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            if (!je.getStatus().toString().equals("COMPLETED")) {
                throw new IllegalStateException(job + " terminó " + je.getStatus() + ": " + je.getExitStatus());
            }
            String mainStep = switch (job) {
                case "dailyTransactionReportJob" -> "loadMovementsStep";
                case "monthlyInterestJob" -> "calculateInterestStep";
                default -> "loadTransactionsStep";
            };
            StepExecution se = je.getStepExecutions().stream().filter(s -> s.getStepName().equals(mainStep)).findFirst().orElseThrow();
            long stepMs = Duration.between(se.getStartTime(), se.getEndTime()).toMillis();
            Result r = new Result(job, label, jobMs, stepMs, se.getReadCount(), se.getWriteCount(), se.getSkipCount(), se.getFilterCount());
            System.out.printf(Locale.ROOT, "[BENCH] %-26s %-28s job=%6d ms step=%6d ms  %d filas/s%n", job, label, jobMs, stepMs, (long) r.rowsPerSecond());
            return r;
        }
    }

    private Result average(String job, String file, String label, String... props) throws Exception {
        List<Result> rs = new ArrayList<>();
        for (int i = 0; i < REPEATS; i++) {
            rs.add(run(job, file, label, props));
        }
        Result f = rs.get(0);
        return new Result(job, label, (long) rs.stream().mapToLong(Result::jobMs).average().orElse(0),
                (long) rs.stream().mapToLong(Result::stepMs).average().orElse(0), f.read, f.written, f.skipped, f.filtered);
    }

    private static String table(String title, String note, List<Result> rows) {
        StringBuilder sb = new StringBuilder("### ").append(title).append("\n\n").append(note).append("\n\n");
        sb.append("| Configuración | Step principal (s) | Job completo (s) | Filas/s (step) | Speedup vs 1.ª fila | Leídas | Escritas | Omitidas | Filtradas |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        double base = rows.get(0).stepMs();
        for (Result r : rows) {
            sb.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %d | %.2f× | %d | %d | %d | %d |%n", r.config(), r.stepMs() / 1000.0,
                    r.jobMs() / 1000.0, (long) r.rowsPerSecond(), base / r.stepMs(), r.read(), r.written(), r.skipped(), r.filtered()));
        }
        return sb.append("\n").toString();
    }

    @Test
    void benchmark() throws Exception {
        // Calentamiento de JIT/pool (descartado)
        run("dailyTransactionReportJob", "movimientos_financieros_diarios.csv", "warm-up", "batch.parallelism.grid-size=4", "batch.chunk-size=1000");

        List<Result> daily = new ArrayList<>();
        for (int grid : new int[] {1, 2, 4, 8}) {
            daily.add(average("dailyTransactionReportJob", "movimientos_financieros_diarios.csv",
                    grid == 1 ? "1 partición (secuencial)" : grid + " particiones", "batch.parallelism.grid-size=" + grid, "batch.chunk-size=1000"));
        }
        sections.add(table("dailyTransactionReportJob — partitioning local (TaskExecutorPartitionHandler)",
                "Step `loadMovementsStep` (manager) con `gridSize` particiones por rango de líneas; chunk = 1000.", daily));

        List<Result> chunks = new ArrayList<>();
        for (int chunk : new int[] {50, 200, 1000, 5000}) {
            chunks.add(run("dailyTransactionReportJob", "movimientos_financieros_diarios.csv", "chunk " + chunk,
                    "batch.parallelism.grid-size=4", "batch.chunk-size=" + chunk));
        }
        sections.add(table("dailyTransactionReportJob — sensibilidad al tamaño de chunk (4 particiones)",
                "Una sola medición por tamaño; muestra el costo de commits frecuentes.", chunks));

        List<Result> annual = new ArrayList<>();
        for (int threads : new int[] {1, 2, 4, 8}) {
            annual.add(average("annualStatementJob", "estados_financieros_anuales.csv",
                    threads == 1 ? "1 hilo" : threads + " hilos", "batch.parallelism.threads=" + threads, "batch.chunk-size=1000"));
        }
        sections.add(table("annualStatementJob — step multi-hilo (SynchronizedItemStreamReader)",
                "Step `loadTransactionsStep` con `TaskExecutor` de N hilos; lectura sincronizada, proceso y escritura en paralelo; chunk = 1000.", annual));

        List<Result> interest = List.of(average("monthlyInterestJob", "intereses_trimestrales.csv", "secuencial (1 hilo)", "batch.chunk-size=1000"));
        sections.add(table("monthlyInterestJob — referencia secuencial",
                "Este job es deliberadamente secuencial: deduplica en orden de archivo y es restartable chunk a chunk.", interest));

        Path report = Path.of(System.getProperty("benchmark.report", "../../evidencias/local/batch_benchmark.md")).toAbsolutePath().normalize();
        Files.createDirectories(report.getParent());
        Files.writeString(report, render());
        System.out.println("[BENCH] informe: " + report);
    }

    private static String render() {
        long rows = Long.getLong("benchmark.rows", 500_000);
        return """
                # Benchmark de jobs batch: 1 hilo vs N hilos/particiones

                Generado automáticamente por `scripts/benchmark-batch.sh` (`BatchBenchmarkIT`) el %s. **No editar a mano.**

                ## Entorno y método

                | Ítem | Valor |
                |---|---|
                | Máquina | %s |
                | Núcleos visibles por la JVM | %d |
                | SO / arquitectura | %s %s / %s |
                | JVM | %s %s |
                | Base de datos | PostgreSQL 16 (alpine) en contenedor Docker, `shared_buffers=256MB`, `synchronous_commit=on` |
                | Datos | CSV sintéticos de **%d filas** por job, generados con `scripts/generate_synthetic_csv.py --seed 42` (mismos tipos de defectos que el legacy: fechas inválidas, montos vacíos/negativos/cero, tipos desconocidos, duplicados) |
                | Repeticiones | %d por configuración (se reporta el promedio); 1 corrida de calentamiento descartada |
                | skipLimit | 100.000.000 (sin límite práctico: los defectos son parte de la carga) |

                Métrica principal: duración del **step principal** (lectura → validación → escritura), que es lo que se paraleliza.
                "Job completo" incluye validación de archivo, resumen/consolidación, exportación y reporte.
                Todas las filas rechazadas se persisten en `batch_rejected_record` dentro del tiempo medido.

                ## Resultados

                %s
                ## Lectura de los resultados

                - La escalabilidad está limitada por **una sola instancia de PostgreSQL** (escrituras, índices únicos y WAL) y por la
                  contención entre hilos; el speedup no es lineal. Con más nodos de aplicación o remote partitioning el cuello de botella
                  pasa a ser la base de datos.
                - Los números son de una laptop de desarrollo con Docker; sirven para comparar configuraciones entre sí, no como cifras de producción.
                - El tiempo del sistema legacy (Shell/COBOL) **no se midió** en este proyecto: no se dispone de su ejecución; cualquier comparación
                  con él es referencial.
                """.formatted(LocalDate.now(), System.getProperty("benchmark.machine", "(no informada)"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("os.arch"), System.getProperty("java.vm.name"), System.getProperty("java.version"), rows,
                REPEATS, String.join("\n", sections));
    }
}

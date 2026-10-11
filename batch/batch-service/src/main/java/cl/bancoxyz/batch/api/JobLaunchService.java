package cl.bancoxyz.batch.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Service;

import cl.bancoxyz.batch.common.SimulatedFailureWriter;
import cl.bancoxyz.batch.config.BatchProperties;
import cl.bancoxyz.batch.job.interest.Periodo;

/**
 * Lanzamiento asíncrono de jobs (REST, cron y reintento automático). No se expone como bean JobLauncher
 * para no colisionar con el launcher síncrono que Spring Boot usa en tests y en modo CLI.
 */
@Service
public class JobLaunchService {

    public static final String DAILY = "dailyTransactionReportJob";
    public static final String INTEREST = "monthlyInterestJob";
    public static final String ANNUAL = "annualStatementJob";
    public static final Set<String> JOB_NAMES = Set.of(DAILY, INTEREST, ANNUAL);

    private final ApplicationContext ctx;
    private final JobExplorer explorer;
    private final BatchProperties props;
    private final JobLauncher asyncLauncher;

    public JobLaunchService(ApplicationContext ctx, JobExplorer explorer, JobRepository jobRepository,
                            BatchProperties props) throws Exception {
        this.ctx = ctx;
        this.explorer = explorer;
        this.props = props;
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("job-launcher-"));
        launcher.afterPropertiesSet();
        this.asyncLauncher = launcher;
    }

    /** Construye los parámetros: runId/inputFile/periodo identifican la instancia; los simulate* no. */
    public JobParameters buildParameters(String jobName, Map<String, String> requested) {
        if (!JOB_NAMES.contains(jobName)) {
            throw new IllegalArgumentException("Job desconocido: " + jobName);
        }
        Map<String, String> p = requested == null ? Map.of() : requested;
        JobParametersBuilder b = new JobParametersBuilder()
                .addString("runId", p.getOrDefault("runId", String.valueOf(Instant.now().toEpochMilli())), true)
                .addString("inputFile", p.getOrDefault("inputFile", defaultFile(jobName)), true);
        if (INTEREST.equals(jobName)) {
            b.addString("periodo", Periodo.parse(p.getOrDefault("periodo", "MENSUAL")).name(), true);
        }
        for (String key : new String[] {SimulatedFailureWriter.FAIL_AT_CHUNK, SimulatedFailureWriter.TRANSIENT_FAILURES}) {
            if (p.containsKey(key)) {
                b.addLong(key, parseLong(key, p.get(key)), false);
            }
        }
        return b.toJobParameters();
    }

    public JobExecution launch(String jobName, Map<String, String> requested) throws Exception {
        return run(jobName, buildParameters(jobName, requested));
    }

    /** Relanza una instancia FAILED: mismos parámetros identificantes → Spring Batch la reinicia. */
    public JobExecution restart(long executionId) throws Exception {
        JobExecution previous = explorer.getJobExecution(executionId);
        if (previous == null) {
            throw new NoSuchJobException("No existe la ejecución " + executionId);
        }
        return run(previous.getJobInstance().getJobName(), previous.getJobParameters());
    }

    public JobExecution run(String jobName, JobParameters parameters) throws Exception {
        Job job;
        try {
            job = ctx.getBean(jobName, Job.class);
        } catch (org.springframework.beans.BeansException e) {
            throw new NoSuchJobException(jobName);
        }
        return asyncLauncher.run(job, parameters);
    }

    private String defaultFile(String jobName) {
        return Path.of(switch (jobName) {
            case DAILY -> props.files().movements();
            case INTEREST -> props.files().interests();
            default -> props.files().statements();
        }).toString();
    }

    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El parámetro " + key + " debe ser un entero: " + value);
        }
    }
}

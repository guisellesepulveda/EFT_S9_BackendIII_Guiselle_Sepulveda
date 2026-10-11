package cl.bancoxyz.batch.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import cl.bancoxyz.batch.api.JobLaunchService;
import cl.bancoxyz.batch.config.BatchProperties;
import cl.bancoxyz.batch.events.JobEventPayload;
import cl.bancoxyz.batch.events.JobEventPublisher;

/**
 * Cierre de job: reporta, emite el evento a Kafka y, ante FAILED, programa un reintento automático
 * (máximo N ejecuciones por instancia, con espera). El reintento es un restart: continúa desde el
 * último chunk confirmado.
 */
@Component
public class JobLifecycleListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleListener.class);

    private final JobExplorer explorer;
    private final ExecutionReporter reporter;
    private final JobEventPublisher publisher;
    private final JobLaunchService launchService;
    private final TaskScheduler scheduler;
    private final BatchProperties props;
    private final JdbcTemplate jdbc;

    public JobLifecycleListener(JobExplorer explorer, ExecutionReporter reporter, JobEventPublisher publisher,
                                JobLaunchService launchService, TaskScheduler scheduler, BatchProperties props,
                                JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.explorer = explorer;
        this.reporter = reporter;
        this.publisher = publisher;
        this.launchService = launchService;
        this.scheduler = scheduler;
        this.props = props;
    }

    @Override
    public void beforeJob(JobExecution je) {
        log.info("Iniciando {} (instancia {}, ejecución {}) con parámetros {}", je.getJobInstance().getJobName(),
                je.getJobInstance().getInstanceId(), je.getId(), je.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution je) {
        try {
            boolean failed = je.getStatus() == BatchStatus.FAILED;
            int attempt = explorer.getJobExecutions(je.getJobInstance()).size();
            boolean willRetry = failed && shouldRetry(je, attempt);
            if (failed) {
                reporter.report(je, null);
                log.error("{} FALLÓ (ejecución {}, intento {}): {}", je.getJobInstance().getJobName(), je.getId(),
                        attempt, je.getExitStatus().getExitDescription().lines().findFirst().orElse(""));
            }
            publisher.publish(failed ? JobEventPublisher.JOB_FAILED : JobEventPublisher.JOB_COMPLETED,
                    payload(je, attempt, willRetry));
            if (!failed) {
                raiseAnomalyAlert(je);
            }
            if (willRetry) {
                scheduleRetry(je);
            }
        } catch (RuntimeException e) {
            // Un problema de reporte/eventos nunca debe alterar el resultado del job
            log.error("Error en el listener de cierre de {}: {}", je.getJobInstance().getJobName(), e.toString());
        }
    }

    /** Anomalías detectadas en un job diario (monto atípico, tipo desconocido, ...) → security.alerts para su revisión. */
    private void raiseAnomalyAlert(JobExecution je) {
        Long anomalies = jdbc.queryForObject("SELECT count(*) FROM transaction_anomaly WHERE job_instance_id = ?", Long.class,
                je.getJobInstance().getInstanceId());
        if (anomalies != null && anomalies > 0) {
            publisher.publishAlert(anomalies > props.anomaly().alertHighThreshold() ? "MEDIUM" : "LOW", "DATA_ANOMALIES_DETECTED",
                    je.getJobInstance().getJobName(), anomalies + " anomalías en la ejecución " + je.getId()
                            + " (ver tabla transaction_anomaly)");
        }
    }

    private boolean shouldRetry(JobExecution je, int attempt) {
        BatchProperties.AutoRetry cfg = props.autoRetry();
        boolean unrecoverable = je.getAllFailureExceptions().stream().anyMatch(JobLifecycleListener::isFileValidation);
        return cfg.enabled() && attempt < cfg.maxAttempts() && !unrecoverable;
    }

    private static boolean isFileValidation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof FileValidationException) {
                return true;
            }
        }
        return false;
    }

    private void scheduleRetry(JobExecution failed) {
        long delayMs = props.autoRetry().delay().toMillis();
        log.warn("Reintento automático de {} (ejecución fallida {}) en {} ms", failed.getJobInstance().getJobName(),
                failed.getId(), delayMs);
        scheduler.schedule(() -> {
            try {
                launchService.run(failed.getJobInstance().getJobName(), failed.getJobParameters());
            } catch (Exception e) {
                log.error("No se pudo relanzar {}: {}", failed.getJobInstance().getJobName(), e.toString());
            }
        }, Instant.now().plusMillis(delayMs));
    }

    private static JobEventPayload payload(JobExecution je, int attempt, boolean willRetry) {
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, JobParameter<?>> e : je.getJobParameters().getParameters().entrySet()) {
            params.put(e.getKey(), String.valueOf(e.getValue().getValue()));
        }
        String description = je.getExitStatus().getExitDescription();
        return new JobEventPayload(je.getJobInstance().getJobName(), je.getJobInstance().getInstanceId(), je.getId(),
                je.getStatus().name(), je.getExitStatus().getExitCode(),
                description.length() > 500 ? description.substring(0, 500) : description,
                je.getStartTime(), je.getEndTime(), attempt, willRetry, params);
    }
}

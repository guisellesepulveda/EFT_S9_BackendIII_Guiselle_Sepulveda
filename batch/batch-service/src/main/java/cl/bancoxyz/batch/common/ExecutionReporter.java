package cl.bancoxyz.batch.common;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reporte de ejecución: leídos / escritos / omitidos / filtrados por step (log + tabla job_execution_report). */
@Component
public class ExecutionReporter {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReporter.class);

    private final JdbcTemplate jdbc;

    public ExecutionReporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void report(JobExecution je, String excludeStep) {
        String job = je.getJobInstance().getJobName();
        long instanceId = je.getJobInstance().getInstanceId();
        log.info("==== REPORTE DE EJECUCIÓN {} (instancia {}, ejecución {}, estado {}) ====",
                job, instanceId, je.getId(), je.getStatus());
        log.info(String.format("%-42s %-10s %8s %8s %8s %8s %7s %6s", "step", "estado",
                "leídos", "escritos", "omitidos", "filtrad.", "retries", "ms"));
        for (StepExecution se : je.getStepExecutions()) {
            if (se.getStepName().equals(excludeStep)) {
                continue;
            }
            long millis = durationMillis(se);
            long retries = se.getExecutionContext().getLong(StepAuditListener.RETRY_COUNT_KEY, 0L);
            log.info(String.format("%-42s %-10s %8d %8d %8d %8d %7d %6d", se.getStepName(), se.getStatus(),
                    se.getReadCount(), se.getWriteCount(), se.getSkipCount(), se.getFilterCount(), retries, millis));
            jdbc.update("""
                    INSERT INTO job_execution_report
                        (job_name, job_instance_id, job_execution_id, step_name, status, read_count, write_count,
                         skip_count, filter_count, retry_count, commit_count, duration_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (job_execution_id, step_name) DO UPDATE SET
                        status = EXCLUDED.status, read_count = EXCLUDED.read_count, write_count = EXCLUDED.write_count,
                        skip_count = EXCLUDED.skip_count, filter_count = EXCLUDED.filter_count,
                        retry_count = EXCLUDED.retry_count, commit_count = EXCLUDED.commit_count,
                        duration_ms = EXCLUDED.duration_ms, reported_at = now()
                    """, job, instanceId, je.getId(), se.getStepName(), se.getStatus().name(), se.getReadCount(),
                    se.getWriteCount(), se.getSkipCount(), se.getFilterCount(), retries, se.getCommitCount(), millis);
        }
    }

    private static long durationMillis(StepExecution se) {
        LocalDateTime start = se.getStartTime();
        LocalDateTime end = se.getEndTime() != null ? se.getEndTime() : LocalDateTime.now();
        return start == null ? 0 : Duration.between(start, end).toMillis();
    }
}

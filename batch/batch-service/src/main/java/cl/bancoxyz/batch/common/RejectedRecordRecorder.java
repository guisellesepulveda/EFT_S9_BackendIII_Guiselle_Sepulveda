package cl.bancoxyz.batch.common;

import org.springframework.batch.core.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persiste cada registro omitido o filtrado para que nada se pierda en silencio.
 * Se invoca dentro de la transacción del chunk; la restricción única (job_instance_id, line_number)
 * hace idempotente la inserción ante reprocesos de un chunk o restarts.
 */
@Component
public class RejectedRecordRecorder {

    private static final int MAX_RAW = 2000;

    private final JdbcTemplate jdbc;

    public RejectedRecordRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordSkip(StepExecution se, long lineNumber, String raw, String code, String reason) {
        insert(se, lineNumber, raw, "SKIPPED", code, reason);
    }

    public void recordFiltered(StepExecution se, SourceLine line, RejectionReason reason, String detail) {
        insert(se, line.lineNumber(), line.rawLine(), "FILTERED", reason.name(), detail);
    }

    public void recordAnomaly(StepExecution se, long lineNumber, String sourceId, RejectionReason type, String detail) {
        jdbc.update("""
                INSERT INTO transaction_anomaly (job_instance_id, line_number, source_id, anomaly_type, detail)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (job_instance_id, line_number, anomaly_type) DO NOTHING
                """, instanceId(se), lineNumber, sourceId, type.name(), detail);
    }

    private void insert(StepExecution se, long lineNumber, String raw, String disposition, String code, String reason) {
        jdbc.update("""
                INSERT INTO batch_rejected_record
                    (job_name, job_instance_id, job_execution_id, step_name, line_number, disposition,
                     reason_code, reason, raw_content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_instance_id, line_number) DO NOTHING
                """,
                se.getJobExecution().getJobInstance().getJobName(), instanceId(se), se.getJobExecutionId(),
                se.getStepName(), lineNumber, disposition, truncate(code, 200), truncate(reason, 500),
                truncate(raw, MAX_RAW));
    }

    private static long instanceId(StepExecution se) {
        return se.getJobExecution().getJobInstance().getInstanceId();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

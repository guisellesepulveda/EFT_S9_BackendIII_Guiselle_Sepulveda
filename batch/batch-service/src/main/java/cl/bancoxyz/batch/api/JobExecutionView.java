package cl.bancoxyz.batch.api;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.StepExecution;

import cl.bancoxyz.batch.common.StepAuditListener;

public record JobExecutionView(long executionId, long instanceId, String jobName, String status, String exitCode,
                               LocalDateTime startTime, LocalDateTime endTime, Map<String, String> parameters,
                               List<StepView> steps) {

    public record StepView(String name, String status, long read, long written, long skipped, long filtered,
                           long retries, long commits) {
    }

    public static JobExecutionView of(JobExecution je) {
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, JobParameter<?>> e : je.getJobParameters().getParameters().entrySet()) {
            params.put(e.getKey(), String.valueOf(e.getValue().getValue()));
        }
        List<StepView> steps = je.getStepExecutions().stream().map(JobExecutionView::step).toList();
        return new JobExecutionView(je.getId(), je.getJobInstance().getInstanceId(), je.getJobInstance().getJobName(),
                je.getStatus().name(), je.getExitStatus().getExitCode(), je.getStartTime(), je.getEndTime(), params,
                steps);
    }

    private static StepView step(StepExecution se) {
        return new StepView(se.getStepName(), se.getStatus().name(), se.getReadCount(), se.getWriteCount(),
                se.getSkipCount(), se.getFilterCount(),
                se.getExecutionContext().getLong(StepAuditListener.RETRY_COUNT_KEY, 0L), se.getCommitCount());
    }
}

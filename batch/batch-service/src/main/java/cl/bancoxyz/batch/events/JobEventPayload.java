package cl.bancoxyz.batch.events;

import java.time.LocalDateTime;
import java.util.Map;

public record JobEventPayload(
        String jobName,
        long jobInstanceId,
        long jobExecutionId,
        String status,
        String exitCode,
        String exitDescription,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int attempt,
        boolean willRetry,
        Map<String, String> parameters) {
}

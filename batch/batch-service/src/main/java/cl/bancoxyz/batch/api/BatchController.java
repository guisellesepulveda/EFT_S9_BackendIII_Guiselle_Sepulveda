package cl.bancoxyz.batch.api;

import java.util.List;
import java.util.Map;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Operación manual de jobs. Todo el controlador exige el rol BATCH_ADMIN. */
@RestController
@RequestMapping("/api/batch")
@PreAuthorize("hasRole('BATCH_ADMIN')")
@Tag(name = "Batch", description = "Lanzamiento y consulta de jobs (rol BATCH_ADMIN)")
public class BatchController {

    private final JobLaunchService launchService;
    private final JobExplorer explorer;
    private final JdbcTemplate jdbc;

    public BatchController(JobLaunchService launchService, JobExplorer explorer, JdbcTemplate jdbc) {
        this.launchService = launchService;
        this.explorer = explorer;
        this.jdbc = jdbc;
    }

    /** Parámetros opcionales: runId, inputFile, periodo (MENSUAL|TRIMESTRAL), simulateFailureAtChunk, simulateTransientFailures. */
    public record LaunchRequest(Map<String, String> parameters) {
    }

    @PostMapping("/jobs/{jobName}")
    @Operation(summary = "Lanza un job de forma asíncrona (202 + id de ejecución)")
    public ResponseEntity<JobExecutionView> launch(@PathVariable String jobName,
                                                   @RequestBody(required = false) LaunchRequest request)
            throws Exception {
        JobExecution je = launchService.launch(jobName, request == null ? null : request.parameters());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobExecutionView.of(je));
    }

    @GetMapping("/executions/{id}")
    @Operation(summary = "Estado y contadores por step de una ejecución")
    public JobExecutionView execution(@PathVariable long id) throws NoSuchJobExecutionException {
        JobExecution je = explorer.getJobExecution(id);
        if (je == null) {
            throw new NoSuchJobExecutionException("No existe la ejecución " + id);
        }
        return JobExecutionView.of(je);
    }

    @PostMapping("/executions/{id}/restart")
    @Operation(summary = "Reinicia una ejecución FAILED (continúa desde el último chunk confirmado)")
    public ResponseEntity<JobExecutionView> restart(@PathVariable long id) throws Exception {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobExecutionView.of(launchService.restart(id)));
    }

    @GetMapping("/executions/{id}/rejected")
    @Operation(summary = "Registros rechazados/filtrados de la instancia de una ejecución")
    public List<Map<String, Object>> rejected(@PathVariable long id,
                                              @RequestParam(defaultValue = "100") int limit)
            throws NoSuchJobExecutionException {
        JobExecution je = explorer.getJobExecution(id);
        if (je == null) {
            throw new NoSuchJobExecutionException("No existe la ejecución " + id);
        }
        return jdbc.queryForList("""
                SELECT line_number, disposition, reason_code, reason, raw_content
                FROM batch_rejected_record WHERE job_instance_id = ? ORDER BY line_number LIMIT ?
                """, je.getJobInstance().getInstanceId(), Math.min(Math.max(limit, 1), 1000));
    }
}

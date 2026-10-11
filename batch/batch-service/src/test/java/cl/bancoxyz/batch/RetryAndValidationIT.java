package cl.bancoxyz.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;

import cl.bancoxyz.batch.common.StepAuditListener;

/** retry + backoff exponencial ante fallos transitorios y validación del archivo de entrada. */
class RetryAndValidationIT extends AbstractIT {

    @TempDir
    Path tmp;

    @Test
    void transientFailuresAreRetriedWithinTheLimit() throws Exception {
        // retryLimit = 3 intentos: 2 fallos transitorios simulados se absorben con reintentos
        JobParameters p = params(INTEREST, defaultInput(INTEREST)).addLong("simulateTransientFailures", 2L, false).toJobParameters();
        JobExecution je = run(INTEREST, p);

        assertCompleted(je);
        StepExecution calc = step(je, "calculateInterestStep");
        assertThat(calc.getExecutionContext().getLong(StepAuditListener.RETRY_COUNT_KEY)).isEqualTo(2L);
        assertThat(calc.getWriteCount()).isEqualTo(509);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interest_calculation", Long.class)).isEqualTo(509);
    }

    @Test
    void retriesAreBoundedByRetryLimit() throws Exception {
        JobParameters p = params(INTEREST, defaultInput(INTEREST)).addLong("simulateTransientFailures", 3L, false).toJobParameters();
        JobExecution je = run(INTEREST, p);

        assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(je.getExitStatus().getExitDescription()).containsAnyOf("Retry", "retry", "Fallo transitorio simulado");
        assertThat(step(je, "calculateInterestStep").getExecutionContext().getLong(StepAuditListener.RETRY_COUNT_KEY)).isEqualTo(3L);
    }

    @Test
    void wrongHeaderStopsTheJobBeforeReadingAnything() throws Exception {
        Path bad = Files.writeString(tmp.resolve("bad.csv"), "id,date,amount,type\n1,2024-01-01,10,debito\n");
        JobExecution je = run(DAILY, params(DAILY, bad).toJobParameters());

        assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(je.getExitStatus().getExitDescription()).contains("Encabezado inválido");
        assertThat(je.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly("validateFileStep");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_transaction", Long.class)).isZero();
    }

    @Test
    void missingFileFailsValidation() throws Exception {
        JobExecution je = run(ANNUAL, params(ANNUAL, tmp.resolve("no-existe.csv")).toJobParameters());

        assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(je.getExitStatus().getExitDescription()).contains("inexistente");
    }

    @Test
    void malformedLinesAreSkippedAndAudited() throws Exception {
        Path f = Files.writeString(tmp.resolve("m.csv"), """
                id,fecha,monto,tipo
                1,2024-01-01,100,debito
                2,2024-01-02,200
                3,2024-01-03,300,credito,extra
                4,2024-01-04,400,credito
                """);
        JobExecution je = run(DAILY, params(DAILY, f).toJobParameters());

        assertCompleted(je);
        assertThat(step(je, "loadMovementsStep").getSkipCount()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT line_number, reason_code, raw_content FROM batch_rejected_record ORDER BY line_number"))
                .extracting(r -> r.get("line_number") + ":" + r.get("reason_code") + ":" + r.get("raw_content"))
                .containsExactly("3:MALFORMED_LINE:2,2024-01-02,200", "4:MALFORMED_LINE:3,2024-01-03,300,credito,extra");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_transaction", Long.class)).isEqualTo(2);
    }
}

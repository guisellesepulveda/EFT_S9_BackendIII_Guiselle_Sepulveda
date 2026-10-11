package cl.bancoxyz.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.test.context.TestPropertySource;

/** El skipLimit es configurable: al superarlo el job falla en lugar de ocultar un archivo en mal estado. */
@TestPropertySource(properties = "batch.skip-limit=60")
class SkipLimitIT extends AbstractIT {

    @Test
    void exceedingSkipLimitFailsTheJobWithoutLosingAuditTrail() throws Exception {
        JobExecution je = run(INTEREST);

        assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(je.getExitStatus().getExitDescription()).contains("SkipLimitExceededException");

        // Los chunks confirmados antes de exceder el límite conservan sus rechazos; el chunk que excede hace rollback
        // completo (datos y rechazos). Invariante: cada línea de un chunk confirmado quedó escrita, omitida o filtrada.
        StepExecution calc = step(je, "calculateInterestStep");
        long chunkSize = 50;
        long written = jdbc.queryForObject("SELECT count(*) FROM interest_calculation", Long.class);
        long audited = countRejected(je, "SKIPPED") + countRejected(je, "FILTERED");
        assertThat(audited).isPositive();
        assertThat(written + audited).isEqualTo(calc.getCommitCount() * chunkSize);
    }
}

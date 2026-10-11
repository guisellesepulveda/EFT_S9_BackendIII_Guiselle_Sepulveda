package cl.bancoxyz.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;

/**
 * Restart: tras un fallo simulado, relanzar con los MISMOS parámetros continúa desde el último chunk confirmado
 * y el resultado final es idéntico al de una ejecución sin fallos (sin duplicados ni pérdidas).
 */
class RestartIT extends AbstractIT {

    @Test
    void sequentialInterestJobResumesFromLastCommittedChunk() throws Exception {
        JobParameters p = params(INTEREST, defaultInput(INTEREST)).addLong("simulateFailureAtChunk", 5L, false).toJobParameters();

        JobExecution first = run(INTEREST, p);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(first.getExitStatus().getExitDescription()).contains("SimulatedFailureException");
        // 4 chunks confirmados antes de fallar en el 5.º (chunk-size = 50 líneas leídas)
        assertThat(step(first, "calculateInterestStep").getCommitCount()).isEqualTo(4);
        long writtenBeforeFailure = jdbc.queryForObject("SELECT count(*) FROM interest_calculation", Long.class);
        assertThat(writtenBeforeFailure).isPositive().isLessThan(509);

        JobExecution second = run(INTEREST, p); // mismos parámetros → restart de la misma instancia
        assertCompleted(second);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        // la 2.ª ejecución NO relee las 200 líneas ya confirmadas
        assertThat(step(second, "calculateInterestStep").getReadCount()).isEqualTo(1000 - 200);
        assertThat(step(second, "calculateInterestStep").getReadCount() + 200).isEqualTo(1000);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM interest_calculation", Long.class)).isEqualTo(509);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interest_calculation WHERE job_instance_id = ?", Long.class,
                instanceId(second))).isEqualTo(509);
        assertThat(countRejected(second, "SKIPPED")).isEqualTo(489);
        assertThat(countRejected(second, "FILTERED")).isEqualTo(2);
        // el step de validación ya completado no se repite
        assertThat(second.getStepExecutions()).extracting(s -> s.getStepName()).doesNotContain("validateFileStep");
    }

    @Test
    void partitionedDailyJobRestartsFailedPartitionsOnly() throws Exception {
        JobParameters p = params(DAILY, defaultInput(DAILY)).addLong("simulateFailureAtChunk", 2L, false).toJobParameters();

        JobExecution first = run(DAILY, p);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution second = run(DAILY, p);
        assertCompleted(second);
        List<Long> workerReads = second.getStepExecutions().stream()
                .filter(s -> s.getStepName().startsWith("loadMovementsWorkerStep:partition"))
                .map(s -> s.getReadCount()).toList();
        assertThat(workerReads).hasSize(4);
        // cada partición (250 filas) retoma tras su primer chunk confirmado (50 filas)
        assertThat(workerReads).allMatch(r -> r == 200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_transaction WHERE job_instance_id = ?", Long.class,
                instanceId(second))).isEqualTo(209);
        assertThat(countRejected(second, "SKIPPED")).isEqualTo(791);
        assertThat(jdbc.queryForObject("SELECT sum(tx_count) FROM daily_transaction_summary WHERE job_instance_id = ?",
                Long.class, instanceId(second))).isEqualTo(209);
    }

    @Test
    void multiThreadedAnnualJobRestartIsIdempotent() throws Exception {
        JobParameters p = params(ANNUAL, defaultInput(ANNUAL)).addLong("simulateFailureAtChunk", 2L, false).toJobParameters();

        JobExecution first = run(ANNUAL, p);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution second = run(ANNUAL, p);
        assertCompleted(second);
        // saveState=false: se relee todo, pero las claves únicas evitan duplicados
        assertThat(jdbc.queryForObject("SELECT count(*) FROM annual_transaction WHERE job_instance_id = ?", Long.class,
                instanceId(second))).isEqualTo(230);
        assertThat(countRejected(second, "SKIPPED")).isEqualTo(770);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM annual_statement WHERE job_instance_id = ?", Long.class,
                instanceId(second))).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT sum(tx_count) FROM annual_statement WHERE job_instance_id = ?", Long.class,
                instanceId(second))).isEqualTo(230);
    }

    @Test
    void completedInstanceCannotBeRunAgain() throws Exception {
        JobParameters p = params(INTEREST, defaultInput(INTEREST)).toJobParameters();
        assertCompleted(run(INTEREST, p));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(INTEREST, p))
                .isInstanceOf(org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException.class);
    }
}

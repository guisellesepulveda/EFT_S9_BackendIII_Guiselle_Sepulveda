package cl.bancoxyz.batch.job.interest;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import cl.bancoxyz.batch.common.CsvReaders;
import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.FileValidationTasklet;
import cl.bancoxyz.batch.common.JobLifecycleListener;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.SimulatedFailureWriter;
import cl.bancoxyz.batch.common.StepAuditListener;
import cl.bancoxyz.batch.common.StepSupport;
import cl.bancoxyz.batch.config.BatchProperties;

/**
 * monthlyInterestJob: validateFile → calculateInterest (secuencial, restartable, deduplica) → updateBalances → executionReport.
 * El parámetro periodo (MENSUAL|TRIMESTRAL) forma parte de la identidad de la instancia (ADR-002).
 */
@Configuration
public class InterestJobConfig {

    static final String[] COLUMNS = {"cuenta_id", "nombre", "saldo", "edad", "tipo"};

    private final BatchProperties props;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final DataSource dataSource;
    private final StepSupport support;
    private final RejectedRecordRecorder recorder;
    private final JobExplorer explorer;

    public InterestJobConfig(BatchProperties props, JobRepository jobRepository, PlatformTransactionManager txManager,
                             DataSource dataSource, StepSupport support, RejectedRecordRecorder recorder,
                             JobExplorer explorer) {
        this.props = props;
        this.jobRepository = jobRepository;
        this.txManager = txManager;
        this.dataSource = dataSource;
        this.support = support;
        this.recorder = recorder;
        this.explorer = explorer;
    }

    @Bean
    Job monthlyInterestJob(JobLifecycleListener listener) {
        return new JobBuilder("monthlyInterestJob", jobRepository)
                .listener(listener)
                .start(interestValidateFileStep())
                .next(calculateInterestStep())
                .next(updateBalancesStep())
                .next(support.executionReportStep())
                .build();
    }

    @Bean
    Step interestValidateFileStep() {
        return support.validateFileStep(interestFileValidationTasklet(null));
    }

    @Bean
    @StepScope
    Tasklet interestFileValidationTasklet(@Value("#{jobParameters['inputFile']}") String inputFile) {
        return new FileValidationTasklet(Path.of(inputFile), COLUMNS);
    }

    @Bean
    Step calculateInterestStep() {
        var builder = new StepBuilder("calculateInterestStep", jobRepository)
                .<CsvRow, InterestCalculation>chunk(props.chunkSize(), txManager)
                .reader(interestReader(null))
                .processor(interestProcessor(null, null))
                .writer(interestWriter(null, null))
                .startLimit(5)
                .allowStartIfComplete(false);
        return support.faultTolerant(builder, interestAuditListener(null)).build();
    }

    @Bean
    @StepScope
    StepAuditListener interestAuditListener(@Value("#{stepExecution}") StepExecution se) {
        return new StepAuditListener(se, recorder, false);
    }

    @Bean
    @StepScope
    FlatFileItemReader<CsvRow> interestReader(@Value("#{jobParameters['inputFile']}") String inputFile) {
        return CsvReaders.reader("interestReader", Path.of(inputFile), COLUMNS, 0, 0, true);
    }

    @Bean
    @StepScope
    ItemProcessor<CsvRow, InterestCalculation> interestProcessor(
            @Value("#{jobParameters['periodo']}") String periodo, @Value("#{stepExecution}") StepExecution se) {
        long instanceId = se.getJobExecution().getJobInstance().getInstanceId();
        Map<String, Long> written = new HashMap<>();
        new JdbcTemplate(dataSource).query("SELECT dedup_key, line_number FROM interest_calculation WHERE job_instance_id = ?",
                rs -> { written.put(rs.getString(1), rs.getLong(2)); }, instanceId);
        return new InterestProcessor(props.interest().annualRates(), props.interest().minAge(),
                props.interest().maxAge(), Periodo.parse(periodo), se, recorder, written);
    }

    @Bean(destroyMethod = "")
    @StepScope
    ItemStreamWriter<InterestCalculation> interestWriter(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId,
            @Value("#{stepExecution}") StepExecution se) {
        JdbcBatchItemWriter<InterestCalculation> jdbc = new JdbcBatchItemWriterBuilder<InterestCalculation>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO interest_calculation
                            (job_instance_id, line_number, cuenta_id, nombre, tipo, periodo, saldo_anterior,
                             tasa_anual, interes, saldo_nuevo, dedup_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (job_instance_id, dedup_key) DO NOTHING
                        """)
                .itemPreparedStatementSetter((c, ps) -> {
                    ps.setLong(1, instanceId);
                    ps.setLong(2, c.lineNumber());
                    ps.setLong(3, c.cuentaId());
                    ps.setString(4, c.nombre());
                    ps.setString(5, c.tipo());
                    ps.setString(6, c.periodo().name());
                    ps.setBigDecimal(7, c.saldoAnterior());
                    ps.setBigDecimal(8, c.tasaAnual());
                    ps.setBigDecimal(9, c.interes());
                    ps.setBigDecimal(10, c.saldoNuevo());
                    ps.setString(11, c.dedupKey());
                })
                .assertUpdates(false)
                .build();
        return SimulatedFailureWriter.wrap(jdbc, se, explorer);
    }

    // ------------------------------------------------------------------ actualización de saldos
    /**
     * Si una cuenta aparece varias veces en el archivo (el dataset legacy repite cuenta_id con distintos datos),
     * prevalece la ÚLTIMA línea del archivo: resultado determinista y documentado.
     */
    @Bean
    Step updateBalancesStep() {
        return new StepBuilder("updateBalancesStep", jobRepository)
                .<BalanceUpdate, BalanceUpdate>chunk(props.chunkSize(), txManager)
                .reader(balanceReader(null))
                .writer(balanceWriter(null))
                .startLimit(5)
                .allowStartIfComplete(false)
                .build();
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<BalanceUpdate> balanceReader(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        return new JdbcCursorItemReaderBuilder<BalanceUpdate>()
                .name("balanceReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT DISTINCT ON (cuenta_id) cuenta_id, nombre, tipo, saldo_nuevo, periodo
                        FROM interest_calculation WHERE job_instance_id = ?
                        ORDER BY cuenta_id, line_number DESC
                        """)
                .preparedStatementSetter(ps -> ps.setLong(1, instanceId))
                .rowMapper((rs, i) -> new BalanceUpdate(rs.getLong("cuenta_id"), rs.getString("nombre"),
                        rs.getString("tipo"), rs.getBigDecimal("saldo_nuevo"), rs.getString("periodo")))
                .build();
    }

    @Bean
    @StepScope
    JdbcBatchItemWriter<BalanceUpdate> balanceWriter(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        return new JdbcBatchItemWriterBuilder<BalanceUpdate>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO account_balance (cuenta_id, nombre, tipo, saldo, last_periodo, last_job_instance_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (cuenta_id) DO UPDATE SET nombre = EXCLUDED.nombre, tipo = EXCLUDED.tipo,
                            saldo = EXCLUDED.saldo, last_periodo = EXCLUDED.last_periodo,
                            last_job_instance_id = EXCLUDED.last_job_instance_id, updated_at = now()
                        """)
                .itemPreparedStatementSetter((b, ps) -> {
                    ps.setLong(1, b.cuentaId());
                    ps.setString(2, b.nombre());
                    ps.setString(3, b.tipo());
                    ps.setBigDecimal(4, b.saldo());
                    ps.setString(5, b.periodo());
                    ps.setLong(6, instanceId);
                })
                .assertUpdates(false)
                .build();
    }
}

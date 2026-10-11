package cl.bancoxyz.batch.job.annual;

import java.nio.file.Path;
import java.sql.Date;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.item.support.builder.SynchronizedItemStreamReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import cl.bancoxyz.batch.common.CsvReaders;
import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.DateNormalizer;
import cl.bancoxyz.batch.common.FileValidationTasklet;
import cl.bancoxyz.batch.common.JobLifecycleListener;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.SimulatedFailureWriter;
import cl.bancoxyz.batch.common.StepAuditListener;
import cl.bancoxyz.batch.common.StepSupport;
import cl.bancoxyz.batch.config.BatchProperties;

/**
 * annualStatementJob: validateFile → loadTransactions (MULTI-HILO con SynchronizedItemStreamReader) →
 * consolidate → exportAccountFiles → executionReport.
 * En el step multi-hilo el reader no guarda estado (saveState=false): un restart relee el archivo completo y la
 * idempotencia la dan las claves únicas (job_instance_id, line_number / dedup_key) con ON CONFLICT DO NOTHING.
 */
@Configuration
public class AnnualJobConfig {

    static final String[] COLUMNS = {"cuenta_id", "fecha", "transaccion", "monto", "descripcion"};

    private final BatchProperties props;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final DataSource dataSource;
    private final StepSupport support;
    private final RejectedRecordRecorder recorder;
    private final JobExplorer explorer;

    public AnnualJobConfig(BatchProperties props, JobRepository jobRepository, PlatformTransactionManager txManager,
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
    Job annualStatementJob(JobLifecycleListener listener) {
        return new JobBuilder("annualStatementJob", jobRepository)
                .listener(listener)
                .start(annualValidateFileStep())
                .next(loadTransactionsStep())
                .next(consolidateStep())
                .next(exportAccountFilesStep())
                .next(support.executionReportStep())
                .build();
    }

    @Bean
    Step annualValidateFileStep() {
        return support.validateFileStep(annualFileValidationTasklet(null));
    }

    @Bean
    @StepScope
    Tasklet annualFileValidationTasklet(@Value("#{jobParameters['inputFile']}") String inputFile) {
        return new FileValidationTasklet(Path.of(inputFile), COLUMNS);
    }

    @SuppressWarnings("deprecation") // throttleLimit: sigue siendo la forma de acotar la concurrencia del step en Batch 5.2
    @Bean
    Step loadTransactionsStep() {
        int threads = props.parallelism().threads();
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("annual-mt-");
        executor.setConcurrencyLimit(threads);
        var builder = new StepBuilder("loadTransactionsStep", jobRepository)
                .<CsvRow, AnnualTransaction>chunk(props.chunkSize(), txManager)
                .reader(annualReader(null))
                .processor(annualProcessor(null))
                .writer(annualWriter(null, null))
                .startLimit(5)
                .allowStartIfComplete(false);
        return support.faultTolerant(builder, annualAuditListener(null))
                .taskExecutor(executor)
                .throttleLimit(threads)
                .build();
    }

    @Bean
    @StepScope
    StepAuditListener annualAuditListener(@Value("#{stepExecution}") StepExecution se) {
        return new StepAuditListener(se, recorder, false);
    }

    @Bean
    @StepScope
    SynchronizedItemStreamReader<CsvRow> annualReader(@Value("#{jobParameters['inputFile']}") String inputFile) {
        FlatFileItemReader<CsvRow> delegate =
                CsvReaders.reader("annualReader", Path.of(inputFile), COLUMNS, 0, 0, false);
        return new SynchronizedItemStreamReaderBuilder<CsvRow>().delegate(delegate).build();
    }

    @Bean
    @StepScope
    ItemProcessor<CsvRow, AnnualTransaction> annualProcessor(@Value("#{stepExecution}") StepExecution se) {
        long instanceId = se.getJobExecution().getJobInstance().getInstanceId();
        Map<String, Long> written = new HashMap<>();
        new JdbcTemplate(dataSource).query("SELECT dedup_key, line_number FROM annual_transaction WHERE job_instance_id = ?",
                rs -> { written.put(rs.getString(1), rs.getLong(2)); }, instanceId);
        return new AnnualTransactionProcessor(new DateNormalizer(props.dateFormats()), se, recorder, written);
    }

    @Bean(destroyMethod = "")
    @StepScope
    ItemStreamWriter<AnnualTransaction> annualWriter(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId,
            @Value("#{stepExecution}") StepExecution se) {
        JdbcBatchItemWriter<AnnualTransaction> jdbc = new JdbcBatchItemWriterBuilder<AnnualTransaction>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO annual_transaction
                            (job_instance_id, line_number, cuenta_id, tx_date, tipo, monto, descripcion, dedup_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """)
                .itemPreparedStatementSetter((t, ps) -> {
                    ps.setLong(1, instanceId);
                    ps.setLong(2, t.lineNumber());
                    ps.setLong(3, t.cuentaId());
                    ps.setDate(4, Date.valueOf(t.date()));
                    ps.setString(5, t.tipo());
                    ps.setBigDecimal(6, t.monto());
                    ps.setString(7, t.descripcion());
                    ps.setString(8, t.dedupKey());
                })
                .assertUpdates(false)
                .build();
        return SimulatedFailureWriter.wrap(jdbc, se, explorer);
    }

    // ------------------------------------------------------------------ consolidación
    @Bean
    Step consolidateStep() {
        return new StepBuilder("consolidateStep", jobRepository)
                .<StatementRow, StatementRow>chunk(props.chunkSize(), txManager)
                .reader(statementReader(null))
                .writer(statementWriter(null))
                .startLimit(5)
                .allowStartIfComplete(false)
                .build();
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<StatementRow> statementReader(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        return new JdbcCursorItemReaderBuilder<StatementRow>()
                .name("statementReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT cuenta_id, EXTRACT(YEAR FROM tx_date)::int AS yr,
                               COALESCE(SUM(monto) FILTER (WHERE tipo = 'deposito'), 0) AS deposits,
                               COALESCE(SUM(monto) FILTER (WHERE tipo = 'retiro'), 0) AS withdrawals,
                               COALESCE(SUM(monto) FILTER (WHERE tipo = 'compra'), 0) AS purchases,
                               COUNT(*) AS n
                        FROM annual_transaction WHERE job_instance_id = ?
                        GROUP BY cuenta_id, yr ORDER BY cuenta_id, yr
                        """)
                .preparedStatementSetter(ps -> ps.setLong(1, instanceId))
                .rowMapper((rs, i) -> new StatementRow(rs.getLong("cuenta_id"), rs.getInt("yr"),
                        rs.getBigDecimal("deposits"), rs.getBigDecimal("withdrawals"), rs.getBigDecimal("purchases"),
                        rs.getLong("n")))
                .build();
    }

    @Bean
    @StepScope
    JdbcBatchItemWriter<StatementRow> statementWriter(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        return new JdbcBatchItemWriterBuilder<StatementRow>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO annual_statement
                            (job_instance_id, cuenta_id, statement_year, total_deposits, total_withdrawals,
                             total_purchases, final_balance, tx_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (job_instance_id, cuenta_id, statement_year) DO UPDATE SET
                            total_deposits = EXCLUDED.total_deposits, total_withdrawals = EXCLUDED.total_withdrawals,
                            total_purchases = EXCLUDED.total_purchases, final_balance = EXCLUDED.final_balance,
                            tx_count = EXCLUDED.tx_count
                        """)
                .itemPreparedStatementSetter((r, ps) -> {
                    ps.setLong(1, instanceId);
                    ps.setLong(2, r.cuentaId());
                    ps.setInt(3, r.year());
                    ps.setBigDecimal(4, r.deposits());
                    ps.setBigDecimal(5, r.withdrawals());
                    ps.setBigDecimal(6, r.purchases());
                    ps.setBigDecimal(7, r.finalBalance());
                    ps.setLong(8, r.txCount());
                })
                .assertUpdates(false)
                .build();
    }

    // ------------------------------------------------------------------ archivos por cuenta
    @Bean
    Step exportAccountFilesStep() {
        return new StepBuilder("exportAccountFilesStep", jobRepository)
                .<StatementRow, AccountStatement>chunk(props.chunkSize(), txManager)
                .reader(statementReader(null))
                .processor(accountDetailProcessor(null))
                .writer(accountFileWriter(null))
                .startLimit(5)
                .build();
    }

    @Bean
    @StepScope
    ItemProcessor<StatementRow, AccountStatement> accountDetailProcessor(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return row -> {
            List<AccountStatement.Line> lines = jdbc.query("""
                    SELECT tx_date, tipo, monto, descripcion FROM annual_transaction
                    WHERE job_instance_id = ? AND cuenta_id = ? AND EXTRACT(YEAR FROM tx_date) = ?
                    ORDER BY tx_date, line_number
                    """, (rs, i) -> new AccountStatement.Line(rs.getDate("tx_date").toLocalDate(),
                    rs.getString("tipo"), rs.getBigDecimal("monto"), rs.getString("descripcion")),
                    instanceId, row.cuentaId(), row.year());
            return new AccountStatement(row.cuentaId(), row.year(), row.deposits(), row.withdrawals(),
                    row.purchases(), row.finalBalance(), row.txCount(), lines);
        };
    }

    @Bean
    @StepScope
    ItemWriter<AccountStatement> accountFileWriter(@Value("#{jobParameters['runId']}") String runId) {
        return new AccountFileWriter(Path.of(props.outputDir(), "annual", runId));
    }
}

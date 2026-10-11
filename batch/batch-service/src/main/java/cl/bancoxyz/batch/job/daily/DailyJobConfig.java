package cl.bancoxyz.batch.job.daily;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
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
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import cl.bancoxyz.batch.common.CsvReaders;
import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.DateNormalizer;
import cl.bancoxyz.batch.common.FileValidationTasklet;
import cl.bancoxyz.batch.common.JobLifecycleListener;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.RejectionReason;
import cl.bancoxyz.batch.common.SimulatedFailureWriter;
import cl.bancoxyz.batch.common.StepAuditListener;
import cl.bancoxyz.batch.common.StepSupport;
import cl.bancoxyz.batch.config.BatchProperties;

/**
 * dailyTransactionReportJob: validateFile → loadMovements (PARTICIONADO por rango de líneas) →
 * summarize → exportReport → executionReport.
 * El CSV legacy de movimientos no trae cuenta, por eso el resumen es por (fecha, tipo).
 */
@Configuration
public class DailyJobConfig {

    static final String[] COLUMNS = {"id", "fecha", "monto", "tipo"};

    private final BatchProperties props;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final DataSource dataSource;
    private final StepSupport support;
    private final RejectedRecordRecorder recorder;
    private final JobExplorer explorer;

    public DailyJobConfig(BatchProperties props, JobRepository jobRepository, PlatformTransactionManager txManager,
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

    // ------------------------------------------------------------------ job
    @Bean
    Job dailyTransactionReportJob(JobLifecycleListener listener) {
        return new JobBuilder("dailyTransactionReportJob", jobRepository)
                .listener(listener)
                .start(dailyValidateFileStep())
                .next(loadMovementsStep())
                .next(dailySummarizeStep())
                .next(dailyExportReportStep())
                .next(support.executionReportStep())
                .build();
    }

    // ------------------------------------------------------------------ steps
    @Bean
    Step dailyValidateFileStep() {
        return support.validateFileStep(dailyFileValidationTasklet(null));
    }

    @Bean
    @StepScope
    Tasklet dailyFileValidationTasklet(@Value("#{jobParameters['inputFile']}") String inputFile) {
        return new FileValidationTasklet(Path.of(inputFile), COLUMNS);
    }

    /** Step manager: reparte el archivo en gridSize particiones y las ejecuta en paralelo. */
    @Bean
    Step loadMovementsStep() {
        return new StepBuilder("loadMovementsStep", jobRepository)
                .partitioner("loadMovementsWorkerStep", dailyPartitioner(null))
                .partitionHandler(dailyPartitionHandler())
                .gridSize(props.parallelism().gridSize())
                .startLimit(3)
                .build();
    }

    @Bean
    TaskExecutorPartitionHandler dailyPartitionHandler() {
        int grid = props.parallelism().gridSize();
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("daily-partition-");
        executor.setConcurrencyLimit(grid);
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(executor);
        handler.setGridSize(grid);
        handler.setStep(loadMovementsWorkerStep());
        return handler;
    }

    @Bean
    @StepScope
    Partitioner dailyPartitioner(@Value("#{jobParameters['inputFile']}") String inputFile) {
        return new LineRangePartitioner(Path.of(inputFile));
    }

    /** Step worker: lectura → validación/proceso → escritura de un rango de filas. */
    @Bean
    Step loadMovementsWorkerStep() {
        var builder = new StepBuilder("loadMovementsWorkerStep", jobRepository)
                .<CsvRow, Movement>chunk(props.chunkSize(), txManager)
                .reader(dailyMovementReader(null, null, null))
                .processor(dailyMovementProcessor(null))
                .writer(dailyMovementWriter(null, null))
                .startLimit(3)
                .allowStartIfComplete(false);
        return support.faultTolerant(builder, dailyAuditListener(null)).build();
    }

    @Bean
    @StepScope
    StepAuditListener dailyAuditListener(@Value("#{stepExecution}") StepExecution se) {
        return new StepAuditListener(se, recorder, true);
    }

    @Bean
    @StepScope
    FlatFileItemReader<CsvRow> dailyMovementReader(@Value("#{jobParameters['inputFile']}") String inputFile,
                                                   @Value("#{stepExecutionContext['fromRow']}") Long fromRow,
                                                   @Value("#{stepExecutionContext['toRow']}") Long toRow) {
        int skip = (int) (fromRow - 1);
        int max = (int) (toRow - fromRow + 1);
        return CsvReaders.reader("dailyMovementReader", Path.of(inputFile), COLUMNS, skip, max, true);
    }

    @Bean
    @StepScope
    ItemProcessor<CsvRow, Movement> dailyMovementProcessor(@Value("#{stepExecution}") StepExecution se) {
        return new MovementProcessor(new DateNormalizer(props.dateFormats()), props.anomaly().amountThreshold(), se,
                recorder);
    }

    /** Escribe movimientos válidos y, en el mismo chunk, los montos atípicos en transaction_anomaly. */
    @Bean(destroyMethod = "")
    @StepScope
    ItemStreamWriter<Movement> dailyMovementWriter(@Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId,
                                             @Value("#{stepExecution}") StepExecution se) {
        JdbcBatchItemWriter<Movement> movements = new JdbcBatchItemWriterBuilder<Movement>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO daily_transaction (job_instance_id, tx_id, line_number, tx_date, tipo, monto, outlier)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (job_instance_id, tx_id) DO NOTHING
                        """)
                .itemPreparedStatementSetter((m, ps) -> {
                    ps.setLong(1, instanceId);
                    ps.setLong(2, m.id());
                    ps.setLong(3, m.lineNumber());
                    ps.setDate(4, Date.valueOf(m.date()));
                    ps.setString(5, m.tipo());
                    ps.setBigDecimal(6, m.monto());
                    ps.setBoolean(7, m.outlier());
                })
                .assertUpdates(false)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ItemWriter<Movement> outliers = chunk -> chunk.getItems().stream().filter(Movement::outlier).forEach(m ->
                jdbc.update("""
                        INSERT INTO transaction_anomaly (job_instance_id, line_number, source_id, anomaly_type, detail)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (job_instance_id, line_number, anomaly_type) DO NOTHING
                        """, instanceId, m.lineNumber(), String.valueOf(m.id()), RejectionReason.OUTLIER_AMOUNT.name(),
                        "monto " + m.monto() + " > umbral " + props.anomaly().amountThreshold()));
        CompositeItemWriter<Movement> composite = new CompositeItemWriter<>(movements, outliers);
        return SimulatedFailureWriter.wrap(composite, se, explorer);
    }

    // ------------------------------------------------------------------ resumen
    @Bean
    Step dailySummarizeStep() {
        return new StepBuilder("summarizeStep", jobRepository)
                .<SummaryRow, SummaryRow>chunk(props.chunkSize(), txManager)
                .reader(dailySummaryReader(null))
                .writer(dailySummaryWriter(null))
                .startLimit(3)
                .allowStartIfComplete(false)
                .build();
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<SummaryRow> dailySummaryReader(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        return new JdbcCursorItemReaderBuilder<SummaryRow>()
                .name("dailySummaryReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT tx_date, tipo, COUNT(*) AS n, SUM(monto) AS total, MAX(monto) AS max
                        FROM daily_transaction WHERE job_instance_id = ?
                        GROUP BY tx_date, tipo ORDER BY tx_date, tipo
                        """)
                .preparedStatementSetter(ps -> ps.setLong(1, instanceId))
                .rowMapper((rs, i) -> new SummaryRow(rs.getDate("tx_date").toLocalDate(), rs.getString("tipo"),
                        rs.getLong("n"), rs.getBigDecimal("total"), rs.getBigDecimal("max")))
                .build();
    }

    @Bean
    @StepScope
    JdbcBatchItemWriter<SummaryRow> dailySummaryWriter(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        return new JdbcBatchItemWriterBuilder<SummaryRow>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO daily_transaction_summary
                            (job_instance_id, summary_date, tipo, tx_count, total_amount, max_amount)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (job_instance_id, summary_date, tipo) DO UPDATE SET
                            tx_count = EXCLUDED.tx_count, total_amount = EXCLUDED.total_amount,
                            max_amount = EXCLUDED.max_amount
                        """)
                .itemPreparedStatementSetter((r, ps) -> {
                    ps.setLong(1, instanceId);
                    ps.setDate(2, Date.valueOf(r.date()));
                    ps.setString(3, r.tipo());
                    ps.setLong(4, r.count());
                    ps.setBigDecimal(5, r.total());
                    ps.setBigDecimal(6, r.max());
                })
                .assertUpdates(false)
                .build();
    }

    // ------------------------------------------------------------------ CSV de reporte
    @Bean
    Step dailyExportReportStep() {
        return new StepBuilder("exportReportStep", jobRepository)
                .<SummaryRow, SummaryRow>chunk(props.chunkSize(), txManager)
                .reader(dailyReportReader(null))
                .writer(dailyReportWriter(null))
                .startLimit(3)
                .build();
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<SummaryRow> dailyReportReader(
            @Value("#{stepExecution.jobExecution.jobInstance.id}") Long instanceId) {
        return new JdbcCursorItemReaderBuilder<SummaryRow>()
                .name("dailyReportReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT summary_date, tipo, tx_count, total_amount, max_amount
                        FROM daily_transaction_summary WHERE job_instance_id = ? ORDER BY summary_date, tipo
                        """)
                .preparedStatementSetter(ps -> ps.setLong(1, instanceId))
                .rowMapper((rs, i) -> new SummaryRow(rs.getDate("summary_date").toLocalDate(), rs.getString("tipo"),
                        rs.getLong("tx_count"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("max_amount")))
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemWriter<SummaryRow> dailyReportWriter(@Value("#{jobParameters['runId']}") String runId) {
        Path dir = Path.of(props.outputDir(), "daily");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear " + dir, e);
        }
        return new FlatFileItemWriterBuilder<SummaryRow>()
                .name("dailyReportWriter")
                .resource(new FileSystemResource(dir.resolve("daily_transaction_report_" + runId + ".csv")))
                .shouldDeleteIfExists(true)
                .headerCallback(w -> w.write("fecha,tipo,cantidad,monto_total,monto_maximo"))
                .delimited().delimiter(",")
                .fieldExtractor(r -> new Object[] {r.date(), r.tipo(), r.count(), r.total().toPlainString(),
                        r.max().toPlainString()})
                .build();
    }
}

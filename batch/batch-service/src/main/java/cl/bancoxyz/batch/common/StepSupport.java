package cl.bancoxyz.batch.common;

import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.FaultTolerantStepBuilder;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import cl.bancoxyz.batch.config.BatchProperties;

/** Política de tolerancia a fallos común a los tres jobs y steps utilitarios. */
@Component
public class StepSupport {

    private final BatchProperties props;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final ExecutionReporter reporter;

    public StepSupport(BatchProperties props, JobRepository jobRepository, PlatformTransactionManager txManager,
                       ExecutionReporter reporter) {
        this.props = props;
        this.jobRepository = jobRepository;
        this.txManager = txManager;
        this.reporter = reporter;
    }

    /**
     * skip: errores de datos (FlatFileParseException, InvalidRecordException) hasta skipLimit.
     * retry: fallos transitorios de BD con backoff exponencial hasta retryLimit.
     */
    public <I, O> FaultTolerantStepBuilder<I, O> faultTolerant(SimpleStepBuilder<I, O> builder,
                                                               StepAuditListener audit) {
        BatchProperties.Backoff b = props.backoff();
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(b.initial().toMillis());
        backoff.setMultiplier(b.multiplier());
        backoff.setMaxInterval(b.max().toMillis());
        return builder.faultTolerant()
                .skip(FlatFileParseException.class)
                .skip(InvalidRecordException.class)
                .skipLimit(props.skipLimit())
                // Un registro inválido en el processor se omite SIN rollback del chunk: el processor es idempotente,
                // y evitar el rollback+reproceso por cada rechazo es clave con datasets muy sucios (benchmark).
                .noRollback(InvalidRecordException.class)
                .retry(TransientDataAccessException.class)
                .retry(CannotGetJdbcConnectionException.class)
                .retryLimit(props.retryLimit())
                .backOffPolicy(backoff)
                .listener((org.springframework.batch.core.SkipListener<Object, Object>) audit)
                .listener((org.springframework.retry.RetryListener) audit);
    }

    /** El tasklet debe ser un bean @StepScope: el archivo depende del parámetro de job inputFile. */
    public Step validateFileStep(Tasklet fileValidationTasklet) {
        return new StepBuilder("validateFileStep", jobRepository)
                .tasklet(fileValidationTasklet, txManager)
                .build();
    }

    public Step executionReportStep() {
        return new StepBuilder("executionReportStep", jobRepository)
                .tasklet(new ExecutionReportTasklet(reporter), txManager)
                .allowStartIfComplete(false)
                .build();
    }
}

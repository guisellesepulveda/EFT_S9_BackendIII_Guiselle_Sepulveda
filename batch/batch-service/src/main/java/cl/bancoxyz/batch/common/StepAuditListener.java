package cl.bancoxyz.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;

/**
 * Listener por ejecución de step (se crea con @StepScope):
 * persiste cada registro omitido (SkipListener) y cuenta los reintentos (RetryListener).
 */
public class StepAuditListener implements SkipListener<Object, Object>, RetryListener {

    public static final String RETRY_COUNT_KEY = "retryCount";

    private static final Logger log = LoggerFactory.getLogger(StepAuditListener.class);

    private final StepExecution stepExecution;
    private final RejectedRecordRecorder recorder;
    private final boolean recordAnomalies;

    public StepAuditListener(StepExecution stepExecution, RejectedRecordRecorder recorder, boolean recordAnomalies) {
        this.stepExecution = stepExecution;
        this.recorder = recorder;
        this.recordAnomalies = recordAnomalies;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof FlatFileParseException ffe) {
            recorder.recordSkip(stepExecution, ffe.getLineNumber(), ffe.getInput(),
                    RejectionReason.MALFORMED_LINE.name(), t.getMessage());
            log.warn("Línea {} omitida en lectura: {}", ffe.getLineNumber(), t.getMessage());
        } else {
            log.warn("Registro omitido en lectura: {}", t.toString());
        }
    }

    @Override
    public void onSkipInProcess(Object item, Throwable t) {
        if (t instanceof InvalidRecordException ire) {
            SourceLine src = ire.source();
            recorder.recordSkip(stepExecution, src.lineNumber(), src.rawLine(), ire.reasonCodes(), ire.reasonText());
            if (recordAnomalies) {
                String sourceId = src instanceof CsvRow row ? row.get(0) : null;
                ire.reasons().forEach(r ->
                        recorder.recordAnomaly(stepExecution, src.lineNumber(), sourceId, r, r.description()));
            }
            log.debug("Línea {} rechazada: {}", src.lineNumber(), ire.reasonCodes());
        }
    }

    @Override
    public void onSkipInWrite(Object item, Throwable t) {
        if (item instanceof SourceLine src) {
            recorder.recordSkip(stepExecution, src.lineNumber(), src.rawLine(), "WRITE_ERROR", t.toString());
        }
    }

    @Override
    public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        // El RetryTemplate también ve excepciones NO reintentables (p. ej. SkipLimitExceeded): no son un retry
        if (!(throwable instanceof TransientDataAccessException || throwable instanceof CannotGetJdbcConnectionException)) {
            return;
        }
        long n = stepExecution.getExecutionContext().getLong(RETRY_COUNT_KEY, 0L) + 1;
        stepExecution.getExecutionContext().putLong(RETRY_COUNT_KEY, n);
        log.warn("Fallo transitorio en step {} (reintento #{} de la operación): {}", stepExecution.getStepName(),
                context.getRetryCount(), throwable.toString());
    }
}

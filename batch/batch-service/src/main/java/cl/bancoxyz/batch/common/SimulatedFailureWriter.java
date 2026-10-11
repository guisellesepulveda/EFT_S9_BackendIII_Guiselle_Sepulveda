package cl.bancoxyz.batch.common;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * Decorador de writer para demostrar restart y retry (parámetros de job NO identificantes):
 * <ul>
 *   <li>{@code simulateFailureAtChunk=N}: la escritura del chunk N lanza {@link SimulatedFailureException}.
 *       Solo ocurre en la PRIMERA ejecución de la instancia; así, relanzar con los mismos parámetros continúa
 *       desde el último chunk confirmado.</li>
 *   <li>{@code simulateTransientFailures=K}: las K primeras escrituras lanzan un fallo transitorio de BD,
 *       que debe resolverse con retry + backoff exponencial.</li>
 * </ul>
 */
public class SimulatedFailureWriter<T> implements ItemStreamWriter<T> {

    public static final String FAIL_AT_CHUNK = "simulateFailureAtChunk";
    public static final String TRANSIENT_FAILURES = "simulateTransientFailures";

    private static final Logger log = LoggerFactory.getLogger(SimulatedFailureWriter.class);

    private final ItemWriter<T> delegate;
    private final long failAtChunk;
    private final long transientFailures;
    private final AtomicInteger chunkCounter = new AtomicInteger();
    private final AtomicInteger transientCounter = new AtomicInteger();

    public SimulatedFailureWriter(ItemWriter<T> delegate, long failAtChunk, long transientFailures) {
        this.delegate = delegate;
        this.failAtChunk = failAtChunk;
        this.transientFailures = transientFailures;
    }

    /**
     * Siempre devuelve el decorador (con los fallos desactivados si los parámetros no los piden), de modo que el
     * tipo del bean sea estable entre ejecuciones y el stream del delegate (si lo es) se abra/cierre correctamente.
     */
    public static <T> SimulatedFailureWriter<T> wrap(ItemWriter<T> delegate, StepExecution se, JobExplorer explorer) {
        long failAt = se.getJobParameters().getLong(FAIL_AT_CHUNK, 0L);
        long transients = se.getJobParameters().getLong(TRANSIENT_FAILURES, 0L);
        boolean firstExecution = failAt <= 0
                || explorer.getJobExecutions(se.getJobExecution().getJobInstance()).size() <= 1;
        log.debug("wrap writer: failAtChunk={} (primera ejecución: {}), transientFailures={}", failAt, firstExecution,
                transients);
        return new SimulatedFailureWriter<>(delegate, firstExecution ? failAt : 0, transients);
    }

    @Override
    public void open(ExecutionContext executionContext) {
        if (delegate instanceof ItemStream stream) {
            stream.open(executionContext);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) {
        if (delegate instanceof ItemStream stream) {
            stream.update(executionContext);
        }
    }

    @Override
    public void close() {
        if (delegate instanceof ItemStream stream) {
            stream.close();
        }
    }

    @Override
    public void write(Chunk<? extends T> chunk) throws Exception {
        if (chunk.isEmpty()) {
            return;
        }
        if (transientFailures > 0 && transientCounter.incrementAndGet() <= transientFailures) {
            log.warn("[SIMULACIÓN] fallo transitorio de BD #{}", transientCounter.get());
            throw new TransientDataAccessResourceException("Fallo transitorio simulado #" + transientCounter.get());
        }
        int n = chunkCounter.incrementAndGet();
        if (failAtChunk > 0 && n == failAtChunk) {
            log.error("[SIMULACIÓN] fallo forzado al escribir el chunk {}", n);
            throw new SimulatedFailureException("Fallo simulado en el chunk " + n);
        }
        delegate.write(chunk);
    }
}

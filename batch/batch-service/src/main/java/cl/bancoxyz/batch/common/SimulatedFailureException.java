package cl.bancoxyz.batch.common;

/** Fallo inyectado por simulateFailureAtChunk; no es skippable ni reintentable, así que el step queda FAILED. */
public class SimulatedFailureException extends RuntimeException {

    public SimulatedFailureException(String message) {
        super(message);
    }
}

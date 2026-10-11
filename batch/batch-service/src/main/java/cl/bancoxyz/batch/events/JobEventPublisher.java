package cl.bancoxyz.batch.events;

/** Puerto de salida de eventos de ciclo de vida de jobs (implementación Kafka o no-op). */
public interface JobEventPublisher {

    String JOB_COMPLETED = "batch.job.completed";
    String JOB_FAILED = "batch.job.failed";

    void publish(String eventType, JobEventPayload payload);

    /** Alerta operativa/de seguridad (tópico security.alerts), p. ej. anomalías detectadas en los datos procesados. */
    void publishAlert(String severity, String alertType, String subject, String detail);
}

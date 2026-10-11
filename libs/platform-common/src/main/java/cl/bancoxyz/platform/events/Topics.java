package cl.bancoxyz.platform.events;

import java.util.List;

/** Catálogo de tópicos. Todos con 3 particiones para poder escalar consumidores; clave = identificador del agregado. */
public final class Topics {

    // Saga de pagos (coreografiada). La clave de todos estos tópicos es el paymentId → orden por pago garantizado
    public static final String TRANSACTIONS_REQUESTED = "transactions.requested";   // payments → accounts
    public static final String TRANSACTIONS_DEBITED = "transactions.debited";       // accounts → accounts (crédito), payments
    public static final String TRANSACTIONS_CREDITED = "transactions.credited";     // accounts → payments
    public static final String TRANSACTIONS_COMPLETED = "transactions.completed";   // payments → accounts (proyección), customers (notificaciones)
    public static final String TRANSACTIONS_FAILED = "transactions.failed";         // accounts/payments → payments (compensación)

    public static final String SECURITY_ALERTS = "security.alerts";                 // auth-server, bff-atm, batch → customers (alertas)
    public static final String ACCOUNTS_LIFECYCLE = "accounts.lifecycle";           // accounts → customers (perfil)
    public static final String BATCH_JOB_EVENTS = "batch.job.events";               // batch-service → monitoreo

    public static final int PARTITIONS = 3;
    public static final String DLT_SUFFIX = ".DLT";

    public static final List<String> ALL = List.of(TRANSACTIONS_REQUESTED, TRANSACTIONS_DEBITED, TRANSACTIONS_CREDITED,
            TRANSACTIONS_COMPLETED, TRANSACTIONS_FAILED, SECURITY_ALERTS, ACCOUNTS_LIFECYCLE, BATCH_JOB_EVENTS);

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}

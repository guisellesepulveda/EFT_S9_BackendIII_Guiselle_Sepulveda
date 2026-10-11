package cl.bancoxyz.platform.events;

public final class EventTypes {

    public static final String TRANSACTION_REQUESTED = "transaction.requested";
    public static final String TRANSACTION_DEBITED = "transaction.debited";
    public static final String TRANSACTION_CREDITED = "transaction.credited";
    public static final String TRANSACTION_COMPLETED = "transaction.completed";
    public static final String TRANSACTION_FAILED = "transaction.failed";
    public static final String ACCOUNT_OPENED = "account.opened";
    public static final String ACCOUNT_CLOSED = "account.closed";
    public static final String SECURITY_ALERT = "security.alert";

    private EventTypes() {
    }
}

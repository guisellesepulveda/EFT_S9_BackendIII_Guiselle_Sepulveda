package cl.bancoxyz.payments.clients;

/** Resultado de consultar una cuenta: encontrada, inexistente o desconocida por indisponibilidad (degraded). */
public record AccountLookup(AccountView account, Kind kind, String reason) {

    public enum Kind { FOUND, NOT_FOUND, DEGRADED }

    public static AccountLookup found(AccountView a) {
        return new AccountLookup(a, Kind.FOUND, null);
    }

    public static AccountLookup notFound() {
        return new AccountLookup(null, Kind.NOT_FOUND, null);
    }

    public static AccountLookup degraded(String reason) {
        return new AccountLookup(null, Kind.DEGRADED, reason);
    }

    public boolean isDegraded() {
        return kind == Kind.DEGRADED;
    }
}

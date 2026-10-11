package cl.bancoxyz.payments.clients;

/**
 * Resultado de consultar un cliente. degraded=true significa que customers-service no respondió: el dato viene de la
 * caché local (source=CACHE) o es desconocido (customer=null, source=NONE).
 */
public record CustomerLookup(CustomerView customer, boolean degraded, String source) {

    public static CustomerLookup live(CustomerView c) {
        return new CustomerLookup(c, false, "LIVE");
    }

    public static CustomerLookup cached(CustomerView c) {
        return new CustomerLookup(c, true, "CACHE");
    }

    public static CustomerLookup unknown() {
        return new CustomerLookup(null, true, "NONE");
    }
}

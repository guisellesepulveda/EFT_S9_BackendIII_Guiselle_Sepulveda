package cl.bancoxyz.batch.common;

/** Motivos de rechazo/anomalía. El nombre se persiste en batch_rejected_record.reason_code. */
public enum RejectionReason {
    MALFORMED_LINE("Línea con número de columnas distinto al esperado"),
    INVALID_ID("Identificador no numérico o vacío"),
    INVALID_ACCOUNT_ID("cuenta_id no numérico o vacío"),
    INVALID_DATE("Fecha vacía, con formato no aceptado o inexistente en el calendario"),
    UNKNOWN_TYPE("Tipo de movimiento fuera de {debito, credito}"),
    MISSING_AMOUNT("Monto vacío"),
    INVALID_AMOUNT("Monto no numérico"),
    NON_POSITIVE_AMOUNT("Monto negativo o cero"),
    MISSING_BALANCE("Saldo vacío"),
    INVALID_BALANCE("Saldo no numérico"),
    NEGATIVE_BALANCE("Saldo negativo"),
    INVALID_ACCOUNT_TYPE("Tipo de cuenta sin tasa configurada"),
    INVALID_AGE("Edad no numérica"),
    AGE_OUT_OF_RANGE("Edad fuera del rango permitido"),
    MISSING_NAME("Nombre vacío"),
    INVALID_TRANSACTION_TYPE("Transacción fuera de {deposito, retiro, compra}"),
    MISSING_DESCRIPTION("Descripción vacía"),
    DUPLICATE_RECORD("Registro duplicado por clave de negocio"),
    OUTLIER_AMOUNT("Monto atípico sobre el umbral configurado");

    private final String description;

    RejectionReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}

package cl.bancoxyz.batch.common;

import java.util.List;
import java.util.stream.Collectors;

/** Error de datos de negocio: el registro se omite (skip) y queda persistido en batch_rejected_record. */
public class InvalidRecordException extends RuntimeException {

    private final transient SourceLine source;
    private final List<RejectionReason> reasons;

    public InvalidRecordException(SourceLine source, List<RejectionReason> reasons) {
        super("Línea " + source.lineNumber() + " inválida: " + codes(reasons));
        this.source = source;
        this.reasons = List.copyOf(reasons);
    }

    public SourceLine source() {
        return source;
    }

    public List<RejectionReason> reasons() {
        return reasons;
    }

    public String reasonCodes() {
        return codes(reasons);
    }

    public String reasonText() {
        return reasons.stream().map(RejectionReason::description).collect(Collectors.joining("; "));
    }

    private static String codes(List<RejectionReason> reasons) {
        return reasons.stream().map(Enum::name).collect(Collectors.joining(";"));
    }
}

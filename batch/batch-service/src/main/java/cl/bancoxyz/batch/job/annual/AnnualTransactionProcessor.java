package cl.bancoxyz.batch.job.annual;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ItemProcessor;

import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.DateNormalizer;
import cl.bancoxyz.batch.common.InvalidRecordException;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.RejectionReason;

/**
 * Valida y normaliza movimientos anuales (decisión D-4: "depósito" → "deposito", "pago" se rechaza, monto > 0).
 * Se ejecuta en un step multi-hilo: el set de claves vistas es concurrente y el resultado agregado es
 * independiente del orden de procesamiento.
 */
public class AnnualTransactionProcessor implements ItemProcessor<CsvRow, AnnualTransaction> {

    private static final Logger log = LoggerFactory.getLogger(AnnualTransactionProcessor.class);
    static final Set<String> TYPES = Set.of("deposito", "retiro", "compra");

    private final DateNormalizer dates;
    private final StepExecution stepExecution;
    private final RejectedRecordRecorder recorder;
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();

    public AnnualTransactionProcessor(DateNormalizer dates, StepExecution stepExecution,
                                      RejectedRecordRecorder recorder,
                                      Map<String, Long> alreadyWritten) {
        this.dates = dates;
        this.stepExecution = stepExecution;
        this.recorder = recorder;
        this.firstSeen.putAll(alreadyWritten);
    }

    static String normalizeType(String raw) {
        String lower = raw.trim().toLowerCase();
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    @Override
    public AnnualTransaction process(CsvRow row) {
        List<RejectionReason> defects = new ArrayList<>();

        Long cuentaId = null;
        try {
            cuentaId = Long.parseLong(row.get(0));
        } catch (NumberFormatException e) {
            defects.add(RejectionReason.INVALID_ACCOUNT_ID);
        }
        var date = dates.parse(row.get(1));
        if (date.isEmpty()) {
            defects.add(RejectionReason.INVALID_DATE);
        }
        String tipo = normalizeType(row.get(2));
        if (!TYPES.contains(tipo)) {
            defects.add(RejectionReason.INVALID_TRANSACTION_TYPE);
        }
        BigDecimal monto = null;
        if (row.get(3).isEmpty()) {
            defects.add(RejectionReason.MISSING_AMOUNT);
        } else {
            try {
                monto = new BigDecimal(row.get(3));
                if (monto.signum() <= 0) {
                    defects.add(RejectionReason.NON_POSITIVE_AMOUNT);
                }
            } catch (NumberFormatException e) {
                defects.add(RejectionReason.INVALID_AMOUNT);
            }
        }
        if (row.get(4).isEmpty()) {
            defects.add(RejectionReason.MISSING_DESCRIPTION);
        }
        if (!defects.isEmpty()) {
            throw new InvalidRecordException(row, defects);
        }

        // La fecha va normalizada (ISO) en la clave: 2024/03/22 y 2024-03-22 son el mismo movimiento
        String key = String.join("|", String.valueOf(cuentaId), date.get().toString(), tipo, monto.toPlainString(),
                row.get(4));
        // Idempotente ante rollback/reproceso de la misma línea (ver InterestProcessor)
        Long first = firstSeen.putIfAbsent(key, row.lineNumber());
        if (first != null && first != row.lineNumber()) {
            log.info("Línea {}: movimiento duplicado ({}), se filtra", row.lineNumber(), key);
            recorder.recordFiltered(stepExecution, row, RejectionReason.DUPLICATE_RECORD, "duplicado de: " + key);
            return null;
        }
        return new AnnualTransaction(row.lineNumber(), row.rawLine(), cuentaId, date.get(), tipo, monto, row.get(4),
                key);
    }
}

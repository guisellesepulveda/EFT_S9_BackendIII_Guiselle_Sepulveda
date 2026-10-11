package cl.bancoxyz.batch.job.interest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ItemProcessor;

import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.InvalidRecordException;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.RejectionReason;

/**
 * Valida la línea, filtra duplicados exactos (clave de negocio = fila completa, decisión D-2) y calcula el interés.
 * {@code alreadyWritten} (clave → línea) se precarga con lo ya confirmado de la instancia para que un restart conserve
 * la deduplicación.
 */
public class InterestProcessor implements ItemProcessor<CsvRow, InterestCalculation> {

    private static final Logger log = LoggerFactory.getLogger(InterestProcessor.class);

    private final Map<String, BigDecimal> rates;
    private final int minAge;
    private final int maxAge;
    private final Periodo periodo;
    private final StepExecution stepExecution;
    private final RejectedRecordRecorder recorder;
    /** clave de negocio → línea de su primera aparición (ver nota de idempotencia en process()). */
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();

    public InterestProcessor(Map<String, BigDecimal> rates, int minAge, int maxAge, Periodo periodo,
                             StepExecution stepExecution, RejectedRecordRecorder recorder,
                             Map<String, Long> alreadyWritten) {
        this.rates = rates;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.periodo = periodo;
        this.stepExecution = stepExecution;
        this.recorder = recorder;
        this.firstSeen.putAll(alreadyWritten);
    }

    @Override
    public InterestCalculation process(CsvRow row) {
        List<RejectionReason> defects = new ArrayList<>();

        Long cuentaId = null;
        try {
            cuentaId = Long.parseLong(row.get(0));
        } catch (NumberFormatException e) {
            defects.add(RejectionReason.INVALID_ACCOUNT_ID);
        }
        if (row.get(1).isEmpty()) {
            defects.add(RejectionReason.MISSING_NAME);
        }
        BigDecimal saldo = null;
        if (row.get(2).isEmpty()) {
            defects.add(RejectionReason.MISSING_BALANCE);
        } else {
            try {
                saldo = new BigDecimal(row.get(2));
                if (saldo.signum() < 0) {
                    defects.add(RejectionReason.NEGATIVE_BALANCE);
                }
            } catch (NumberFormatException e) {
                defects.add(RejectionReason.INVALID_BALANCE);
            }
        }
        if (row.get(3).isEmpty()) {
            // D-5: la edad no interviene en el cálculo; edad vacía es advertencia, no rechazo
            log.debug("Línea {}: edad vacía (advertencia)", row.lineNumber());
        } else {
            try {
                int age = Integer.parseInt(row.get(3));
                if (age < minAge || age > maxAge) {
                    defects.add(RejectionReason.AGE_OUT_OF_RANGE);
                }
            } catch (NumberFormatException e) {
                defects.add(RejectionReason.INVALID_AGE);
            }
        }
        String tipo = row.get(4);
        BigDecimal rate = rates.get(tipo);
        if (rate == null) {
            defects.add(RejectionReason.INVALID_ACCOUNT_TYPE);
        }
        if (!defects.isEmpty()) {
            throw new InvalidRecordException(row, defects);
        }

        String key = String.join("|", row.get(0), row.get(1), row.get(2), row.get(3), tipo);
        // Idempotencia: si el chunk hace rollback (retry/restart) y esta MISMA línea se reprocesa, no es un duplicado.
        // Solo lo es una línea distinta con la misma clave que ya apareció antes.
        Long first = firstSeen.putIfAbsent(key, row.lineNumber());
        if (first != null && first != row.lineNumber()) {
            log.info("Línea {}: registro duplicado ({}), se filtra", row.lineNumber(), key);
            recorder.recordFiltered(stepExecution, row, RejectionReason.DUPLICATE_RECORD, "duplicado de: " + key);
            return null;
        }
        BigDecimal interes = InterestCalculator.interest(saldo, rate, periodo);
        return new InterestCalculation(row.lineNumber(), row.rawLine(), cuentaId, row.get(1), tipo, periodo, saldo,
                rate, interes, saldo.add(interes), key);
    }
}

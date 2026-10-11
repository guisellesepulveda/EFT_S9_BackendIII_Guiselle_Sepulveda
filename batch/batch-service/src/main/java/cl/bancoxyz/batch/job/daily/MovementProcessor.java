package cl.bancoxyz.batch.job.daily;

import java.math.BigDecimal;
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
 * Validación y normalización de movimientos. Acumula TODOS los defectos de la línea (no solo el primero)
 * para que el conteo por tipo de error sea comparable con docs/analisis_datos_legacy.md.
 * Devuelve null (filtra) los ids duplicados dentro de la partición; entre particiones la unicidad
 * la garantiza la PK de daily_transaction.
 */
public class MovementProcessor implements ItemProcessor<CsvRow, Movement> {

    private static final Logger log = LoggerFactory.getLogger(MovementProcessor.class);
    static final Set<String> TYPES = Set.of("debito", "credito");

    private final DateNormalizer dates;
    private final BigDecimal outlierThreshold;
    private final StepExecution stepExecution;
    private final RejectedRecordRecorder recorder;
    private final Map<Long, Long> firstSeen = new ConcurrentHashMap<>();

    public MovementProcessor(DateNormalizer dates, BigDecimal outlierThreshold, StepExecution stepExecution,
                             RejectedRecordRecorder recorder) {
        this.dates = dates;
        this.outlierThreshold = outlierThreshold;
        this.stepExecution = stepExecution;
        this.recorder = recorder;
    }

    @Override
    public Movement process(CsvRow row) {
        List<RejectionReason> defects = new ArrayList<>();

        Long id = null;
        try {
            id = Long.parseLong(row.get(0));
        } catch (NumberFormatException e) {
            defects.add(RejectionReason.INVALID_ID);
        }
        var date = dates.parse(row.get(1));
        if (date.isEmpty()) {
            defects.add(RejectionReason.INVALID_DATE);
        }
        BigDecimal amount = null;
        String rawAmount = row.get(2);
        if (rawAmount.isEmpty()) {
            defects.add(RejectionReason.MISSING_AMOUNT);
        } else {
            try {
                amount = new BigDecimal(rawAmount);
                if (amount.signum() <= 0) {
                    defects.add(RejectionReason.NON_POSITIVE_AMOUNT);
                }
            } catch (NumberFormatException e) {
                defects.add(RejectionReason.INVALID_AMOUNT);
            }
        }
        String tipo = row.get(3);
        if (!TYPES.contains(tipo)) {
            defects.add(RejectionReason.UNKNOWN_TYPE);
        }
        if (!defects.isEmpty()) {
            throw new InvalidRecordException(row, defects);
        }
        // Idempotente ante rollback/reproceso de la misma línea (ver InterestProcessor)
        Long first = firstSeen.putIfAbsent(id, row.lineNumber());
        if (first != null && first != row.lineNumber()) {
            log.info("Línea {}: id {} duplicado, se filtra", row.lineNumber(), id);
            recorder.recordFiltered(stepExecution, row, RejectionReason.DUPLICATE_RECORD, "id duplicado: " + id);
            return null;
        }
        return new Movement(row.lineNumber(), row.rawLine(), id, date.get(), tipo, amount,
                amount.compareTo(outlierThreshold) > 0);
    }
}

package cl.bancoxyz.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.test.MetaDataInstanceFactory;

import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.InvalidRecordException;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.RejectionReason;
import cl.bancoxyz.batch.job.interest.InterestCalculation;
import cl.bancoxyz.batch.job.interest.InterestCalculator;
import cl.bancoxyz.batch.job.interest.InterestProcessor;
import cl.bancoxyz.batch.job.interest.Periodo;

class InterestTest {

    private static final Map<String, BigDecimal> RATES = Map.of("ahorro", new BigDecimal("0.0250"),
            "prestamo", new BigDecimal("0.1200"), "hipoteca", new BigDecimal("0.0600"));

    private InterestProcessor processor(Periodo p, Map<String, Long> seen) {
        return new InterestProcessor(RATES, 18, 100, p, MetaDataInstanceFactory.createStepExecution(),
                mock(RejectedRecordRecorder.class), seen);
    }

    private static CsvRow row(long line, String cuenta, String nombre, String saldo, String edad, String tipo) {
        return new CsvRow(line, String.join(",", cuenta, nombre, saldo, edad, tipo),
                new String[] {cuenta, nombre, saldo, edad, tipo});
    }

    @ParameterizedTest
    @CsvSource({
            "5000, 0.0250, MENSUAL, 10.42",      // 5000 × 0.025 / 12 = 10.4166… → 10.42
            "5000, 0.0250, TRIMESTRAL, 31.25",   // 5000 × 0.025 / 4
            "8000, 0.1200, MENSUAL, 80.00",
            "8000, 0.1200, TRIMESTRAL, 240.00",
            "7000, 0.0600, MENSUAL, 35.00",
            "12000, 0.0250, MENSUAL, 25.00",
            "1, 0.0250, MENSUAL, 0.00",          // 0.00208 → 0.00
            "1000.05, 0.0600, MENSUAL, 5.00"     // 5.00025 → 5.00
    })
    void interestFormula(String saldo, String rate, Periodo periodo, String expected) {
        assertThat(InterestCalculator.interest(new BigDecimal(saldo), new BigDecimal(rate), periodo))
                .isEqualByComparingTo(expected);
    }

    @Test
    void halfUpRounding() {
        // 1000.50 × 0.0250 / 12 = 2.084375 -> 2.08 ; 3000 × 0.0250 / 12 = 6.25 exacto
        assertThat(InterestCalculator.interest(new BigDecimal("1000.50"), new BigDecimal("0.0250"), Periodo.MENSUAL))
                .isEqualByComparingTo("2.08");
        // 6.125 -> 6.13 (HALF_UP, no banker's rounding)
        assertThat(InterestCalculator.interest(new BigDecimal("2940"), new BigDecimal("0.0250"), Periodo.MENSUAL))
                .isEqualByComparingTo("6.13");
    }

    @Test
    void periodoParsingIsStrict() {
        assertThat(Periodo.parse("mensual")).isEqualTo(Periodo.MENSUAL);
        assertThatThrownBy(() -> Periodo.parse("ANUAL")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processorCalculatesAndComputesNewBalance() {
        InterestCalculation c = processor(Periodo.MENSUAL, Map.of()).process(row(2, "101", "Jane", "8000", "25", "prestamo"));
        assertThat(c.interes()).isEqualByComparingTo("80.00");
        assertThat(c.saldoNuevo()).isEqualByComparingTo("8080.00");
        assertThat(c.tasaAnual()).isEqualByComparingTo("0.12");
    }

    @Test
    void emptyAgeIsAWarningNotARejection() {
        assertThat(processor(Periodo.MENSUAL, Map.of()).process(row(2, "101", "Jane", "8000", "", "ahorro"))).isNotNull();
    }

    @Test
    void rejectsMissingBalanceAgeOutOfRangeAndUnknownType() {
        InterestProcessor p = processor(Periodo.MENSUAL, Map.of());
        assertThatThrownBy(() -> p.process(row(2, "1", "A", "", "30", "ahorro")))
                .isInstanceOfSatisfying(InvalidRecordException.class,
                        e -> assertThat(e.reasons()).containsExactly(RejectionReason.MISSING_BALANCE));
        assertThatThrownBy(() -> p.process(row(3, "1", "A", "5000", "150", "ahorro")))
                .isInstanceOfSatisfying(InvalidRecordException.class,
                        e -> assertThat(e.reasons()).containsExactly(RejectionReason.AGE_OUT_OF_RANGE));
        assertThatThrownBy(() -> p.process(row(4, "1", "A", "5000", "30", "-1")))
                .isInstanceOfSatisfying(InvalidRecordException.class,
                        e -> assertThat(e.reasons()).containsExactly(RejectionReason.INVALID_ACCOUNT_TYPE));
        assertThatThrownBy(() -> p.process(row(5, "1", "A", "-5", "30", "unknown")))
                .isInstanceOfSatisfying(InvalidRecordException.class, e -> assertThat(e.reasons())
                        .containsExactly(RejectionReason.NEGATIVE_BALANCE, RejectionReason.INVALID_ACCOUNT_TYPE));
    }

    @Test
    void duplicateByFullRowIsFilteredButSameAccountWithOtherDataIsNot() {
        InterestProcessor p = processor(Periodo.MENSUAL, Map.of());
        assertThat(p.process(row(2, "101", "Jane", "5000", "45", "ahorro"))).isNotNull();
        assertThat(p.process(row(3, "101", "Jane", "5000", "45", "ahorro"))).isNull();           // duplicado exacto
        assertThat(p.process(row(4, "101", "Jane", "5000", "46", "ahorro"))).isNotNull();        // misma cuenta, otra edad
    }

    @Test
    void reprocessingTheSameLineAfterARollbackIsNotADuplicate() {
        // Regresión: un retry/rollback del chunk reprocesa las mismas líneas; no deben filtrarse como duplicadas
        InterestProcessor p = processor(Periodo.MENSUAL, Map.of());
        CsvRow r = row(7, "101", "Jane", "5000", "45", "ahorro");
        assertThat(p.process(r)).isNotNull();
        assertThat(p.process(r)).isNotNull();
        assertThat(p.process(row(9, "101", "Jane", "5000", "45", "ahorro"))).isNull(); // otra línea, misma clave
    }

    @Test
    void preloadedKeysSurviveARestart() {
        InterestProcessor p = processor(Periodo.MENSUAL, Map.of("101|Jane|5000|45|ahorro", 2L));
        assertThat(p.process(row(2, "101", "Jane", "5000", "45", "ahorro"))).isNotNull(); // su propia línea ya confirmada
        assertThat(p.process(row(30, "101", "Jane", "5000", "45", "ahorro"))).isNull();    // duplicado posterior
    }
}

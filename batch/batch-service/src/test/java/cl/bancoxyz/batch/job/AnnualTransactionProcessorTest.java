package cl.bancoxyz.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.test.MetaDataInstanceFactory;

import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.DateNormalizer;
import cl.bancoxyz.batch.common.InvalidRecordException;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.RejectionReason;
import cl.bancoxyz.batch.job.annual.AnnualTransaction;
import cl.bancoxyz.batch.job.annual.AnnualTransactionProcessor;

class AnnualTransactionProcessorTest {

    private AnnualTransactionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AnnualTransactionProcessor(new DateNormalizer(List.of("yyyy-MM-dd", "yyyy/MM/dd")),
                MetaDataInstanceFactory.createStepExecution(), mock(RejectedRecordRecorder.class), Map.of());
    }

    private static CsvRow row(long line, String cuenta, String fecha, String tipo, String monto, String desc) {
        return new CsvRow(line, String.join(",", cuenta, fecha, tipo, monto, desc),
                new String[] {cuenta, fecha, tipo, monto, desc});
    }

    @Test
    void accentedDepositIsNormalized() {
        AnnualTransaction t = processor.process(row(2, "101", "2024/04/30", "depósito", "2500", "Ingreso"));
        assertThat(t.tipo()).isEqualTo("deposito");
    }

    @Test
    void unknownTypeAndEmptyDescriptionAndNonPositiveAmountAreRejected() {
        assertThatThrownBy(() -> processor.process(row(2, "101", "2024-04-30", "pago", "100", "x")))
                .isInstanceOfSatisfying(InvalidRecordException.class,
                        e -> assertThat(e.reasons()).containsExactly(RejectionReason.INVALID_TRANSACTION_TYPE));
        assertThatThrownBy(() -> processor.process(row(3, "101", "2024-04-30", "retiro", "-500", "")))
                .isInstanceOfSatisfying(InvalidRecordException.class, e -> assertThat(e.reasons())
                        .containsExactly(RejectionReason.NON_POSITIVE_AMOUNT, RejectionReason.MISSING_DESCRIPTION));
        assertThatThrownBy(() -> processor.process(row(4, "101", "25-11-2024", "compra", "0", "x")))
                .isInstanceOfSatisfying(InvalidRecordException.class, e -> assertThat(e.reasons())
                        .containsExactly(RejectionReason.INVALID_DATE, RejectionReason.NON_POSITIVE_AMOUNT));
    }

    @Test
    void duplicateIsDetectedAcrossDateFormats() {
        assertThat(processor.process(row(2, "101", "2024/03/22", "compra", "1500", "Tienda"))).isNotNull();
        assertThat(processor.process(row(3, "101", "2024-03-22", "compra", "1500", "Tienda"))).isNull();
        assertThat(processor.process(row(4, "101", "2024-03-22", "compra", "1500", "Otra tienda"))).isNotNull();
    }
}

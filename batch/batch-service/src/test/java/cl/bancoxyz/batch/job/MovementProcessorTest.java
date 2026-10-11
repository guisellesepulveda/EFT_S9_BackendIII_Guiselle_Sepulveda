package cl.bancoxyz.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.test.MetaDataInstanceFactory;

import cl.bancoxyz.batch.common.CsvRow;
import cl.bancoxyz.batch.common.DateNormalizer;
import cl.bancoxyz.batch.common.InvalidRecordException;
import cl.bancoxyz.batch.common.RejectedRecordRecorder;
import cl.bancoxyz.batch.common.RejectionReason;
import cl.bancoxyz.batch.job.daily.Movement;
import cl.bancoxyz.batch.job.daily.MovementProcessor;

class MovementProcessorTest {

    private RejectedRecordRecorder recorder;
    private MovementProcessor processor;

    @BeforeEach
    void setUp() {
        recorder = mock(RejectedRecordRecorder.class);
        processor = new MovementProcessor(new DateNormalizer(List.of("yyyy-MM-dd", "yyyy/MM/dd")),
                new BigDecimal("2500"), MetaDataInstanceFactory.createStepExecution(), recorder);
    }

    private static CsvRow row(long line, String id, String fecha, String monto, String tipo) {
        return new CsvRow(line, String.join(",", id, fecha, monto, tipo), new String[] {id, fecha, monto, tipo});
    }

    @Test
    void validRowIsNormalized() {
        Movement m = processor.process(row(2, "7", "2024/03/05", "1500", "credito"));
        assertThat(m.id()).isEqualTo(7);
        assertThat(m.date().toString()).isEqualTo("2024-03-05");
        assertThat(m.monto()).isEqualByComparingTo("1500");
        assertThat(m.outlier()).isFalse();
    }

    @Test
    void amountAboveThresholdIsFlaggedAsOutlierButKept() {
        assertThat(processor.process(row(2, "1", "2024-01-01", "3000", "debito")).outlier()).isTrue();
        assertThat(processor.process(row(3, "2", "2024-01-01", "2500", "debito")).outlier()).isFalse();
    }

    @Test
    void collectsAllDefectsOfTheLine() {
        assertThatThrownBy(() -> processor.process(row(5, "9", "2024-13-01", "", "invalido")))
                .isInstanceOfSatisfying(InvalidRecordException.class, e -> assertThat(e.reasons())
                        .containsExactly(RejectionReason.INVALID_DATE, RejectionReason.MISSING_AMOUNT,
                                RejectionReason.UNKNOWN_TYPE));
    }

    @Test
    void negativeAndZeroAmountsAreRejected() {
        for (String amount : new String[] {"-200", "0"}) {
            assertThatThrownBy(() -> processor.process(row(2, "1", "2024-01-01", amount, "debito")))
                    .isInstanceOfSatisfying(InvalidRecordException.class,
                            e -> assertThat(e.reasons()).containsExactly(RejectionReason.NON_POSITIVE_AMOUNT));
        }
    }

    @Test
    void nonNumericAmountAndIdAreRejected() {
        assertThatThrownBy(() -> processor.process(row(2, "x", "2024-01-01", "abc", "debito")))
                .isInstanceOfSatisfying(InvalidRecordException.class, e -> assertThat(e.reasons())
                        .containsExactly(RejectionReason.INVALID_ID, RejectionReason.INVALID_AMOUNT));
    }

    @Test
    void duplicateIdIsFilteredAndRecorded() {
        CsvRow first = row(2, "4", "2024-01-01", "100", "debito");
        CsvRow second = row(9, "4", "2024-01-02", "200", "credito");
        assertThat(processor.process(first)).isNotNull();
        assertThat(processor.process(second)).isNull();
        verify(recorder).recordFiltered(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(second),
                org.mockito.ArgumentMatchers.eq(RejectionReason.DUPLICATE_RECORD),
                org.mockito.ArgumentMatchers.contains("4"));
    }
}

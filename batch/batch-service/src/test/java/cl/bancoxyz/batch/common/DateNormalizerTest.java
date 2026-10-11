package cl.bancoxyz.batch.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DateNormalizerTest {

    private final DateNormalizer strict = new DateNormalizer(List.of("yyyy-MM-dd", "yyyy/MM/dd"));

    @Test
    void acceptsBothConfiguredFormats() {
        assertThat(strict.parse("2024-03-05")).contains(LocalDate.of(2024, 3, 5));
        assertThat(strict.parse("2024/03/05")).contains(LocalDate.of(2024, 3, 5));
        assertThat(strict.parse(" 2024/03/05 ")).contains(LocalDate.of(2024, 3, 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "11-12-2024", "02/04/2024", "2024-13-01", "2024-02-30", "2024/00/10", "hoy", "2024-3-5"})
    void rejectsEverythingElse(String value) {
        assertThat(strict.parse(value)).isEmpty();
    }

    @Test
    void nullIsRejected() {
        assertThat(strict.parse(null)).isEmpty();
    }

    @Test
    void extendedFormatsAreConfigurable() {
        DateNormalizer extended = new DateNormalizer(List.of("yyyy-MM-dd", "yyyy/MM/dd", "dd-MM-yyyy", "dd/MM/yyyy"));
        assertThat(extended.parse("11-12-2024")).contains(LocalDate.of(2024, 12, 11));
        assertThat(extended.parse("02/04/2024")).contains(LocalDate.of(2024, 4, 2));
        assertThat(extended.parse("2024-13-01")).isEmpty();
    }
}

package cl.bancoxyz.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;

import cl.bancoxyz.batch.job.daily.LineRangePartitioner;

class LineRangePartitionerTest {

    @TempDir
    Path tmp;

    private Path file(int dataRows) throws IOException {
        StringBuilder sb = new StringBuilder("id,fecha,monto,tipo\n");
        IntStream.rangeClosed(1, dataRows).forEach(i -> sb.append(i).append(",2024-01-01,10,debito\n"));
        return Files.writeString(tmp.resolve("m" + dataRows + ".csv"), sb);
    }

    private static Map<String, long[]> ranges(Map<String, ExecutionContext> parts) {
        Map<String, long[]> r = new TreeMap<>();
        parts.forEach((k, v) -> r.put(k, new long[] {v.getLong(LineRangePartitioner.FROM_ROW), v.getLong(LineRangePartitioner.TO_ROW)}));
        return r;
    }

    @Test
    void rangesAreContiguousDisjointAndCoverAllRows() throws IOException {
        Map<String, long[]> r = ranges(new LineRangePartitioner(file(1000)).partition(4));
        assertThat(r).containsOnlyKeys("partition0", "partition1", "partition2", "partition3");
        assertThat(r.get("partition0")).containsExactly(1, 250);
        assertThat(r.get("partition3")).containsExactly(751, 1000);
    }

    @Test
    void unevenSplitStillCoversEverything() throws IOException {
        Map<String, long[]> r = ranges(new LineRangePartitioner(file(10)).partition(4));
        long covered = r.values().stream().mapToLong(a -> a[1] - a[0] + 1).sum();
        assertThat(covered).isEqualTo(10);
        long previousTo = 0;
        for (long[] range : r.values()) {
            assertThat(range[0]).isEqualTo(previousTo + 1);
            previousTo = range[1];
        }
    }

    @Test
    void gridLargerThanRowsCreatesOnlyNeededPartitions() throws IOException {
        assertThat(new LineRangePartitioner(file(3)).partition(8)).hasSize(3);
    }

    @Test
    void emptyFileStillYieldsNoCrash() throws IOException {
        assertThat(new LineRangePartitioner(file(0)).partition(4)).hasSizeLessThanOrEqualTo(1);
    }
}

package cl.bancoxyz.batch.job.daily;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

/**
 * Reparte las filas de datos del archivo en rangos contiguos [fromRow, toRow] (base 1, sin contar el encabezado),
 * uno por partición. Los nombres partition0..N-1 son estables, requisito para que un restart reutilice las particiones.
 */
public class LineRangePartitioner implements Partitioner {

    public static final String FROM_ROW = "fromRow";
    public static final String TO_ROW = "toRow";

    private static final Logger log = LoggerFactory.getLogger(LineRangePartitioner.class);

    private final Path file;

    public LineRangePartitioner(Path file) {
        this.file = file;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        long rows = countDataRows(file);
        int partitions = (int) Math.max(1, Math.min(gridSize, rows));
        long size = (rows + partitions - 1) / partitions;
        Map<String, ExecutionContext> result = new HashMap<>();
        for (int i = 0; i < partitions; i++) {
            long from = i * size + 1;
            long to = Math.min(rows, (i + 1) * size);
            if (from > to) {
                break;
            }
            ExecutionContext ctx = new ExecutionContext();
            ctx.putLong(FROM_ROW, from);
            ctx.putLong(TO_ROW, to);
            result.put("partition" + i, ctx);
            log.info("partition{} -> filas {}..{} ({} filas)", i, from, to, to - from + 1);
        }
        return result;
    }

    static long countDataRows(Path file) {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Math.max(0, r.lines().count() - 1);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo contar las filas de " + file, e);
        }
    }
}

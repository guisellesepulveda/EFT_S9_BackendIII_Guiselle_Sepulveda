package cl.bancoxyz.batch.job.annual;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Archivos de auditoría por cuenta: cuenta_{id}_{año}.json (consolidado + detalle) y .csv (detalle).
 * Idempotente: reescribe los archivos, por lo que un restart no genera duplicados.
 */
public class AccountFileWriter implements ItemWriter<AccountStatement> {

    private final Path dir;
    private final ObjectMapper json = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    public AccountFileWriter(Path dir) {
        this.dir = dir;
    }

    @Override
    public void write(Chunk<? extends AccountStatement> chunk) throws IOException {
        Files.createDirectories(dir);
        for (AccountStatement s : chunk) {
            String base = "cuenta_" + s.cuentaId() + "_" + s.year();
            try {
                json.writeValue(dir.resolve(base + ".json").toFile(), s);
                String csv = "fecha,tipo,monto,descripcion\n" + s.transactions().stream()
                        .map(t -> t.date() + "," + t.tipo() + "," + t.monto().toPlainString() + ","
                                + quote(t.descripcion()))
                        .collect(Collectors.joining("\n")) + "\n";
                Files.writeString(dir.resolve(base + ".csv"), csv, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo escribir " + base, e);
            }
        }
    }

    private static String quote(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}

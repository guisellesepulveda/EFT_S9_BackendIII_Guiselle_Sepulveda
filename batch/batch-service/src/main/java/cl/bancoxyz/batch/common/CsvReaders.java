package cl.bancoxyz.batch.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.core.io.FileSystemResource;

/** Fábrica de readers CSV estrictos (número de columnas exacto → FlatFileParseException si no coincide). */
public final class CsvReaders {

    private CsvReaders() {
    }

    /**
     * @param columns       nombres de columnas esperadas (define el número de tokens por línea)
     * @param skipDataRows  filas de datos a saltar tras el encabezado (usado por las particiones)
     * @param maxDataRows   máximo de filas a leer (0 = sin límite)
     * @param saveState     false para steps multi-hilo (el estado del reader no es confiable con varios hilos)
     */
    public static FlatFileItemReader<CsvRow> reader(String name, Path file, String[] columns,
                                                    int skipDataRows, int maxDataRows, boolean saveState) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames(columns);
        tokenizer.setStrict(true);
        LineMapper<CsvRow> mapper = (line, lineNumber) -> {
            FieldSet fs = tokenizer.tokenize(line);
            return new CsvRow(lineNumber, line, fs.getValues());
        };
        FlatFileItemReaderBuilder<CsvRow> b = new FlatFileItemReaderBuilder<CsvRow>()
                .name(name)
                .resource(new FileSystemResource(file))
                .encoding(StandardCharsets.UTF_8.name())
                .linesToSkip(1 + skipDataRows)
                .lineMapper(mapper)
                .strict(true)
                .saveState(saveState);
        if (maxDataRows > 0) {
            b.maxItemCount(maxDataRows);
        }
        return b.build();
    }
}

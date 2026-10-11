package cl.bancoxyz.batch.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/** Step inicial: el archivo existe, es legible, no está vacío y su encabezado es el esperado. */
public class FileValidationTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(FileValidationTasklet.class);

    private final Path file;
    private final String[] expectedHeader;

    public FileValidationTasklet(Path file, String[] expectedHeader) {
        this.file = file;
        this.expectedHeader = expectedHeader;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new FileValidationException("Archivo de entrada inexistente o ilegible: " + file);
        }
        String header;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            header = r.readLine();
        }
        if (header == null || header.isBlank()) {
            throw new FileValidationException("Archivo vacío: " + file);
        }
        // Un BOM UTF-8 al inicio no debe invalidar el encabezado
        String[] actual = Arrays.stream(header.replace("﻿", "").split(",", -1)).map(String::trim).toArray(String[]::new);
        if (!Arrays.equals(actual, expectedHeader)) {
            throw new FileValidationException("Encabezado inválido en " + file.getFileName() + ": esperado "
                    + Arrays.toString(expectedHeader) + " pero se encontró " + Arrays.toString(actual));
        }
        log.info("Archivo validado: {} (encabezado {})", file, Arrays.toString(actual));
        return RepeatStatus.FINISHED;
    }
}

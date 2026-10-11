package cl.bancoxyz.batch.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.repeat.RepeatStatus;

class FileValidationTaskletTest {

    private static final String[] HEADER = {"id", "fecha", "monto", "tipo"};

    @TempDir
    Path tmp;

    @Test
    void validHeaderPasses() throws Exception {
        Path f = Files.writeString(tmp.resolve("ok.csv"), "id,fecha,monto,tipo\n1,2024-01-01,10,debito\n");
        assertThat(new FileValidationTasklet(f, HEADER).execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void utf8BomIsTolerated() throws Exception {
        Path f = Files.writeString(tmp.resolve("bom.csv"), "﻿id,fecha,monto,tipo\n");
        assertThat(new FileValidationTasklet(f, HEADER).execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void wrongHeaderFails() throws IOException {
        Path f = Files.writeString(tmp.resolve("bad.csv"), "id,date,amount,type\n");
        assertThatThrownBy(() -> new FileValidationTasklet(f, HEADER).execute(null, null))
                .isInstanceOf(FileValidationException.class).hasMessageContaining("Encabezado inválido");
    }

    @Test
    void missingAndEmptyFilesFail() throws IOException {
        assertThatThrownBy(() -> new FileValidationTasklet(tmp.resolve("nope.csv"), HEADER).execute(null, null))
                .isInstanceOf(FileValidationException.class).hasMessageContaining("inexistente");
        Path empty = Files.writeString(tmp.resolve("empty.csv"), "");
        assertThatThrownBy(() -> new FileValidationTasklet(empty, HEADER).execute(null, null))
                .isInstanceOf(FileValidationException.class).hasMessageContaining("vacío");
    }
}

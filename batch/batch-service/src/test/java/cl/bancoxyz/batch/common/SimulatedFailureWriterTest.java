package cl.bancoxyz.batch.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.TransientDataAccessException;

class SimulatedFailureWriterTest {

    private final List<String> written = new ArrayList<>();
    private final ItemWriter<String> delegate = chunk -> written.addAll(chunk.getItems());

    @Test
    void failsExactlyAtTheRequestedChunk() throws Exception {
        SimulatedFailureWriter<String> w = new SimulatedFailureWriter<>(delegate, 3, 0);
        w.write(Chunk.of("a"));
        w.write(Chunk.of("b"));
        assertThatThrownBy(() -> w.write(Chunk.of("c"))).isInstanceOf(SimulatedFailureException.class);
        assertThat(written).containsExactly("a", "b");
    }

    @Test
    void transientFailuresComeFirstAndThenWritesSucceed() throws Exception {
        SimulatedFailureWriter<String> w = new SimulatedFailureWriter<>(delegate, 0, 2);
        assertThatThrownBy(() -> w.write(Chunk.of("a"))).isInstanceOf(TransientDataAccessException.class);
        assertThatThrownBy(() -> w.write(Chunk.of("a"))).isInstanceOf(TransientDataAccessException.class);
        w.write(Chunk.of("a"));
        assertThat(written).containsExactly("a");
    }

    @Test
    void emptyChunksDoNotCount() throws Exception {
        SimulatedFailureWriter<String> w = new SimulatedFailureWriter<>(delegate, 1, 0);
        w.write(new Chunk<>());
        assertThatThrownBy(() -> w.write(Chunk.of("a"))).isInstanceOf(SimulatedFailureException.class);
    }
}

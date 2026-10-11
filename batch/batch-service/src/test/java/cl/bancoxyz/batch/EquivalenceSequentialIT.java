package cl.bancoxyz.batch;

import org.springframework.test.context.TestPropertySource;

/** Sin paralelismo y con un chunk pequeño y "raro": el resultado debe ser idéntico. */
@TestPropertySource(properties = {"batch.parallelism.grid-size=1", "batch.parallelism.threads=1", "batch.chunk-size=7"})
class EquivalenceSequentialIT extends AbstractEquivalenceIT {
}

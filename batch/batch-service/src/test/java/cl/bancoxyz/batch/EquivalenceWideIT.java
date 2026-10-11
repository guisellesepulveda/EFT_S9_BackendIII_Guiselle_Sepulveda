package cl.bancoxyz.batch;

import org.springframework.test.context.TestPropertySource;

/** Más particiones e hilos que núcleos: más contención, mismo resultado. */
@TestPropertySource(properties = {"batch.parallelism.grid-size=8", "batch.parallelism.threads=8", "batch.chunk-size=33"})
class EquivalenceWideIT extends AbstractEquivalenceIT {
}

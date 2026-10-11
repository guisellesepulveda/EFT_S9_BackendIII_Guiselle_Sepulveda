package cl.bancoxyz.batch;

import org.springframework.test.context.TestPropertySource;

/** Configuración "por defecto" de despliegue: 4 particiones, 4 hilos, chunk 50. */
@TestPropertySource(properties = {"batch.parallelism.grid-size=4", "batch.parallelism.threads=4"})
class EquivalenceIT extends AbstractEquivalenceIT {
}

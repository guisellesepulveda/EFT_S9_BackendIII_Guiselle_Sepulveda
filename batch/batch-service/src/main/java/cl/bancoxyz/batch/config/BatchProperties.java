package cl.bancoxyz.batch.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros externalizables de los jobs. En despliegue se sirven desde el Config Server
 * ({@code infra/config-repo/batch-service.yml}); los valores por defecto permiten correr aislado.
 */
@ConfigurationProperties(prefix = "batch")
public record BatchProperties(
        @DefaultValue("100") int chunkSize,
        @DefaultValue("5000") int skipLimit,
        @DefaultValue("3") int retryLimit,
        @DefaultValue Backoff backoff,
        @DefaultValue Parallelism parallelism,
        @DefaultValue Files files,
        @DefaultValue("./outputs") String outputDir,
        @DefaultValue({"yyyy-MM-dd", "yyyy/MM/dd"}) List<String> dateFormats,
        @DefaultValue Anomaly anomaly,
        @DefaultValue Interest interest,
        @DefaultValue AutoRetry autoRetry,
        @DefaultValue Schedule schedule,
        @DefaultValue Events events) {

    /** Backoff exponencial para reintentos de fallos transitorios. */
    public record Backoff(
            @DefaultValue("200ms") Duration initial,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("5s") Duration max) {
    }

    /** gridSize = particiones del job diario; threads = hilos del step multi-hilo del job anual. */
    public record Parallelism(
            @DefaultValue("4") int gridSize,
            @DefaultValue("4") int threads) {
    }

    /** Rutas por defecto de los CSV legacy (se pueden sobreescribir con el parámetro de job inputFile). */
    public record Files(
            @DefaultValue("data/legacy/movimientos_financieros_diarios.csv") String movements,
            @DefaultValue("data/legacy/intereses_trimestrales.csv") String interests,
            @DefaultValue("data/legacy/estados_financieros_anuales.csv") String statements) {
    }

    public record Anomaly(
            /** Monto sobre el cual un movimiento válido se marca como OUTLIER_AMOUNT. */
            @DefaultValue("2500") BigDecimal amountThreshold,
            /** Sobre este número de anomalías la alerta a security.alerts sube de LOW a MEDIUM. */
            @DefaultValue("100") long alertHighThreshold) {
    }

    public record Interest(
            /** Tasas anuales por tipo de cuenta (valores de ejemplo; se sirven desde el Config Server). */
            Map<String, BigDecimal> annualRates,
            @DefaultValue("18") int minAge,
            @DefaultValue("100") int maxAge) {

        public Interest {
            if (annualRates == null || annualRates.isEmpty()) {
                Map<String, BigDecimal> defaults = new LinkedHashMap<>();
                defaults.put("ahorro", new BigDecimal("0.0250"));
                defaults.put("prestamo", new BigDecimal("0.1200"));
                defaults.put("hipoteca", new BigDecimal("0.0600"));
                annualRates = defaults;
            }
        }
    }

    /** Reintento automático de una instancia de job FAILED. */
    public record AutoRetry(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("30s") Duration delay) {
    }

    public record Schedule(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("0 0 2 * * *") String dailyCron,
            @DefaultValue("0 0 3 1 * *") String interestCron,
            @DefaultValue("0 0 4 1 1 *") String annualCron) {
    }

    public record Events(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("batch.job.events") String topic) {
    }
}

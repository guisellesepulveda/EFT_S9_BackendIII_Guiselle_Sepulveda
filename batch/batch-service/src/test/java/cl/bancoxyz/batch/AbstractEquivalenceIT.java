package cl.bancoxyz.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.batch.config.BatchProperties;

/**
 * Equivalencia con el legacy: los resultados de los jobs sobre los CSV REALES deben coincidir, fila por fila,
 * con los calculados por scripts/equivalencia_legacy.py (implementación independiente en Python).
 * Se ejecuta con distintas configuraciones de paralelismo: el resultado debe ser idéntico.
 */
abstract class AbstractEquivalenceIT extends AbstractIT {

    private static final JsonNode COUNTS = readCounts();

    @Autowired
    BatchProperties props;

    private static JsonNode readCounts() {
        try (var in = AbstractIT.class.getResourceAsStream("/equivalence/expected_counts.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ dailyTransactionReportJob
    @Test
    void dailyTransactionReport() throws Exception {
        JobExecution je = run(DAILY);
        assertCompleted(je);
        JsonNode exp = COUNTS.get("movimientos");

        // conteos del step particionado (manager agrega los workers)
        StepExecution load = step(je, "loadMovementsStep");
        assertThat(load.getReadCount()).isEqualTo(exp.get("filas").asLong());
        assertThat(load.getWriteCount()).isEqualTo(exp.get("escritas").asLong());
        assertThat(load.getSkipCount()).isEqualTo(exp.get("rechazadas").asLong());

        // una ejecución por partición
        long workers = je.getStepExecutions().stream().filter(s -> s.getStepName().startsWith("loadMovementsWorkerStep:partition")).count();
        assertThat(workers).isEqualTo(Math.min(props.parallelism().gridSize(), 1000));

        // invariante: nada se pierde en silencio
        assertThat(countRejected(je, "SKIPPED")).isEqualTo(exp.get("rechazadas").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_transaction WHERE job_instance_id = ?", Long.class, instanceId(je)))
                .isEqualTo(exp.get("escritas").asLong());
        assertThat(exp.get("filas").asLong()).isEqualTo(exp.get("escritas").asLong() + exp.get("rechazadas").asLong() + exp.get("filtradas").asLong());

        // resumen por (fecha, tipo) idéntico al esperado
        List<List<String>> actual = jdbc.query("""
                SELECT summary_date, tipo, tx_count, total_amount, max_amount FROM daily_transaction_summary
                WHERE job_instance_id = ? ORDER BY summary_date, tipo
                """, (rs, i) -> List.of(rs.getDate(1).toString(), rs.getString(2), norm(rs.getLong(3)),
                norm(rs.getBigDecimal(4)), norm(rs.getBigDecimal(5))), instanceId(je));
        List<List<String>> expected = expectedCsv("expected_daily_summary.csv").stream()
                .map(r -> List.of(r.get("fecha"), r.get("tipo"), norm(r.get("cantidad")), norm(r.get("monto_total")),
                        norm(r.get("monto_maximo")))).toList();
        assertThat(actual).isNotEmpty().isEqualTo(expected);

        // anomalías por tipo
        Map<String, Long> anomalies = new TreeMap<>();
        jdbc.query("SELECT anomaly_type, count(*) FROM transaction_anomaly WHERE job_instance_id = ? GROUP BY 1",
                rs -> { anomalies.put(rs.getString(1), rs.getLong(2)); }, instanceId(je));
        Map<String, Long> expectedAnomalies = new TreeMap<>();
        exp.get("anomalias").fields().forEachRemaining(e -> expectedAnomalies.put(e.getKey(), e.getValue().asLong()));
        assertThat(anomalies).isEqualTo(expectedAnomalies);

        // CSV de reporte
        String runId = je.getJobParameters().getString("runId");
        Path report = Path.of(props.outputDir(), "daily", "daily_transaction_report_" + runId + ".csv");
        List<String> lines = Files.readAllLines(report);
        assertThat(lines.get(0)).isEqualTo("fecha,tipo,cantidad,monto_total,monto_maximo");
        assertThat(lines).hasSize(1 + expected.size());

        // step final de reporte
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_execution_report WHERE job_execution_id = ?", Long.class, je.getId()))
                .isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT skip_count FROM job_execution_report WHERE job_execution_id = ? AND step_name = 'loadMovementsStep'
                """, Long.class, je.getId())).isEqualTo(exp.get("rechazadas").asLong());
    }

    // ------------------------------------------------------------------ monthlyInterestJob
    @Test
    void monthlyInterest_mensual() throws Exception {
        verifyInterest("MENSUAL");
    }

    @Test
    void monthlyInterest_trimestral() throws Exception {
        verifyInterest("TRIMESTRAL");
    }

    private void verifyInterest(String periodo) throws Exception {
        var p = params(INTEREST, defaultInput(INTEREST)).addString("periodo", periodo, true).toJobParameters();
        JobExecution je = run(INTEREST, p);
        assertCompleted(je);
        JsonNode exp = COUNTS.get("intereses");

        StepExecution calc = step(je, "calculateInterestStep");
        assertThat(calc.getReadCount()).isEqualTo(exp.get("filas").asLong());
        assertThat(calc.getWriteCount()).isEqualTo(exp.get("escritas").asLong());
        assertThat(calc.getSkipCount()).isEqualTo(exp.get("rechazadas").asLong());
        assertThat(calc.getFilterCount()).isEqualTo(exp.get("filtradas").asLong());

        assertThat(countRejected(je, "SKIPPED")).isEqualTo(exp.get("rechazadas").asLong());
        assertThat(countRejected(je, "FILTERED")).isEqualTo(exp.get("filtradas").asLong());

        List<List<String>> actual = jdbc.query("""
                SELECT line_number, cuenta_id, nombre, tipo, saldo_anterior, tasa_anual, interes, saldo_nuevo
                FROM interest_calculation WHERE job_instance_id = ? ORDER BY line_number
                """, (rs, i) -> List.of(norm(rs.getLong(1)), norm(rs.getLong(2)), rs.getString(3), rs.getString(4),
                norm(rs.getBigDecimal(5)), norm(rs.getBigDecimal(6)), norm(rs.getBigDecimal(7)), norm(rs.getBigDecimal(8))),
                instanceId(je));
        List<List<String>> expected = expectedCsv("expected_interest_" + periodo + ".csv").stream()
                .map(r -> List.of(norm(r.get("line_number")), norm(r.get("cuenta_id")), r.get("nombre"), r.get("tipo"),
                        norm(r.get("saldo_anterior")), norm(r.get("tasa_anual")), norm(r.get("interes")),
                        norm(r.get("saldo_nuevo")))).toList();
        assertThat(actual).hasSize((int) exp.get("escritas").asLong()).isEqualTo(expected);

        // actualización de saldo (la última línea de cada cuenta prevalece)
        List<List<String>> balances = jdbc.query("SELECT cuenta_id, nombre, tipo, saldo FROM account_balance ORDER BY cuenta_id",
                (rs, i) -> List.of(norm(rs.getLong(1)), rs.getString(2), rs.getString(3), norm(rs.getBigDecimal(4))));
        List<List<String>> expectedBalances = expectedCsv("expected_balances_" + periodo + ".csv").stream()
                .map(r -> List.of(norm(r.get("cuenta_id")), r.get("nombre"), r.get("tipo"), norm(r.get("saldo")))).toList();
        assertThat(balances).isNotEmpty().isEqualTo(expectedBalances);
    }

    // ------------------------------------------------------------------ annualStatementJob
    @Test
    void annualStatement() throws Exception {
        JobExecution je = run(ANNUAL);
        assertCompleted(je);
        JsonNode exp = COUNTS.get("estados");

        StepExecution load = step(je, "loadTransactionsStep");
        assertThat(load.getReadCount()).isEqualTo(exp.get("filas").asLong());
        assertThat(load.getWriteCount()).isEqualTo(exp.get("escritas").asLong());
        assertThat(load.getSkipCount()).isEqualTo(exp.get("rechazadas").asLong());
        assertThat(countRejected(je, "SKIPPED")).isEqualTo(exp.get("rechazadas").asLong());

        List<List<String>> actual = jdbc.query("""
                SELECT cuenta_id, statement_year, total_deposits, total_withdrawals, total_purchases, final_balance, tx_count
                FROM annual_statement WHERE job_instance_id = ? ORDER BY cuenta_id, statement_year
                """, (rs, i) -> List.of(norm(rs.getLong(1)), norm(rs.getInt(2)), norm(rs.getBigDecimal(3)),
                norm(rs.getBigDecimal(4)), norm(rs.getBigDecimal(5)), norm(rs.getBigDecimal(6)), norm(rs.getLong(7))),
                instanceId(je));
        List<List<String>> expected = expectedCsv("expected_annual_statements.csv").stream()
                .map(r -> List.of(norm(r.get("cuenta_id")), norm(r.get("year")), norm(r.get("depositos")),
                        norm(r.get("retiros")), norm(r.get("compras")), norm(r.get("saldo_final")),
                        norm(r.get("movimientos")))).toList();
        assertThat(actual).hasSize(exp.get("cuentas_con_estado").asInt()).isEqualTo(expected);

        // archivos por cuenta para auditoría (JSON + CSV)
        Path dir = Path.of(props.outputDir(), "annual", je.getJobParameters().getString("runId"));
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>(files.map(f -> f.getFileName().toString()).sorted().toList());
            assertThat(names).hasSize(expected.size() * 2);
            assertThat(names).contains("cuenta_101_2024.json", "cuenta_101_2024.csv");
        }
        JsonNode json = new ObjectMapper().readTree(dir.resolve("cuenta_101_2024.json").toFile());
        assertThat(norm(json.get("finalBalance").decimalValue())).isEqualTo(expected.get(0).get(5));
        assertThat(json.get("transactions")).hasSize(Integer.parseInt(expected.get(0).get(6)));
        assertThat(Files.readAllLines(dir.resolve("cuenta_101_2024.csv"))).hasSize(1 + Integer.parseInt(expected.get(0).get(6)));
    }
}

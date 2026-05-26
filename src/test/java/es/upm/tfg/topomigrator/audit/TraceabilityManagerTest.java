package es.upm.tfg.topomigrator.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * Tests para {@link TraceabilityManager}.
 * Cubre: escritura de trazas de tabla, escritura del sumario global,
 * contenido JSON correcto, guardas ante argumentos nulos/incompletos,
 * y creación automática de directorios.
 */
public class TraceabilityManagerTest extends TestCase {

    private TraceabilityManager manager;
    private final Path tracesBaseDir = Paths.get("outputs", "traces").toAbsolutePath();
    private final Path tracesTablesDir = tracesBaseDir.resolve("tables");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        manager = new TraceabilityManager();
    }

    @Override
    protected void tearDown() throws Exception {
        // Limpiar archivos generados por los tests
        cleanTestFiles(tracesTablesDir, "test_");
        cleanTestFiles(tracesBaseDir, "test_summary");
        super.tearDown();
    }

    /** Limpia archivos que empiecen por un prefijo en un directorio. */
    private void cleanTestFiles(Path dir, String prefix) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().startsWith(prefix))
                 .forEach(f -> {
                     try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DIRECTORIO AUTO-CREADO
    // ═══════════════════════════════════════════════════════════════════

    /** El constructor crea los directorios de trazas si no existen. */
    public void testConstructorCreatesDirectories() {
        assertTrue("El directorio base de trazas debe existir", Files.exists(tracesBaseDir));
        assertTrue("El directorio de trazas de tablas debe existir", Files.exists(tracesTablesDir));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ESCRITURA DE TRAZA DE TABLA
    // ═══════════════════════════════════════════════════════════════════

    /** Escribe una traza de tabla y verifica que el archivo JSON existe. */
    public void testWriteTableTraceCreatesFile() {
        TableTrace trace = buildValidTableTrace("test_write_trace");
        manager.writeTableTrace(trace);

        Path expectedFile = tracesTablesDir.resolve("public.test_write_trace.json");
        assertTrue("El fichero de traza debería existir", Files.exists(expectedFile));
    }

    /** Verifica que el contenido JSON de la traza es correcto. */
    public void testWriteTableTraceContentIsValidJson() throws Exception {
        TableTrace trace = buildValidTableTrace("test_content_check");
        trace.executionId = "exec-12345";
        trace.status = "SUCCESS";
        trace.recordsProcessed = 42;
        trace.migrationType = "full";

        manager.writeTableTrace(trace);

        Path file = tracesTablesDir.resolve("public.test_content_check.json");
        String json = Files.readString(file);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("exec-12345", obj.get("executionId").getAsString());
        assertEquals("SUCCESS", obj.get("status").getAsString());
        assertEquals(42, obj.get("recordsProcessed").getAsLong());
        assertEquals("full", obj.get("migrationType").getAsString());
        assertFalse(obj.has("executionOrder"));
    }

    /** Verifica que el mapeo source/target se serializa correctamente. */
    public void testWriteTableTraceContainsSourceAndTarget() throws Exception {
        TableTrace trace = buildValidTableTrace("test_mapping");
        trace.table.source.schema = "public";
        trace.table.source.name = "clientes_src";
        trace.table.target.schema = "sales";
        trace.table.target.name = "test_mapping";

        manager.writeTableTrace(trace);

        Path file = tracesTablesDir.resolve("sales.test_mapping.json");
        String json = Files.readString(file);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject tableObj = obj.getAsJsonObject("table");

        assertEquals("clientes_src", tableObj.getAsJsonObject("source").get("name").getAsString());
        assertEquals("test_mapping", tableObj.getAsJsonObject("target").get("name").getAsString());
        assertEquals("sales", tableObj.getAsJsonObject("target").get("schema").getAsString());
    }

    /** Verifica que los campos obligatorios de auditoria se serializan aunque esten vacios. */
    public void testWriteTableTraceContainsRequiredAuditFields() throws Exception {
        TableTrace trace = buildValidTableTrace("test_required_fields");
        trace.errors = new ArrayList<>();
        trace.auditMetrics = new TableTrace.AuditMetrics();
        trace.auditMetrics.strategy = "SOURCE_QUERY_COUNT_WITH_TARGET_DELTA_VALIDATION";
        trace.auditMetrics.sourceSelectedRecords = 10L;
        trace.auditMetrics.targetRowsBefore = 2L;
        trace.auditMetrics.targetRowsAfter = 12L;
        trace.auditMetrics.targetNetDelta = 10L;
        trace.auditMetrics.consistencyStatus = "MATCH";
        trace.auditMetrics.warnings = new ArrayList<>();

        manager.writeTableTrace(trace);

        Path file = tracesTablesDir.resolve("public.test_required_fields.json");
        String json = Files.readString(file);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject timing = obj.getAsJsonObject("timing");
        JsonObject auditMetrics = obj.getAsJsonObject("auditMetrics");

        assertTrue(obj.has("errors"));
        assertTrue(obj.getAsJsonArray("errors").isEmpty());
        assertTrue(obj.has("auditMetrics"));
        assertEquals("2026-01-01T00:00:00", timing.get("startTime").getAsString());
        assertEquals("2026-01-01T00:01:00", timing.get("endTime").getAsString());
        assertTrue(timing.get("durationMs").getAsLong() >= 0L);
        assertEquals(10L, auditMetrics.get("sourceSelectedRecords").getAsLong());
        assertEquals(2L, auditMetrics.get("targetRowsBefore").getAsLong());
        assertEquals(12L, auditMetrics.get("targetRowsAfter").getAsLong());
        assertEquals(10L, auditMetrics.get("targetNetDelta").getAsLong());
        assertEquals("MATCH", auditMetrics.get("consistencyStatus").getAsString());
        assertTrue(auditMetrics.has("warnings"));
        assertTrue(auditMetrics.getAsJsonArray("warnings").isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GUARDAS — TRAZA NULA / INCOMPLETA
    // ═══════════════════════════════════════════════════════════════════

    /** Un trace nulo no produce crash (solo log de error). */
    public void testWriteNullTableTraceDoesNotCrash() {
        manager.writeTableTrace(null);
        // Debe sobrevivir sin excepción
    }

    /** Un trace con table nulo no produce crash. */
    public void testWriteTableTraceWithNullTableDoesNotCrash() {
        TableTrace trace = new TableTrace();
        trace.table = null;
        manager.writeTableTrace(trace);
    }

    /** Un trace con table.target nulo no produce crash. */
    public void testWriteTableTraceWithNullTargetDoesNotCrash() {
        TableTrace trace = new TableTrace();
        trace.table = new TableTrace.TableMapping();
        trace.table.target = null;
        manager.writeTableTrace(trace);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ESCRITURA DEL SUMARIO GLOBAL
    // ═══════════════════════════════════════════════════════════════════

    /** Escribe un sumario global y verifica que el archivo existe. */
    public void testWriteSummaryCreatesFile() {
        SummaryTrace summary = buildValidSummary();
        manager.writeSummary(summary);

        Path expectedFile = tracesBaseDir.resolve("summary.json");
        assertTrue("El fichero summary.json debería existir", Files.exists(expectedFile));
    }

    /** Verifica contenido del sumario JSON. */
    public void testWriteSummaryContentIsValid() throws Exception {
        SummaryTrace summary = buildValidSummary();
        summary.executionId = "exec-test-999";
        summary.tables = new SummaryTrace.TablesSummary();
        summary.tables.total = 5;
        summary.tables.successful = 4;
        summary.tables.failed = 1;

        manager.writeSummary(summary);

        Path file = tracesBaseDir.resolve("summary.json");
        String json = Files.readString(file);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("exec-test-999", obj.get("executionId").getAsString());
        assertEquals(5, obj.getAsJsonObject("tables").get("total").getAsInt());
        assertEquals(4, obj.getAsJsonObject("tables").get("successful").getAsInt());
        assertEquals(1, obj.getAsJsonObject("tables").get("failed").getAsInt());
    }

    /** Un sumario nulo no produce crash. */
    public void testWriteNullSummaryDoesNotCrash() {
        manager.writeSummary(null);
    }

    /** Un sumario con información de migración se serializa correctamente. */
    public void testWriteSummaryWithMigrationInfo() throws Exception {
        SummaryTrace summary = buildValidSummary();
        summary.migration = new SummaryTrace.MigrationInfo();
        summary.migration.name = "migracion_test";
        summary.migration.version = "2.0";
        summary.migration.executedBy = "tester";

        manager.writeSummary(summary);

        Path file = tracesBaseDir.resolve("summary.json");
        String json = Files.readString(file);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject migObj = obj.getAsJsonObject("migration");

        assertEquals("migracion_test", migObj.get("name").getAsString());
        assertEquals("2.0", migObj.get("version").getAsString());
        assertEquals("tester", migObj.get("executedBy").getAsString());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private TableTrace buildValidTableTrace(String targetName) {
        TableTrace trace = new TableTrace();
        trace.executionId = "exec-test";
        trace.tableExecutionId = "exec-test-t1";
        trace.migrationType = "full";
        trace.status = "SUCCESS";
        trace.recordsProcessed = 100;
        trace.timing = new Timing();
        trace.timing.startTime = "2026-01-01T00:00:00";
        trace.timing.endTime = "2026-01-01T00:01:00";
        trace.timing.durationMs = 60000;

        trace.table = new TableTrace.TableMapping();
        trace.table.source = new TableTrace.SchemaTable();
        trace.table.source.schema = "public";
        trace.table.source.name = "source_table";
        trace.table.target = new TableTrace.SchemaTable();
        trace.table.target.schema = "public";
        trace.table.target.name = targetName;

        return trace;
    }

    private SummaryTrace buildValidSummary() {
        SummaryTrace summary = new SummaryTrace();
        summary.executionId = "exec-summary-test";
        summary.timing = new Timing();
        summary.timing.startTime = "2026-01-01T00:00:00";
        summary.timing.endTime = "2026-01-01T01:00:00";
        summary.timing.durationMs = 3600000;
        summary.tables = new SummaryTrace.TablesSummary();
        summary.tables.total = 0;
        summary.executionOrder = new ArrayList<>();
        summary.tableExecutionDetails = new ArrayList<>();
        summary.failedTables = new ArrayList<>();
        summary.blockedTables = new ArrayList<>();
        return summary;
    }
}

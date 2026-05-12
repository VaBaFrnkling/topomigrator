package es.upm.tfg.topomigrator.execution;

import com.google.gson.JsonObject;
import es.upm.tfg.topomigrator.audit.SummaryTrace;
import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.audit.TraceabilityManager;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.MigrationInfo;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionEngineFailureClassificationTest extends TestCase {

    public void testJavaExceptionDuringTableExecutionEndsAsFailedWithTraceError() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.uploadException = new IllegalStateException("Error JDBC simulado con password=secret");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), 0L, 0L, 3);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(), List.of());
            fail("La migracion debe propagarse como no exitosa cuando una tabla falla.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("FAILED", trace.status);
        assertEquals(1, traceabilityManager.summary.tables.failed);
        assertEquals("clientes", traceabilityManager.summary.failedTables.get(0).table);
        assertNotNull("Los errores de tabla fallida deben quedar en la traza.", trace.errors);
        assertEquals(1, trace.errors.size());
        assertTrue(trace.errors.get(0).contains("Error JDBC simulado"));
        assertFalse(trace.errors.get(0).contains("secret"));
    }

    public void testIncrementalJavaExceptionBeforeNiFiDoesNotCreateSuccessfulState() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.uploadException = new IllegalStateException("Error JDBC simulado con password=secret");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), stateService, 0L, 0L, 3);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(incrementalTable("public", "clientes")), List.of());
            fail("La migracion incremental debe propagarse como no exitosa cuando una tabla falla.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        assertEquals("FAILED", traceabilityManager.tableTraces.get(0).status);
        assertNull(stateService.getState("public.clientes"));
    }

    public void testFailureDiagnosticsRedactCommonSecretFormats() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.uploadException = new IllegalStateException(
                "password: secret; {\"token\":\"abc.def\"}; Authorization: Bearer jwt-value; https://user:pass@example.test/db");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), 0L, 0L, 3);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(), List.of());
            fail("La migracion debe propagarse como no exitosa cuando una tabla falla.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        String diagnostic = traceabilityManager.tableTraces.get(0).errors.get(0);
        assertFalse(diagnostic.contains("secret"));
        assertFalse(diagnostic.contains("abc.def"));
        assertFalse(diagnostic.contains("jwt-value"));
        assertFalse(diagnostic.contains("user:pass"));
        assertTrue(diagnostic.contains("[REDACTED]"));
    }

    public void testNiFiFailureDiagnosticsEndAsFailedWithTraceError() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failureDiagnostics = List.of("NiFiFailure_Insert recibio 1 FlowFile por una relacion failure");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), 0L, 0L, 4);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(), List.of());
            fail("La migracion debe propagarse como no exitosa cuando NiFi detecta fallo.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("FAILED", trace.status);
        assertNotNull(trace.errors);
        assertEquals(1, trace.errors.size());
        assertTrue(trace.errors.get(0).contains("NiFi detecto fallos internos")
                || trace.errors.get(0).contains("NiFi detect"));
        assertFalse(trace.errors.get(0).contains("Bearer"));
    }

    public void testIncrementalNiFiFailureDiagnosticsDoNotAdvanceState() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failureDiagnostics = List.of("NiFiFailure_Insert recibio 1 FlowFile por una relacion failure");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), stateService, 0L, 0L, 4);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(incrementalTable("public", "clientes")), List.of());
            fail("La migracion incremental debe propagarse como no exitosa cuando NiFi detecta fallo.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        assertEquals("FAILED", traceabilityManager.tableTraces.get(0).status);
        assertNull(stateService.getState("public.clientes"));
    }

    public void testIncrementalFailurePreservesExistingState() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failureDiagnostics = List.of("NiFiFailure_Insert recibio 1 FlowFile por una relacion failure");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();
        MigrationContract contract = contract(incrementalTable("public", "clientes"));
        TableMigration table = contract.getTables().get("clientes");
        stateService.saveSuccessfulBatch(
                stateService.buildStateKey(table),
                table,
                "2024-01-01",
                "2024-02-01",
                "previous-exec",
                "previous-table-exec",
                3L
        );
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), stateService, 0L, 0L, 4);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract, List.of());
            fail("La migracion incremental debe fallar sin avanzar el estado existente.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        IncrementalStateService.IncrementalState state = stateService.getState("public.clientes");
        assertNotNull(state);
        assertEquals("2024-01-01", state.previousProcessedValue);
        assertEquals("2024-02-01", state.lastProcessedValue);
        assertEquals("SUCCESS", state.executionStatus);
        assertEquals("previous-exec", state.executionId);
        assertEquals("previous-table-exec", state.tableExecutionId);
        assertEquals(Long.valueOf(3L), state.recordsProcessed);
    }

    public void testUnsafeIncrementalUpsertFailsBeforeNiFiAndPreservesState() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();
        MigrationContract contract = contract(incrementalUpsertTable("public", "clientes"));
        TableMigration table = contract.getTables().get("clientes");
        stateService.saveSuccessfulBatch(
                stateService.buildStateKey(table),
                table,
                "2024-01-01",
                "2024-02-01",
                "previous-exec",
                "previous-table-exec",
                3L
        );
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new UnsafeUpsertMetricsService(), stateService, 0L, 0L, 4);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract, List.of());
            fail("UPSERT incremental sin claves de idempotencia ni PK debe fallar antes de NiFi.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        assertTrue("El flujo inseguro no debe subirse a NiFi.", nifiClient.uploadedGroups.isEmpty());
        assertTrue("El flujo inseguro no debe arrancar ningun Process Group.", nifiClient.states.isEmpty());
        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("FAILED", trace.status);
        assertTrue(trace.errors.get(0).contains("idempotencyKeyColumns"));
        assertTrue(trace.errors.get(0).contains("clave primaria"));
        assertTrue(trace.errors.get(0).contains("idempotencia"));

        IncrementalStateService.IncrementalState state = stateService.getState("public.clientes");
        assertNotNull(state);
        assertEquals("2024-02-01", state.lastProcessedValue);
        assertEquals("previous-exec", state.executionId);
        assertEquals(Long.valueOf(3L), state.recordsProcessed);
    }

    public void testNiFiTimeoutEndsAsFailedWithTraceError() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.activeThreads = 1;
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), 0L, 0L, 2);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(), List.of());
            fail("La migracion debe propagarse como no exitosa cuando NiFi no confirma fin.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("FAILED", trace.status);
        assertNotNull(trace.errors);
        assertEquals(1, trace.errors.size());
        assertTrue(trace.errors.get(0).contains("Timeout de NiFi"));
    }

    public void testIncrementalNiFiTimeoutDoesNotAdvanceState() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.activeThreads = 1;
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), stateService, 0L, 0L, 2);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(incrementalTable("public", "clientes")), List.of());
            fail("La migracion incremental debe propagarse como no exitosa cuando NiFi no confirma fin.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        assertEquals("FAILED", traceabilityManager.tableTraces.get(0).status);
        assertNull(stateService.getState("public.clientes"));
    }

    public void testAmbiguousNiFiInspectionEndsAsFailedWithTraceError() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failureDiagnosticsException = new IllegalStateException(
                "No se pudo inspeccionar el estado interno de fallo del Process Group pg-clientes");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), 0L, 0L, 4);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(), List.of());
            fail("La migracion debe propagarse como no exitosa ante resultado ambiguo.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("FAILED", trace.status);
        assertNotNull(trace.errors);
        assertEquals(1, trace.errors.size());
        assertTrue(trace.errors.get(0).contains("No se pudo inspeccionar"));
    }

    public void testIncrementalAmbiguousNiFiInspectionDoesNotAdvanceState() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failureDiagnosticsException = new IllegalStateException(
                "No se pudo inspeccionar el estado interno de fallo del Process Group pg-clientes");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new SuccessfulMetricsService(), stateService, 0L, 0L, 4);

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract(incrementalTable("public", "clientes")), List.of());
            fail("La migracion incremental debe propagarse como no exitosa ante resultado ambiguo.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        assertEquals("FAILED", traceabilityManager.tableTraces.get(0).status);
        assertNull(stateService.getState("public.clientes"));
    }

    private ExecutionEngine engine(NiFiClient nifiClient,
                                   TraceabilityManager traceabilityManager,
                                   TableMetricsService metricsService,
                                   long initialWaitMs,
                                   long pollWaitMs,
                                   int maxMonitorChecks) {
        return new ExecutionEngine(
                nifiClient,
                traceabilityManager,
                metricsService,
                stateService(),
                initialWaitMs,
                pollWaitMs,
                maxMonitorChecks
        );
    }

    private ExecutionEngine engine(NiFiClient nifiClient,
                                   TraceabilityManager traceabilityManager,
                                   TableMetricsService metricsService,
                                   IncrementalStateService stateService,
                                   long initialWaitMs,
                                   long pollWaitMs,
                                   int maxMonitorChecks) {
        return new ExecutionEngine(
                nifiClient,
                traceabilityManager,
                metricsService,
                stateService,
                initialWaitMs,
                pollWaitMs,
                maxMonitorChecks
        );
    }

    private IncrementalStateService stateService() {
        try {
            return new IncrementalStateService(Files.createTempDirectory("topomigrator-state").resolve("incremental-state.json"));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear estado incremental temporal para test.", e);
        }
    }

    private MigrationContract contract() {
        MigrationContract contract = new MigrationContract();
        MigrationInfo migration = new MigrationInfo();
        migration.setName("failure-classification-test");
        migration.setDescription("test");
        migration.setVersion("1");
        migration.setAuthor("test");
        contract.setMigration(migration);

        DatabaseConfig database = new DatabaseConfig();
        database.setSourceConnection(connection("jdbc:postgresql://source/db"));
        database.setTargetConnection(connection("jdbc:postgresql://target/db"));
        contract.setDatabase(database);

        Map<String, TableMigration> tables = new LinkedHashMap<>();
        tables.put("clientes", table("public", "clientes"));
        contract.setTables(tables);
        return contract;
    }

    private MigrationContract contract(TableMigration table) {
        MigrationContract contract = contract();
        Map<String, TableMigration> tables = new LinkedHashMap<>();
        tables.put("clientes", table);
        contract.setTables(tables);
        return contract;
    }

    private ConnectionConfig connection(String jdbcUrl) {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("user");
        config.setPassword("secret");
        config.setDriver("org.postgresql.Driver");
        config.setDriverLocation("/opt/nifi/drivers/postgresql-42.7.10.jar");
        config.setDatabaseType("PostgreSQL");
        return config;
    }

    private TableMigration table(String schema, String tableName) {
        TableMigration table = new TableMigration();
        table.setEnabled(true);
        table.setMigrationType("full");
        table.setSource(tableRef(schema, tableName));
        table.setTarget(tableRef(schema, tableName));
        return table;
    }

    private TableMigration incrementalTable(String schema, String tableName) {
        TableMigration table = table(schema, tableName);
        table.setMigrationType("incremental");

        IncrementalConfig incrementalConfig = new IncrementalConfig();
        incrementalConfig.setColumn("updated_at");
        incrementalConfig.setStartValue("2024-01-01");
        incrementalConfig.setBatchSize(50);
        incrementalConfig.setLoadStrategy("append");
        table.setIncrementalConfig(incrementalConfig);
        return table;
    }

    private TableMigration incrementalUpsertTable(String schema, String tableName) {
        TableMigration table = incrementalTable(schema, tableName);
        table.getIncrementalConfig().setLoadStrategy("upsert");
        table.getIncrementalConfig().setIdempotencyKeyColumns(null);
        return table;
    }

    private TableRef tableRef(String schema, String tableName) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(tableName);
        return ref;
    }

    private static final class RecordingNiFiClient extends NiFiClient {
        private final List<String> uploadedGroups = new ArrayList<>();
        private final List<String> states = new ArrayList<>();
        private int activeThreads;
        private List<String> failureDiagnostics = List.of();
        private RuntimeException failureDiagnosticsException;
        private RuntimeException uploadException;

        private RecordingNiFiClient() {
            super("https://test.invalid/nifi-api", "user", "password", false);
        }

        @Override
        public void authenticate() {
        }

        @Override
        public String getRootProcessGroupId() {
            return "root";
        }

        @Override
        public String uploadFlowDefinition(String parentId, String groupName, int positionY, Path flowJsonPath, Map<String, String> dynamicVariables) {
            uploadedGroups.add(groupName);
            if (uploadException != null) {
                throw uploadException;
            }
            return "pg-clientes";
        }

        @Override
        public void changeProcessGroupState(String processGroupId, String state) {
            states.add(state);
        }

        @Override
        public JsonObject getProcessGroupStatus(String processGroupId) {
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("activeThreadCount", activeThreads);
            snapshot.addProperty("queuedCount", "0");
            snapshot.addProperty("bytesRead", "1 KB");

            JsonObject status = new JsonObject();
            status.add("aggregateSnapshot", snapshot);

            JsonObject root = new JsonObject();
            root.add("processGroupStatus", status);
            return root;
        }

        @Override
        public List<String> collectFailureDiagnostics(String processGroupId) {
            if (failureDiagnosticsException != null) {
                throw failureDiagnosticsException;
            }
            return failureDiagnostics;
        }

        @Override
        public void cleanupProcessGroup(String processGroupId) {
        }
    }

    private static final class RecordingTraceabilityManager extends TraceabilityManager {
        private final List<TableTrace> tableTraces = new ArrayList<>();
        private SummaryTrace summary;

        @Override
        public void writeTableTrace(TableTrace trace) {
            tableTraces.add(trace);
        }

        @Override
        public void writeSummary(SummaryTrace summary) {
            this.summary = summary;
        }
    }

    private static class SuccessfulMetricsService extends TableMetricsService {
        private int targetCountCalls;

        @Override
        public long countSourceSelectedRows(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
            return 1L;
        }

        @Override
        public long countTargetRows(MigrationContract contract, TableMigration tableConfig) {
            targetCountCalls++;
            return targetCountCalls % 2 == 1 ? 0L : 1L;
        }
    }

    private static final class UnsafeUpsertMetricsService extends SuccessfulMetricsService {
        @Override
        public List<String> getTargetPrimaryKeyColumns(MigrationContract contract, TableMigration tableConfig) {
            return List.of();
        }
    }

}

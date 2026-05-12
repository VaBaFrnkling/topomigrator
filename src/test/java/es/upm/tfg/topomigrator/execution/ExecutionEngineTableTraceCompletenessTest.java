package es.upm.tfg.topomigrator.execution;

import com.google.gson.JsonObject;
import es.upm.tfg.topomigrator.audit.SummaryTrace;
import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.audit.TraceabilityManager;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.MigrationInfo;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionEngineTableTraceCompletenessTest extends TestCase {

    public void testSuccessTraceContainsCompleteAuditContract() {
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(new RecordingNiFiClient(), traceabilityManager, new MatchingMetricsService());

        engine.executeMigration(List.of(new TableNode("clientes")), contract("clientes"), List.of());

        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertTraceBasics(trace, "SUCCESS");
        assertNotNull(trace.errors);
        assertTrue(trace.errors.isEmpty());
        assertNotNull(trace.auditMetrics);
        assertEquals(5L, trace.auditMetrics.sourceSelectedRecords.longValue());
        assertEquals(10L, trace.auditMetrics.targetRowsBefore.longValue());
        assertEquals(15L, trace.auditMetrics.targetRowsAfter.longValue());
        assertEquals(5L, trace.auditMetrics.targetNetDelta.longValue());
        assertEquals("MATCH", trace.auditMetrics.consistencyStatus);
        assertNotNull(trace.auditMetrics.warnings);
        assertTrue(trace.auditMetrics.warnings.isEmpty());
    }

    public void testFailedTraceContainsSanitizedDiagnosticsAndAuditContract() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.uploadException = new IllegalStateException("Fallo con password=secret y token=abc.def");
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new MatchingMetricsService());

        try {
            engine.executeMigration(List.of(new TableNode("clientes")), contract("clientes"), List.of());
            fail("La migracion debe propagarse como no exitosa.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
        }

        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertTraceBasics(trace, "FAILED");
        assertEquals(0L, trace.recordsProcessed);
        assertNotNull(trace.errors);
        assertEquals(1, trace.errors.size());
        assertFalse(trace.errors.get(0).contains("secret"));
        assertFalse(trace.errors.get(0).contains("abc.def"));
        assertTrue(trace.errors.get(0).contains("[REDACTED]"));
        assertEquals("FAILED", trace.auditMetrics.consistencyStatus);
        assertNotNull(trace.auditMetrics.warnings);
        assertEquals(1, trace.auditMetrics.warnings.size());
        assertFalse(trace.auditMetrics.warnings.get(0).contains("secret"));
    }

    public void testBlockedTraceContainsDependencyDiagnosticAndAuditContract() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failUploadsForGroup = "Migracion_clientes";
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, new MatchingMetricsService());

        try {
            engine.executeMigration(
                    List.of(new TableNode("clientes"), new TableNode("pedidos")),
                    contract("clientes", "pedidos"),
                    List.of(new ForeignKeyDependency("clientes", "pedidos"))
            );
            fail("La migracion debe propagarse como no exitosa por bloqueo.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("bloqueada"));
        }

        TableTrace trace = traceabilityManager.tableTraces.get(1);
        assertTraceBasics(trace, "BLOCKED");
        assertEquals(0L, trace.recordsProcessed);
        assertNotNull(trace.errors);
        assertTrue(trace.errors.isEmpty());
        assertEquals("BLOCKED", trace.auditMetrics.consistencyStatus);
        assertNotNull(trace.auditMetrics.warnings);
        assertEquals(1, trace.auditMetrics.warnings.size());
        assertTrue(trace.auditMetrics.warnings.get(0).contains("clientes"));
    }

    private void assertTraceBasics(TableTrace trace, String status) {
        assertNotNull(trace.executionId);
        assertNotNull(trace.tableExecutionId);
        assertTrue(trace.executionOrder > 0);
        assertNotNull(trace.table);
        assertNotNull(trace.table.source);
        assertNotNull(trace.table.target);
        assertEquals("full", trace.migrationType);
        assertEquals(status, trace.status);
        assertNotNull(trace.timing);
        assertNotNull(trace.timing.startTime);
        assertNotNull(trace.timing.endTime);
        assertTrue(trace.timing.durationMs >= 0L);
        assertNotNull(trace.auditMetrics);
        assertNotNull(trace.auditMetrics.strategy);
        assertNotNull(trace.auditMetrics.warnings);
    }

    private ExecutionEngine engine(NiFiClient nifiClient,
                                   TraceabilityManager traceabilityManager,
                                   TableMetricsService metricsService) {
        return new ExecutionEngine(
                nifiClient,
                traceabilityManager,
                metricsService,
                stateService(),
                0L,
                0L,
                4
        );
    }

    private IncrementalStateService stateService() {
        try {
            return new IncrementalStateService(Files.createTempDirectory("topomigrator-state").resolve("incremental-state.json"));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear estado incremental temporal para test.", e);
        }
    }

    private MigrationContract contract(String... tableNames) {
        MigrationContract contract = new MigrationContract();
        MigrationInfo migration = new MigrationInfo();
        migration.setName("trace-completeness-test");
        migration.setDescription("test");
        migration.setVersion("1");
        migration.setAuthor("test");
        contract.setMigration(migration);

        DatabaseConfig database = new DatabaseConfig();
        database.setSourceConnection(connection("jdbc:postgresql://source/db"));
        database.setTargetConnection(connection("jdbc:postgresql://target/db"));
        contract.setDatabase(database);

        Map<String, TableMigration> tables = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            tables.put(tableName, table("public", tableName));
        }
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

    private TableRef tableRef(String schema, String tableName) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(tableName);
        return ref;
    }

    private static final class RecordingNiFiClient extends NiFiClient {
        private RuntimeException uploadException;
        private String failUploadsForGroup;

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
            if (uploadException != null) {
                throw uploadException;
            }
            if (groupName.equals(failUploadsForGroup)) {
                throw new IllegalStateException("Fallo simulado en tabla padre");
            }
            return "pg-" + groupName;
        }

        @Override
        public void changeProcessGroupState(String processGroupId, String state) {
        }

        @Override
        public JsonObject getProcessGroupStatus(String processGroupId) {
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("activeThreadCount", 0);
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
            return List.of();
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

    private static final class MatchingMetricsService extends TableMetricsService {
        @Override
        public long countSourceSelectedRows(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
            return 5L;
        }

        @Override
        public long countTargetRows(MigrationContract contract, TableMigration tableConfig) {
            String tableName = tableConfig.getTarget().getTable();
            if ("pedidos".equals(tableName)) {
                return 0L;
            }
            return targetRowsBeforeSeen ? 15L : markTargetRowsBeforeSeen();
        }

        private boolean targetRowsBeforeSeen;

        private long markTargetRowsBeforeSeen() {
            targetRowsBeforeSeen = true;
            return 10L;
        }
    }
}

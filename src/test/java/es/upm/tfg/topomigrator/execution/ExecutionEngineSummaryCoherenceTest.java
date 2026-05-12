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
import java.util.stream.Collectors;

public class ExecutionEngineSummaryCoherenceTest extends TestCase {

    public void testSummaryCountsAndListsMatchTableTracesForMixedExecution() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failUploadsForGroup = "Migracion_pedidos";
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager);

        try {
            engine.executeMigration(
                    List.of(new TableNode("clientes"), new TableNode("pedidos"), new TableNode("lineas")),
                    contract("clientes", "pedidos", "lineas"),
                    List.of(new ForeignKeyDependency("pedidos", "lineas"))
            );
            fail("La migracion debe terminar como no exitosa por fallo y bloqueo.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
            assertTrue(expected.getMessage().contains("bloqueada"));
        }

        SummaryTrace summary = traceabilityManager.summary;
        assertNotNull(summary);
        assertEquals(3, traceabilityManager.tableTraces.size());
        assertEquals(3, summary.tableExecutionDetails.size());

        assertEquals(3, summary.tables.total);
        assertEquals(countTracesByStatus(traceabilityManager.tableTraces, "SUCCESS"), summary.tables.successful);
        assertEquals(countTracesByStatus(traceabilityManager.tableTraces, "FAILED"), summary.tables.failed);
        assertEquals(countTracesByStatus(traceabilityManager.tableTraces, "BLOCKED"), summary.tables.blocked);

        assertEquals(List.of("SUCCESS", "FAILED", "BLOCKED"),
                summary.tableExecutionDetails.stream().map(d -> d.status).collect(Collectors.toList()));
        assertEquals(List.of("pedidos"), summary.failedTables.stream().map(f -> f.table).collect(Collectors.toList()));
        assertEquals(List.of("lineas"), summary.blockedTables);

        TableTrace failedTrace = traceabilityManager.tableTraces.get(1);
        SummaryTrace.FailedTable failedSummary = summary.failedTables.get(0);
        assertEquals("FAILED", failedTrace.status);
        assertEquals(failedTrace.errors.get(0), failedSummary.reason);
        assertFalse(failedSummary.reason.contains("secret"));
        assertFalse(failedSummary.reason.contains("abc.def"));
        assertFalse(failedSummary.reason.contains("jwt-token"));
        assertTrue(failedSummary.reason.contains("[REDACTED]"));

        SummaryTrace.TableExecutionSummary successDetail = summary.tableExecutionDetails.get(0);
        TableTrace successTrace = traceabilityManager.tableTraces.get(0);
        assertEquals(successTrace.tableExecutionId, successDetail.executionId);
        assertEquals(successTrace.executionOrder, successDetail.order);
        assertEquals(successTrace.recordsProcessed, successDetail.records);
        assertEquals(successTrace.timing.durationMs, successDetail.durationMs);
        assertEquals(successTrace.auditMetrics.sourceSelectedRecords, successDetail.sourceSelectedRecords);
        assertEquals(successTrace.auditMetrics.targetNetDelta, successDetail.targetNetDeltaRecords);
        assertEquals(successTrace.auditMetrics.consistencyStatus, successDetail.auditConsistencyStatus);
    }

    private int countTracesByStatus(List<TableTrace> traces, String status) {
        return (int) traces.stream().filter(trace -> status.equals(trace.status)).count();
    }

    private ExecutionEngine engine(NiFiClient nifiClient, TraceabilityManager traceabilityManager) {
        return new ExecutionEngine(
                nifiClient,
                traceabilityManager,
                new SuccessfulMetricsService(),
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
        migration.setName("summary-coherence-test");
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
            if (groupName.equals(failUploadsForGroup)) {
                throw new IllegalStateException("Fallo con password=secret token=abc.def Authorization: Bearer jwt-token");
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

    private static final class SuccessfulMetricsService extends TableMetricsService {
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
}

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

public class ExecutionEngineIncrementalStateServiceTest extends TestCase {

    public void testIncrementalExecutionUsesPersistedStateBeforeConfiguredStartValue() throws Exception {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        StubTableMetricsService metricsService = new StubTableMetricsService("2024-03-01");
        IncrementalStateService stateService = stateService();
        MigrationContract contract = contract();
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

        ExecutionEngine engine = engine(nifiClient, traceabilityManager, metricsService, stateService);

        engine.executeMigration(List.of(new TableNode("clientes")), contract, List.of());

        assertEquals("SELECT * FROM public.clientes WHERE updated_at > '2024-02-01' ORDER BY updated_at ASC LIMIT 50",
                nifiClient.uploadedVariables.get(0).get("##QUERY_SQL##"));
        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("updated_at", trace.incrementalInfo.column);
        assertEquals("2024-02-01", trace.incrementalInfo.previousValue);
        assertEquals("2024-03-01", trace.incrementalInfo.lastProcessedValue);
        assertTrue(trace.incrementalInfo.stateUpdated);

        IncrementalStateService.IncrementalState state = stateService.getState("public.clientes");
        assertEquals("2024-02-01", state.previousProcessedValue);
        assertEquals("2024-03-01", state.lastProcessedValue);
        assertEquals("SUCCESS", state.executionStatus);
        assertEquals(trace.executionId, state.executionId);
        assertEquals(trace.tableExecutionId, state.tableExecutionId);
        assertEquals(Long.valueOf(trace.recordsProcessed), state.recordsProcessed);
    }

    public void testIncrementalExecutionFallsBackToConfiguredStartValueWithoutState() throws Exception {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();

        engine(nifiClient, traceabilityManager, new StubTableMetricsService("2024-01-15"), stateService)
                .executeMigration(List.of(new TableNode("clientes")), contract(), List.of());

        assertEquals("SELECT * FROM public.clientes WHERE updated_at > '2024-01-01' ORDER BY updated_at ASC LIMIT 50",
                nifiClient.uploadedVariables.get(0).get("##QUERY_SQL##"));
        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("updated_at", trace.incrementalInfo.column);
        assertEquals("2024-01-01", trace.incrementalInfo.previousValue);
        assertEquals("2024-01-15", trace.incrementalInfo.lastProcessedValue);
        assertTrue(trace.incrementalInfo.stateUpdated);
    }

    public void testIncrementalTraceKeepsInitialValuesWhenNoNewCursorIsAvailable() throws Exception {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();

        engine(nifiClient, traceabilityManager, new StubTableMetricsService(" "), stateService)
                .executeMigration(List.of(new TableNode("clientes")), contract(), List.of());

        TableTrace trace = traceabilityManager.tableTraces.get(0);
        assertEquals("updated_at", trace.incrementalInfo.column);
        assertEquals("2024-01-01", trace.incrementalInfo.previousValue);
        assertEquals("2024-01-01", trace.incrementalInfo.lastProcessedValue);
        assertFalse(trace.incrementalInfo.stateUpdated);
        assertNull(stateService.getState("public.clientes"));
    }

    public void testExecutionEngineDoesNotReferenceLegacyIncrementalStateStore() throws Exception {
        String source = Files.readString(Path.of("src/main/java/es/upm/tfg/topomigrator/execution/ExecutionEngine.java"));
        assertFalse(source.contains("IncrementalStateStore"));
    }

    private ExecutionEngine engine(NiFiClient nifiClient,
                                   TraceabilityManager traceabilityManager,
                                   TableMetricsService metricsService,
                                   IncrementalStateService stateService) {
        return new ExecutionEngine(nifiClient, traceabilityManager, metricsService, stateService, 0L, 0L, 4);
    }

    private IncrementalStateService stateService() throws Exception {
        return new IncrementalStateService(Files.createTempDirectory("topomigrator-state").resolve("incremental-state.json"));
    }

    private MigrationContract contract() {
        MigrationContract contract = new MigrationContract();
        MigrationInfo migration = new MigrationInfo();
        migration.setName("incremental-state-service-test");
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
        table.setMigrationType("incremental");
        table.setSource(tableRef(schema, tableName));
        table.setTarget(tableRef(schema, tableName));

        IncrementalConfig incrementalConfig = new IncrementalConfig();
        incrementalConfig.setColumn("updated_at");
        incrementalConfig.setStartValue("2024-01-01");
        incrementalConfig.setBatchSize(50);
        incrementalConfig.setLoadStrategy("append");
        table.setIncrementalConfig(incrementalConfig);
        return table;
    }

    private TableRef tableRef(String schema, String tableName) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(tableName);
        return ref;
    }

    private static final class RecordingNiFiClient extends NiFiClient {
        private final List<Map<String, String>> uploadedVariables = new ArrayList<>();

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
            uploadedVariables.add(new LinkedHashMap<>(dynamicVariables));
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

    private static final class StubTableMetricsService extends TableMetricsService {
        private final String lastIncrementalValue;
        private int targetCountCalls;

        private StubTableMetricsService(String lastIncrementalValue) {
            this.lastIncrementalValue = lastIncrementalValue;
        }

        @Override
        public long countSourceSelectedRows(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
            return 5L;
        }

        @Override
        public long countTargetRows(MigrationContract contract, TableMigration tableConfig) {
            targetCountCalls++;
            return targetCountCalls % 2 == 1 ? 10L : 15L;
        }

        @Override
        public String findLastSelectedIncrementalValue(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
            return lastIncrementalValue;
        }
    }
}

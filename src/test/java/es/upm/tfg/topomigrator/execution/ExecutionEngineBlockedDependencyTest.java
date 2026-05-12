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
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionEngineBlockedDependencyTest extends TestCase {

    public void testDependentTableIsBlockedAfterParentFailureAndNotSentToNiFi() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failUploadsForGroup = "Migracion_clientes";
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager);

        try {
            engine.executeMigration(
                    List.of(new TableNode("clientes"), new TableNode("pedidos")),
                    contractWithLogicalKeys(),
                    List.of(new ForeignKeyDependency("clientes", "pedidos"))
            );
            fail("La migracion debe terminar como no exitosa por tabla fallida y dependiente bloqueada.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fallida"));
            assertTrue(expected.getMessage().contains("bloqueada"));
        }

        assertEquals(List.of("Migracion_clientes"), nifiClient.uploadedGroups);
        assertTrue(nifiClient.states.isEmpty());
        assertTrue(nifiClient.cleanedGroups.isEmpty());

        assertEquals("FAILED", traceabilityManager.tableTraces.get(0).status);
        assertEquals("BLOCKED", traceabilityManager.tableTraces.get(1).status);
        assertEquals(1, traceabilityManager.summary.tables.failed);
        assertEquals(1, traceabilityManager.summary.tables.blocked);
        assertEquals(List.of("pedidos"), traceabilityManager.summary.blockedTables);
        assertTrue(traceabilityManager.tableTraces.get(1).auditMetrics.warnings.get(0).contains("clientes"));
    }

    public void testSchemaQualifiedDependenciesBlockLogicalExecutionKeys() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failUploadsForGroup = "Migracion_clientes";
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager);

        try {
            engine.executeMigration(
                    List.of(new TableNode("clientes"), new TableNode("pedidos")),
                    contractWithLogicalKeys(),
                    List.of(new ForeignKeyDependency("public.clientes", "public.pedidos"))
            );
            fail("La dependencia fisica schema.table debe bloquear la clave logica dependiente.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("bloqueada"));
        }

        assertEquals(List.of("Migracion_clientes"), nifiClient.uploadedGroups);
        assertEquals("FAILED", traceabilityManager.tableTraces.get(0).status);
        assertEquals("BLOCKED", traceabilityManager.tableTraces.get(1).status);
        assertEquals(1, traceabilityManager.summary.tables.blocked);
        assertEquals(List.of("pedidos"), traceabilityManager.summary.blockedTables);
    }

    public void testBlockedIncrementalDependentDoesNotPersistState() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        nifiClient.failUploadsForGroup = "Migracion_clientes";
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        IncrementalStateService stateService = stateService();
        ExecutionEngine engine = engine(nifiClient, traceabilityManager, stateService);

        try {
            engine.executeMigration(
                    List.of(new TableNode("clientes"), new TableNode("pedidos")),
                    contractWithIncrementalDependent(),
                    List.of(new ForeignKeyDependency("clientes", "pedidos"))
            );
            fail("La migracion debe terminar como no exitosa por tabla fallida y dependiente bloqueada.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("bloqueada"));
        }

        assertEquals(List.of("Migracion_clientes"), nifiClient.uploadedGroups);
        assertEquals("BLOCKED", traceabilityManager.tableTraces.get(1).status);
        assertNull(stateService.getState("public.pedidos"));
    }

    private ExecutionEngine engine(NiFiClient nifiClient, TraceabilityManager traceabilityManager) {
        return engine(nifiClient, traceabilityManager, stateService());
    }

    private ExecutionEngine engine(NiFiClient nifiClient,
                                   TraceabilityManager traceabilityManager,
                                   IncrementalStateService stateService) {
        return new ExecutionEngine(
                nifiClient,
                traceabilityManager,
                new SuccessfulMetricsService(),
                stateService,
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

    private MigrationContract contractWithLogicalKeys() {
        MigrationContract contract = new MigrationContract();
        MigrationInfo migration = new MigrationInfo();
        migration.setName("blocked-dependency-test");
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
        tables.put("pedidos", table("public", "pedidos"));
        contract.setTables(tables);
        return contract;
    }

    private MigrationContract contractWithIncrementalDependent() {
        MigrationContract contract = contractWithLogicalKeys();
        Map<String, TableMigration> tables = new LinkedHashMap<>();
        tables.put("clientes", table("public", "clientes"));
        tables.put("pedidos", incrementalTable("public", "pedidos"));
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

    private TableRef tableRef(String schema, String tableName) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(tableName);
        return ref;
    }

    private static final class RecordingNiFiClient extends NiFiClient {
        private final List<String> uploadedGroups = new ArrayList<>();
        private final List<String> states = new ArrayList<>();
        private final List<String> cleanedGroups = new ArrayList<>();
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
            uploadedGroups.add(groupName);
            if (groupName.equals(failUploadsForGroup)) {
                throw new IllegalStateException("Fallo simulado en tabla padre");
            }
            return "pg-" + groupName;
        }

        @Override
        public void changeProcessGroupState(String processGroupId, String state) {
            states.add(state);
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
            cleanedGroups.add(processGroupId);
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

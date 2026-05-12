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

public class ExecutionEngineFullMigrationOrderTest extends TestCase {

    public void testFullMigrationExecutesTablesInProvidedOrderThroughNiFiClient() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        StubTableMetricsService metricsService = new StubTableMetricsService();
        IncrementalStateService stateService = stateService();
        ExecutionEngine engine = new ExecutionEngine(
                nifiClient,
                traceabilityManager,
                metricsService,
                stateService,
                0L,
                0L,
                4
        );

        MigrationContract contract = contract();
        List<TableNode> executionOrder = List.of(new TableNode("clientes"), new TableNode("pedidos"));

        engine.executeMigration(executionOrder, contract, List.of(new ForeignKeyDependency("clientes", "pedidos")));

        assertEquals(List.of("Migracion_clientes", "Migracion_pedidos"), nifiClient.uploadedGroups);
        assertEquals(List.of("pg-Migracion_clientes", "pg-Migracion_pedidos"), nifiClient.cleanedGroups);
        assertEquals(List.of("clientes", "pedidos"), traceabilityManager.summary.executionOrder);
        assertEquals(2, traceabilityManager.summary.tables.successful);
        assertEquals(0, traceabilityManager.summary.tables.failed);
        assertEquals(0, traceabilityManager.summary.tables.blocked);
        assertEquals("SUCCESS", traceabilityManager.tableTraces.get(0).status);
        assertEquals("SUCCESS", traceabilityManager.tableTraces.get(1).status);
        assertEquals("INSERT", nifiClient.uploadedVariables.get(0).get("##STATEMENT_TYPE##"));
        assertEquals("", nifiClient.uploadedVariables.get(0).get("##UPDATE_KEYS##"));
        assertEquals("SELECT * FROM public.clientes", nifiClient.uploadedVariables.get(0).get("##QUERY_SQL##"));
        assertNull(stateService.getState("public.clientes"));
        assertNull(stateService.getState("public.pedidos"));
    }

    public void testProductionNiFiRestBoundaryStaysInsideNiFiClient() throws Exception {
        List<Path> productionFiles = Files.walk(Path.of("src/main/java/es/upm/tfg/topomigrator"))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .toList();

        for (Path file : productionFiles) {
            String normalized = file.toString().replace('\\', '/');
            if (normalized.endsWith("execution/NiFiClient.java")) {
                continue;
            }
            String source = Files.readString(file);
            assertFalse("Solo NiFiClient debe construir endpoints /nifi-api: " + file,
                    source.contains("/nifi-api"));
            assertFalse("Solo NiFiClient debe usar HttpClient para REST NiFi: " + file,
                    source.contains("HttpClient"));
            assertFalse("Solo NiFiClient debe construir HttpRequest para REST NiFi: " + file,
                    source.contains("HttpRequest"));
        }
    }

    public void testMonitoringTestSeamRejectsInvalidTimingConfiguration() {
        RecordingNiFiClient nifiClient = new RecordingNiFiClient();
        RecordingTraceabilityManager traceabilityManager = new RecordingTraceabilityManager();
        StubTableMetricsService metricsService = new StubTableMetricsService();

        try {
            new ExecutionEngine(nifiClient, traceabilityManager, metricsService, stateService(), -1L, 0L, 1);
            fail("No debe aceptar tiempos de espera negativos.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no pueden ser negativos"));
        }

        try {
            new ExecutionEngine(nifiClient, traceabilityManager, metricsService, stateService(), 0L, 0L, 0);
            fail("No debe aceptar cero comprobaciones de monitorizacion.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("debe ser positivo"));
        }
    }

    private MigrationContract contract() {
        MigrationContract contract = new MigrationContract();
        MigrationInfo migration = new MigrationInfo();
        migration.setName("full-order-test");
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

    private IncrementalStateService stateService() {
        try {
            return new IncrementalStateService(Files.createTempDirectory("topomigrator-state").resolve("incremental-state.json"));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear estado incremental temporal para test.", e);
        }
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
        private final List<String> uploadedGroups = new ArrayList<>();
        private final List<Map<String, String>> uploadedVariables = new ArrayList<>();
        private final List<String> cleanedGroups = new ArrayList<>();

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

    private static final class StubTableMetricsService extends TableMetricsService {
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

package es.upm.tfg.topomigrator.execution;

import com.google.gson.JsonObject;
import es.upm.tfg.topomigrator.audit.SummaryTrace;
import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.audit.TraceabilityManager;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExecutionEngineQualityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void executeMigrationWritesSuccessfulSummaryAndTableTraceWithoutExecutionOrderField() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(Map.of("customers", 20L), Map.of("customers", List.of(0L, 20L)));
        ExecutionEngine engine = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));

        engine.executeMigration(List.of(new TableNode("customers")), contract, List.of());

        assertEquals(1, traces.summary.tables.total);
        assertEquals(1, traces.summary.tables.successful);
        assertEquals("SUCCESS", traces.tableTraces.get(0).status);
        assertEquals(20L, traces.tableTraces.get(0).recordsProcessed);
        assertFalse("Las trazas individuales no deben exponer executionOrder",
                List.of(TableTrace.class.getDeclaredFields()).stream().anyMatch(field -> field.getName().equals("executionOrder")));
        assertTrue(nifi.uploadedGroups.contains("Migracion_customers"));
    }

    @Test
    public void executeMigrationBlocksDependentTablesAfterParentFailure() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        nifi.failUploadsForTable = "customers";
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(
                Map.of("customers", 5L, "orders", 7L),
                Map.of("customers", List.of(0L), "orders", List.of(0L))
        );
        ExecutionEngine engine = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(
                QualityTestData.fullTable("customers"),
                QualityTestData.fullTable("orders")
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> engine.executeMigration(
                List.of(new TableNode("customers"), new TableNode("orders")),
                contract,
                List.of(new ForeignKeyDependency("customers", "orders"))
        ));

        assertTrue(error.getMessage().contains("tabla(s) fallida(s)"));
        assertEquals(2, traces.summary.tables.total);
        assertEquals(1, traces.summary.tables.failed);
        assertEquals(1, traces.summary.tables.blocked);
        assertEquals("FAILED", traces.tableTraces.get(0).status);
        assertEquals("BLOCKED", traces.tableTraces.get(1).status);
        assertEquals(List.of("Migracion_customers"), nifi.uploadedGroups);
    }

    @Test
    public void executeMigrationDoesNotBlockTableForSelfReferencingForeignKey() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(Map.of("employees", 3L), Map.of("employees", List.of(0L, 3L)));
        ExecutionEngine engine = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("employees"));

        engine.executeMigration(
                List.of(new TableNode("employees")),
                contract,
                List.of(new ForeignKeyDependency("employees", "employees"))
        );

        assertEquals(1, traces.summary.tables.successful);
        assertEquals(0, traces.summary.tables.blocked);
        assertEquals("SUCCESS", traces.tableTraces.get(0).status);
        assertEquals(List.of("Migracion_employees"), nifi.uploadedGroups);
    }

    private ExecutionEngine engine(RecordingNiFiClient nifi, RecordingTraceabilityManager traces, CountingMetricsService metrics) throws Exception {
        Path stateFile = temporaryFolder.newFolder("state").toPath().resolve("incremental-state.json");
        return new ExecutionEngine(nifi, traces, metrics, new IncrementalStateService(stateFile), 0L, 0L, 5);
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

    private static final class RecordingNiFiClient extends NiFiClient {
        private final List<String> uploadedGroups = new ArrayList<>();
        private String failUploadsForTable;

        private RecordingNiFiClient() {
            super("https://nifi.invalid/nifi-api", "user", "password", false);
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
            if (groupName.equals("Migracion_" + failUploadsForTable)) {
                throw new RuntimeException("Simulated NiFi upload failure for " + groupName);
            }
            return groupName + "-id";
        }

        @Override
        public void enableControllerServicesRecursively(String processGroupId) {
        }

        @Override
        public void changeProcessGroupState(String processGroupId, String state) {
        }

        @Override
        public JsonObject getProcessGroupStatus(String processGroupId) {
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("activeThreadCount", 0);
            snapshot.addProperty("queuedCount", "0");
            snapshot.addProperty("bytesRead", "0 bytes");
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

    private static final class CountingMetricsService extends TableMetricsService {
        private final Map<String, Long> sourceRows;
        private final Map<String, List<Long>> targetRows;
        private final Map<String, Integer> targetCalls = new java.util.HashMap<>();

        private CountingMetricsService(Map<String, Long> sourceRows, Map<String, List<Long>> targetRows) {
            this.sourceRows = sourceRows;
            this.targetRows = targetRows;
        }

        @Override
        public long countSourceSelectedRows(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
            return sourceRows.getOrDefault(tableConfig.getTarget().getTable(), 0L);
        }

        @Override
        public long countTargetRows(MigrationContract contract, TableMigration tableConfig) {
            String table = tableConfig.getTarget().getTable();
            int callIndex = targetCalls.merge(table, 1, Integer::sum) - 1;
            List<Long> values = targetRows.getOrDefault(table, List.of(0L));
            return values.get(Math.min(callIndex, values.size() - 1));
        }
    }
}

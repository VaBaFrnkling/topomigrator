package es.upm.tfg.topomigrator.execution;

import com.google.gson.JsonObject;
import es.upm.tfg.topomigrator.audit.ErrorArtifactWriter;
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
import java.util.HashMap;
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
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));

        harness.engine.executeMigration(List.of(new TableNode("customers")), contract, List.of());

        assertEquals(1, traces.summary.tables.total);
        assertEquals(1, traces.summary.tables.successful);
        assertEquals("SUCCESS", traces.tableTraces.get(0).status);
        assertEquals(20L, traces.tableTraces.get(0).recordsProcessed);
        assertFalse("Las trazas individuales no deben exponer executionOrder",
                List.of(TableTrace.class.getDeclaredFields()).stream().anyMatch(field -> field.getName().equals("executionOrder")));
        assertEquals(1, nifi.uploadedGroups.size());
        assertTrue(nifi.uploadedGroups.get(0).contains(traces.summary.executionId));
        assertTrue(nifi.uploadedGroups.get(0).contains("Migracion_customers"));
        assertEquals(List.of(nifi.uploadedGroups.get(0) + "-id"), nifi.readinessValidatedGroups);
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
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(
                QualityTestData.fullTable("customers"),
                QualityTestData.fullTable("orders")
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> harness.engine.executeMigration(
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
        assertEquals(1, nifi.uploadedGroups.size());
        assertTrue(nifi.uploadedGroups.get(0).contains("Migracion_customers"));
        assertEquals(List.of("NIFI_TABLE_EXECUTION:public.customers:RuntimeException"), harness.errors.tableErrors);
        assertEquals(List.of(), harness.errors.executionErrors);
    }

    @Test
    public void executeMigrationDoesNotBlockTableForSelfReferencingForeignKey() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(Map.of("employees", 3L), Map.of("employees", List.of(0L, 3L)));
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("employees"));

        harness.engine.executeMigration(
                List.of(new TableNode("employees")),
                contract,
                List.of(new ForeignKeyDependency("employees", "employees"))
        );

        assertEquals(1, traces.summary.tables.successful);
        assertEquals(0, traces.summary.tables.blocked);
        assertEquals("SUCCESS", traces.tableTraces.get(0).status);
        assertEquals(1, nifi.uploadedGroups.size());
        assertTrue(nifi.uploadedGroups.get(0).contains("Migracion_employees"));
    }

    @Test
    public void executeMigrationKeepsFunctionalSuccessWhenProcessGroupCleanupFails() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        nifi.failCleanup = true;
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(Map.of("customers", 20L), Map.of("customers", List.of(0L, 20L)));
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));

        harness.engine.executeMigration(List.of(new TableNode("customers")), contract, List.of());

        assertEquals(1, traces.summary.tables.successful);
        assertEquals(0, traces.summary.tables.failed);
        assertEquals("SUCCESS", traces.tableTraces.get(0).status);
        assertEquals("SUCCESS", traces.tableTraces.get(0).migrationStatus);
        assertEquals(20L, traces.tableTraces.get(0).recordsProcessed);
        assertEquals("FAILED", traces.tableTraces.get(0).cleanup.status);
        assertTrue(traces.tableTraces.get(0).cleanup.processGroupId.contains("Migracion_customers"));
        assertTrue(traces.tableTraces.get(0).cleanup.message.contains("Fallo operacional de cleanup"));
        assertTrue(traces.tableTraces.get(0).auditMetrics.warnings.stream()
                .anyMatch(warning -> warning.contains("Fallo operacional de cleanup")));
    }

    @Test
    public void executeMigrationDoesNotBlockDependentsWhenParentCleanupFails() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        nifi.failCleanup = true;
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(
                Map.of("customers", 5L, "orders", 7L),
                Map.of("customers", List.of(0L, 5L), "orders", List.of(0L, 7L))
        );
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(
                QualityTestData.fullTable("customers"),
                QualityTestData.fullTable("orders")
        );

        harness.engine.executeMigration(
                List.of(new TableNode("customers"), new TableNode("orders")),
                contract,
                List.of(new ForeignKeyDependency("customers", "orders"))
        );

        assertEquals(2, traces.summary.tables.successful);
        assertEquals(0, traces.summary.tables.blocked);
        assertEquals("SUCCESS", traces.tableTraces.get(0).status);
        assertEquals("FAILED", traces.tableTraces.get(0).cleanup.status);
        assertEquals("SUCCESS", traces.tableTraces.get(1).status);
    }

    @Test
    public void executeMigrationContinuesWhenStaleProcessGroupsCannotBeCleaned() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        nifi.failStaleCleanup = true;
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(Map.of("customers", 20L), Map.of("customers", List.of(0L, 20L)));
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));

        harness.engine.executeMigration(List.of(new TableNode("customers")), contract, List.of());

        assertEquals(1, traces.summary.tables.successful);
        assertEquals(1, nifi.staleCleanupWarnings.size());
        assertTrue(nifi.staleCleanupWarnings.get(0).contains("stale NiFi state could not be cleaned"));
        assertEquals(List.of(), harness.errors.executionErrors);
    }

    @Test
    public void executeMigrationFailsWhenNiFiOperationalReadinessValidationFails() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        nifi.failOperationalReadiness = true;
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(Map.of("customers", 20L), Map.of("customers", List.of(0L, 20L)));
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> harness.engine.executeMigration(List.of(new TableNode("customers")), contract, List.of()));

        assertTrue(error.getMessage().contains("tabla(s) fallida(s)"));
        assertEquals("FAILED", traces.tableTraces.get(0).status);
        assertTrue(traces.tableTraces.get(0).errors.get(0).contains("Controller Services no operativos"));
    }

    @Test
    public void executeMigrationFailsWhenAuditCountersMismatch() throws Exception {
        RecordingNiFiClient nifi = new RecordingNiFiClient();
        RecordingTraceabilityManager traces = new RecordingTraceabilityManager();
        CountingMetricsService metrics = new CountingMetricsService(Map.of("customers", 20L), Map.of("customers", List.of(0L, 12L)));
        EngineHarness harness = engine(nifi, traces, metrics);
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> harness.engine.executeMigration(List.of(new TableNode("customers")), contract, List.of()));

        assertTrue(error.getMessage().contains("tabla(s) fallida(s)"));
        assertEquals("FAILED", traces.tableTraces.get(0).status);
        assertEquals("MISMATCH", traces.tableTraces.get(0).auditMetrics.consistencyStatus);
        assertTrue(traces.tableTraces.get(0).errors.get(0).contains("inconsistencia"));
    }

    private EngineHarness engine(RecordingNiFiClient nifi, RecordingTraceabilityManager traces, CountingMetricsService metrics) throws Exception {
        Path stateFile = temporaryFolder.newFolder("state").toPath().resolve("incremental-state.json");
        RecordingErrorArtifactWriter errors = new RecordingErrorArtifactWriter();
        ExecutionEngine engine = new ExecutionEngine(nifi, traces, metrics, new IncrementalStateService(stateFile),
                errors, 0L, 0L, 5);
        return new EngineHarness(engine, errors);
    }

    private record EngineHarness(ExecutionEngine engine, RecordingErrorArtifactWriter errors) {
    }

    private static final class RecordingErrorArtifactWriter extends ErrorArtifactWriter {
        private final List<String> tableErrors = new ArrayList<>();
        private final List<String> executionErrors = new ArrayList<>();

        @Override
        public void writeTableError(String executionId,
                                    String tableExecutionId,
                                    String executionPhase,
                                    String table,
                                    Exception error) {
            tableErrors.add(executionPhase + ":" + table + ":" + error.getClass().getSimpleName());
        }

        @Override
        public void writeExecutionError(String executionId, String executionPhase, Exception error) {
            executionErrors.add(executionPhase + ":" + error.getClass().getSimpleName());
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

    private static final class RecordingNiFiClient extends NiFiClient {
        private final List<String> uploadedGroups = new ArrayList<>();
        private final List<String> readinessValidatedGroups = new ArrayList<>();
        private final List<String> staleCleanupWarnings = new ArrayList<>();
        private String failUploadsForTable;
        private boolean failCleanup;
        private boolean failStaleCleanup;
        private boolean failOperationalReadiness;

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
        public void cleanupStaleTopomigratorProcessGroups(String rootProcessGroupId) {
            if (failStaleCleanup) {
                staleCleanupWarnings.add("stale NiFi state could not be cleaned");
            }
        }

        @Override
        public String uploadFlowDefinition(String parentId, String groupName, int positionY, Path flowJsonPath, Map<String, String> dynamicVariables) {
            uploadedGroups.add(groupName);
            if (groupName.contains("Migracion_" + failUploadsForTable)) {
                throw new RuntimeException("Simulated NiFi upload failure for " + groupName);
            }
            return groupName + "-id";
        }

        @Override
        public void enableControllerServicesRecursively(String processGroupId) {
        }

        @Override
        public void validateProcessGroupOperationalReadiness(String processGroupId) {
            readinessValidatedGroups.add(processGroupId);
            if (failOperationalReadiness) {
                throw new RuntimeException("Controller Services no operativos");
            }
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
            if (failCleanup) {
                throw new RuntimeException("cleanup failed");
            }
        }
    }

    private static final class CountingMetricsService extends TableMetricsService {
        private final Map<String, Long> sourceRows;
        private final Map<String, List<Long>> targetRows;
        private final Map<String, Integer> targetCalls = new HashMap<>();

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

        @Override
        public String buildSourceSelectSql(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
            return buildSourceSelectSql(tableConfig, effectiveStartValue);
        }

        @Override
        public List<String> getTargetPrimaryKeyColumns(MigrationContract contract, TableMigration tableConfig) {
            return List.of("id");
        }
    }
}

package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FlowVariableBuilderQualityTest {

    private final TableMetricsService metricsService = new TableMetricsService() {
        @Override
        public String buildSourceSelectSql(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
            return buildSourceSelectSql(tableConfig, effectiveStartValue);
        }

        @Override
        public List<String> getTargetPrimaryKeyColumns(MigrationContract contract, TableMigration tableConfig) {
            return List.of("id");
        }
    };
    private final FlowVariableBuilder builder = new FlowVariableBuilder(metricsService);

    @Test
    public void buildCreatesAllNiFiTokensForFullMigration() {
        TableMigration table = QualityTestData.fullTable("customers");
        MigrationContract contract = QualityTestData.contractWith(table);
        TableTrace trace = tableTrace(table);

        Map<String, String> variables = builder.build("exec-001", contract, table, trace, null);

        assertEquals(expectedNiFiTokens(), variables.keySet());
        assertEquals("exec-001", variables.get("##EXECUTION_ID##"));
        assertEquals("customers", variables.get("##TABLA_ORIGEN##"));
        assertEquals("public", variables.get("##ESQUEMA_DESTINO##"));
        assertEquals("UPSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("id", variables.get("##UPDATE_KEYS##"));
        assertEquals("SELECT * FROM public.customers", variables.get("##QUERY_SQL##"));
        assertEquals("jdbc:postgresql://source:5432/source_db", variables.get("##SOURCE_DB_URL##"));
        assertEquals("PostgreSQL", variables.get("##TARGET_DB_TYPE##"));
        assertTrue(trace.auditMetrics.warnings.get(0).contains("Migracion full"));
    }

    @Test
    public void buildConfiguresIncrementalUpsertWithIdempotencyKeysAndCursorSql() {
        TableMigration table = QualityTestData.incrementalTable("orders");
        MigrationContract contract = QualityTestData.contractWith(table);
        TableTrace trace = tableTrace(table);

        Map<String, String> variables = builder.build("exec-002", contract, table, trace, "2026-05-01T00:00:00");

        assertEquals("UPSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("id", variables.get("##UPDATE_KEYS##"));
        assertEquals("SELECT * FROM public.orders WHERE updated_at > '2026-05-01T00:00:00' ORDER BY updated_at ASC LIMIT 100",
                variables.get("##QUERY_SQL##"));
        assertTrue(trace.auditMetrics.warnings.get(0).contains("UPSERT"));
    }

    @Test
    public void buildSourceSelectSqlOrdersFullSelfReferencingTableByHierarchy() {
        TableMigration table = QualityTestData.fullTable("employees");

        String sql = new TableMetricsService().buildSourceSelectSql(
                table,
                null,
                new TableMetricsService.SelfReferenceOrdering(List.of("id", "name", "manager_id"), "id", "manager_id")
        );

        assertTrue(sql.startsWith("WITH RECURSIVE topo_source AS (SELECT * FROM public.employees)"));
        assertTrue(sql.contains("JOIN topo_self_fk_order parent ON child.manager_id = parent.id"));
        assertTrue(sql.endsWith("SELECT id, name, manager_id FROM topo_ranked ORDER BY topo_depth ASC, id ASC"));
    }

    @Test
    public void buildFailsForFullMigrationWhenPrimaryKeyCannotBeDetected() {
        TableMetricsService noPrimaryKeyMetrics = new TableMetricsService() {
            @Override
            public String buildSourceSelectSql(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) {
                return buildSourceSelectSql(tableConfig, effectiveStartValue);
            }

            @Override
            public List<String> getTargetPrimaryKeyColumns(MigrationContract contract, TableMigration tableConfig) {
                return List.of();
            }
        };
        FlowVariableBuilder localBuilder = new FlowVariableBuilder(noPrimaryKeyMetrics);
        TableMigration table = QualityTestData.fullTable("customers");
        MigrationContract contract = QualityTestData.contractWith(table);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> localBuilder.build("exec-003", contract, table, tableTrace(table), null));

        assertTrue(error.getMessage().contains("UPSERT"));
        assertTrue(error.getMessage().contains("clave primaria"));
    }

    private TableTrace tableTrace(TableMigration table) {
        TableTrace trace = new TableTrace();
        trace.table = new TableTrace.TableMapping();
        trace.table.source = new TableTrace.SchemaTable();
        trace.table.source.schema = table.getSource().getSchema();
        trace.table.source.name = table.getSource().getTable();
        trace.table.target = new TableTrace.SchemaTable();
        trace.table.target.schema = table.getTarget().getSchema();
        trace.table.target.name = table.getTarget().getTable();
        trace.auditMetrics = new TableTrace.AuditMetrics();
        trace.auditMetrics.warnings = new java.util.ArrayList<>();
        return trace;
    }

    private Set<String> expectedNiFiTokens() {
        return new LinkedHashSet<>(java.util.List.of(
                "##EXECUTION_ID##",
                "##TABLA_ORIGEN##",
                "##ESQUEMA_ORIGEN##",
                "##TABLA_DESTINO##",
                "##ESQUEMA_DESTINO##",
                "##SOURCE_DB_URL##",
                "##SOURCE_DB_USER##",
                "##SOURCE_DB_PASSWORD##",
                "##SOURCE_DB_DRIVER##",
                "##SOURCE_DB_DRIVER_LOCATION##",
                "##TARGET_DB_URL##",
                "##TARGET_DB_USER##",
                "##TARGET_DB_PASSWORD##",
                "##TARGET_DB_DRIVER##",
                "##TARGET_DB_DRIVER_LOCATION##",
                "##TARGET_DB_TYPE##",
                "##STATEMENT_TYPE##",
                "##UPDATE_KEYS##",
                "##QUERY_SQL##"
        ));
    }
}

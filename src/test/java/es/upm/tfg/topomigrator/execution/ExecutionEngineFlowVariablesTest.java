package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.FilterConfig;
import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExecutionEngineFlowVariablesTest extends TestCase {

    public void testBuildsFullMigrationVariablesFromContractAndTableTrace() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new TableMetricsService());
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("origen", "clientes_src", "destino", "clientes_dst", "full");
        TableTrace trace = trace(table);

        Map<String, String> variables = builder.build("exec-001", contract, table, trace, null);

        assertEquals("exec-001", variables.get("##EXECUTION_ID##"));
        assertEquals("clientes_src", variables.get("##TABLA_ORIGEN##"));
        assertEquals("origen", variables.get("##ESQUEMA_ORIGEN##"));
        assertEquals("clientes_dst", variables.get("##TABLA_DESTINO##"));
        assertEquals("destino", variables.get("##ESQUEMA_DESTINO##"));
        assertEquals("jdbc:postgresql://source/db", variables.get("##SOURCE_DB_URL##"));
        assertEquals("source-user", variables.get("##SOURCE_DB_USER##"));
        assertEquals("source-secret", variables.get("##SOURCE_DB_PASSWORD##"));
        assertEquals("jdbc:postgresql://target/db", variables.get("##TARGET_DB_URL##"));
        assertEquals("target-user", variables.get("##TARGET_DB_USER##"));
        assertEquals("target-secret", variables.get("##TARGET_DB_PASSWORD##"));
        assertEquals("INSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("", variables.get("##UPDATE_KEYS##"));
        assertEquals("SELECT * FROM origen.clientes_src", variables.get("##QUERY_SQL##"));
    }

    public void testDefaultsBlankSchemasDriversLocationsAndTargetDatabaseType() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new TableMetricsService());
        ConnectionConfig source = connection("jdbc:postgresql://source/db", "source-user", "source-secret");
        source.setDriver(" ");
        source.setDriverLocation("");
        ConnectionConfig target = connection("jdbc:mysql://target/db", "target-user", "target-secret");
        target.setDriver(null);
        target.setDriverLocation(null);
        target.setDatabaseType(null);
        MigrationContract contract = contract(source, target);
        TableMigration table = table(" ", "items_src", null, "items_dst", "full");
        TableTrace trace = trace(table);

        Map<String, String> variables = builder.build("exec-002", contract, table, trace, null);

        assertEquals("public", variables.get("##ESQUEMA_ORIGEN##"));
        assertEquals("public", variables.get("##ESQUEMA_DESTINO##"));
        assertEquals("items_src", variables.get("##TABLA_ORIGEN##"));
        assertEquals("items_dst", variables.get("##TABLA_DESTINO##"));
        assertEquals("org.postgresql.Driver", variables.get("##SOURCE_DB_DRIVER##"));
        assertEquals("/opt/nifi/drivers/postgresql-42.7.10.jar", variables.get("##SOURCE_DB_DRIVER_LOCATION##"));
        assertEquals("org.postgresql.Driver", variables.get("##TARGET_DB_DRIVER##"));
        assertEquals("/opt/nifi/drivers/postgresql-42.7.10.jar", variables.get("##TARGET_DB_DRIVER_LOCATION##"));
        assertEquals("MySQL", variables.get("##TARGET_DB_TYPE##"));
    }

    public void testConfiguredTargetDatabaseTypeOverridesInference() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new TableMetricsService());
        ConnectionConfig target = connection("jdbc:postgresql://target/db", "target-user", "target-secret");
        target.setDatabaseType("PostgreSQL");
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"), target);
        TableMigration table = table("public", "clientes", "public", "clientes", "full");

        Map<String, String> variables = builder.build("exec-003", contract, table, trace(table), null);

        assertEquals("PostgreSQL", variables.get("##TARGET_DB_TYPE##"));
    }

    public void testBuildsIncrementalUpsertVariablesAndQuerySql() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new TableMetricsService());
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "pedidos_src", "warehouse", "pedidos_dst", "incremental");
        IncrementalConfig incremental = new IncrementalConfig();
        incremental.setColumn("updated_at");
        incremental.setStartValue("2026-01-01T00:00:00");
        incremental.setBatchSize(500);
        incremental.setLoadStrategy("upsert");
        incremental.setIdempotencyKeyColumns(List.of(" id ", "", "tenant_id"));
        table.setIncrementalConfig(incremental);

        Map<String, String> variables = builder.build("exec-004", contract, table, trace(table), "2026-02-01T00:00:00");

        assertEquals("UPSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("id,tenant_id", variables.get("##UPDATE_KEYS##"));
        assertEquals("SELECT * FROM public.pedidos_src WHERE updated_at > '2026-02-01T00:00:00' ORDER BY updated_at ASC LIMIT 500",
                variables.get("##QUERY_SQL##"));
    }

    public void testConfiguredIdempotencyKeysAvoidPrimaryKeyLookup() {
        FailingPrimaryKeyMetricsService metricsService = new FailingPrimaryKeyMetricsService();
        FlowVariableBuilder builder = new FlowVariableBuilder(metricsService);
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "pedidos_src", "warehouse", "pedidos_dst", "incremental");
        IncrementalConfig incremental = incrementalConfig("updated_at", "2026-01-01T00:00:00", 500, "upsert");
        incremental.setIdempotencyKeyColumns(List.of(" id ", "tenant_id"));
        table.setIncrementalConfig(incremental);

        Map<String, String> variables = builder.build("exec-004a", contract, table, trace(table), "2026-02-01T00:00:00");

        assertFalse(metricsService.primaryKeyLookupCalled);
        assertEquals("UPSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("id,tenant_id", variables.get("##UPDATE_KEYS##"));
    }

    public void testIncrementalUpsertInfersUpdateKeysFromTargetPrimaryKey() {
        StubPrimaryKeyMetricsService metricsService = new StubPrimaryKeyMetricsService(List.of(" id ", "", "tenant_id"));
        FlowVariableBuilder builder = new FlowVariableBuilder(metricsService);
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "pedidos_src", "warehouse", "pedidos_dst", "incremental");
        table.setIncrementalConfig(incrementalConfig("updated_at", "2026-01-01T00:00:00", 500, "upsert"));

        Map<String, String> variables = builder.build("exec-004b", contract, table, trace(table), "2026-02-01T00:00:00");

        assertTrue(metricsService.primaryKeyLookupCalled);
        assertEquals("UPSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("id,tenant_id", variables.get("##UPDATE_KEYS##"));
    }

    public void testBlankIncrementalLoadStrategyDefaultsToUpsertWithDetectedPrimaryKey() {
        StubPrimaryKeyMetricsService metricsService = new StubPrimaryKeyMetricsService(List.of("id"));
        FlowVariableBuilder builder = new FlowVariableBuilder(metricsService);
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "pedidos_src", "warehouse", "pedidos_dst", "incremental");
        table.setIncrementalConfig(incrementalConfig("updated_at", "2026-01-01T00:00:00", 500, null));

        Map<String, String> variables = builder.build("exec-004d", contract, table, trace(table), "2026-02-01T00:00:00");

        assertTrue(metricsService.primaryKeyLookupCalled);
        assertEquals("UPSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("id", variables.get("##UPDATE_KEYS##"));
    }

    public void testIncrementalUpsertWithoutKeysOrPrimaryKeyFailsSafely() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new StubPrimaryKeyMetricsService(List.of()));
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "pedidos_src", "warehouse", "pedidos_dst", "incremental");
        table.setIncrementalConfig(incrementalConfig("updated_at", "2026-01-01T00:00:00", 500, "upsert"));

        try {
            builder.build("exec-004c", contract, table, trace(table), "2026-02-01T00:00:00");
            fail("UPSERT incremental sin idempotencyKeyColumns ni PK destino debe fallar.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("idempotencyKeyColumns"));
            assertTrue(expected.getMessage().contains("clave primaria"));
            assertTrue(expected.getMessage().contains("idempotencia"));
        }
    }

    public void testBuildsIncrementalQueryWithDefaultBatchSizeWhenBatchSizeIsMissing() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new TableMetricsService());
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "clientes_src", "warehouse", "clientes_dst", "incremental");
        IncrementalConfig incremental = new IncrementalConfig();
        incremental.setColumn("updated_at");
        incremental.setStartValue("2026-01-01T00:00:00");
        incremental.setLoadStrategy("append");
        table.setIncrementalConfig(incremental);

        TableTrace trace = trace(table);
        Map<String, String> variables = builder.build("exec-005", contract, table, trace, "2026-02-01T00:00:00");

        assertEquals("INSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("", variables.get("##UPDATE_KEYS##"));
        assertTrue(trace.auditMetrics.warnings.get(0).contains("append"));
        assertTrue(trace.auditMetrics.warnings.get(0).contains("no se garantiza idempotencia"));
        assertEquals("SELECT * FROM public.clientes_src WHERE updated_at > '2026-02-01T00:00:00' ORDER BY updated_at ASC LIMIT 1000",
                variables.get("##QUERY_SQL##"));
    }

    public void testBuildsIncrementalQueryCombiningIncrementalPredicateAndFilters() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new TableMetricsService());
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "pedidos_src", "warehouse", "pedidos_dst", "incremental");
        IncrementalConfig incremental = new IncrementalConfig();
        incremental.setColumn("updated_at");
        incremental.setStartValue("2026-01-01T00:00:00");
        incremental.setBatchSize(250);
        incremental.setLoadStrategy("append_only");
        table.setIncrementalConfig(incremental);
        FilterConfig filters = new FilterConfig();
        filters.setWhere("tenant_id = 7");
        table.setFilters(filters);

        TableTrace trace = trace(table);
        Map<String, String> variables = builder.build("exec-006", contract, table, trace, "2026-02-01T00:00:00");

        assertEquals("INSERT", variables.get("##STATEMENT_TYPE##"));
        assertEquals("", variables.get("##UPDATE_KEYS##"));
        assertTrue(trace.auditMetrics.warnings.get(0).contains("append_only"));
        assertTrue(trace.auditMetrics.warnings.get(0).contains("no se garantiza idempotencia"));
        assertEquals("SELECT * FROM public.pedidos_src WHERE updated_at > '2026-02-01T00:00:00' AND tenant_id = 7 ORDER BY updated_at ASC LIMIT 250",
                variables.get("##QUERY_SQL##"));
    }

    public void testUnsupportedIncrementalLoadStrategyFailsBeforeNiFiVariablesAreUsable() {
        FlowVariableBuilder builder = new FlowVariableBuilder(new TableMetricsService());
        MigrationContract contract = contract(connection("jdbc:postgresql://source/db", "source-user", "source-secret"),
                connection("jdbc:postgresql://target/db", "target-user", "target-secret"));
        TableMigration table = table("public", "pedidos_src", "warehouse", "pedidos_dst", "incremental");
        table.setIncrementalConfig(incrementalConfig("updated_at", "2026-01-01T00:00:00", 250, "merge"));

        try {
            builder.build("exec-006b", contract, table, trace(table), "2026-02-01T00:00:00");
            fail("loadStrategy no soportada debe fallar con diagnostico claro.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("loadStrategy"));
            assertTrue(expected.getMessage().contains("merge"));
            assertTrue(expected.getMessage().contains("upsert"));
            assertTrue(expected.getMessage().contains("append"));
        }
    }

    public void testJavaGeneratedTokensMatchMainMigrationFlowPlaceholders() throws Exception {
        Set<String> javaTokens = FlowVariableBuilder.generatedTokens();
        Set<String> flowTokens = extractTokens(Files.readString(Path.of("flows", "MainMigration.json")));

        assertEquals("Los tokens generados por Java deben coincidir exactamente con los placeholders del flujo NiFi.",
                flowTokens, javaTokens);
    }

    public void testFlowVariablePreparationDoesNotLogSecretsOrJwtValues() throws Exception {
        String engineSource = Files.readString(Path.of("src/main/java/es/upm/tfg/topomigrator/execution/ExecutionEngine.java"));
        String builderSource = Files.readString(Path.of("src/main/java/es/upm/tfg/topomigrator/execution/FlowVariableBuilder.java"));
        String nifiClientSource = Files.readString(Path.of("src/main/java/es/upm/tfg/topomigrator/execution/NiFiClient.java"));

        assertNoLoggerLineContains(engineSource, "##SOURCE_DB_PASSWORD##");
        assertNoLoggerLineContains(engineSource, "##TARGET_DB_PASSWORD##");
        assertNoLoggerLineContains(builderSource, "##SOURCE_DB_PASSWORD##");
        assertNoLoggerLineContains(builderSource, "##TARGET_DB_PASSWORD##");
        assertNoLoggerLineContains(nifiClientSource, "password");
        assertNoLoggerLineContains(nifiClientSource, "jwtToken");
    }

    private Set<String> extractTokens(String content) {
        Matcher matcher = Pattern.compile("##[A-Z0-9_]+##").matcher(content);
        Set<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private void assertNoLoggerLineContains(String source, String forbidden) {
        for (String line : source.split("\\R")) {
            if (line.contains("logger.") && line.contains(forbidden)) {
                fail("No se deben registrar secretos o JWT en logs: " + line.trim());
            }
        }
    }

    private MigrationContract contract(ConnectionConfig source, ConnectionConfig target) {
        DatabaseConfig database = new DatabaseConfig();
        database.setSourceConnection(source);
        database.setTargetConnection(target);
        MigrationContract contract = new MigrationContract();
        contract.setDatabase(database);
        return contract;
    }

    private ConnectionConfig connection(String jdbcUrl, String username, String password) {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriver("org.postgresql.Driver");
        config.setDriverLocation("/drivers/custom.jar");
        config.setDatabaseType("PostgreSQL");
        return config;
    }

    private TableMigration table(String sourceSchema, String sourceTable, String targetSchema, String targetTable, String migrationType) {
        TableMigration table = new TableMigration();
        table.setEnabled(true);
        table.setMigrationType(migrationType);
        table.setSource(tableRef(sourceSchema, sourceTable));
        table.setTarget(tableRef(targetSchema, targetTable));
        return table;
    }

    private IncrementalConfig incrementalConfig(String column, String startValue, Integer batchSize, String loadStrategy) {
        IncrementalConfig incremental = new IncrementalConfig();
        incremental.setColumn(column);
        incremental.setStartValue(startValue);
        incremental.setBatchSize(batchSize);
        incremental.setLoadStrategy(loadStrategy);
        return incremental;
    }

    private TableRef tableRef(String schema, String tableName) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(tableName);
        return ref;
    }

    private TableTrace trace(TableMigration table) {
        TableTrace trace = new TableTrace();
        trace.table = new TableTrace.TableMapping();
        trace.table.source = new TableTrace.SchemaTable();
        trace.table.source.schema = table.getSource().getSchema();
        trace.table.source.name = table.getSource().getTable();
        trace.table.target = new TableTrace.SchemaTable();
        trace.table.target.schema = table.getTarget().getSchema();
        trace.table.target.name = table.getTarget().getTable();
        return trace;
    }

    private static class StubPrimaryKeyMetricsService extends TableMetricsService {
        private final List<String> primaryKeyColumns;
        protected boolean primaryKeyLookupCalled;

        private StubPrimaryKeyMetricsService(List<String> primaryKeyColumns) {
            this.primaryKeyColumns = primaryKeyColumns;
        }

        @Override
        public List<String> getTargetPrimaryKeyColumns(MigrationContract contract, TableMigration tableConfig) {
            primaryKeyLookupCalled = true;
            return primaryKeyColumns;
        }
    }

    private static final class FailingPrimaryKeyMetricsService extends StubPrimaryKeyMetricsService {
        private FailingPrimaryKeyMetricsService() {
            super(List.of());
        }

        @Override
        public List<String> getTargetPrimaryKeyColumns(MigrationContract contract, TableMigration tableConfig) {
            primaryKeyLookupCalled = true;
            throw new IllegalStateException("No debe consultar PK si hay idempotencyKeyColumns configuradas.");
        }
    }
}

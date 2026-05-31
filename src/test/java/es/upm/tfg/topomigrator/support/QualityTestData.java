package es.upm.tfg.topomigrator.support;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.FilterConfig;
import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.MigrationInfo;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QualityTestData {

    private QualityTestData() {
    }

    public static MigrationContract contractWith(TableMigration... tables) {
        MigrationContract contract = new MigrationContract();
        MigrationInfo migration = new MigrationInfo();
        migration.setName("quality-suite");
        migration.setVersion("1.0");
        migration.setDescription("Contract used by quality tests");
        migration.setAuthor("quality-test");
        contract.setMigration(migration);

        DatabaseConfig database = new DatabaseConfig();
        database.setSourceConnection(connection("jdbc:postgresql://source:5432/source_db"));
        database.setTargetConnection(connection("jdbc:postgresql://target:5432/target_db"));
        contract.setDatabase(database);

        Map<String, TableMigration> tableMap = new LinkedHashMap<>();
        for (TableMigration table : tables) {
            tableMap.put(table.getTarget().getTable(), table);
        }
        contract.setTables(tableMap);
        return contract;
    }

    public static TableMigration fullTable(String tableName) {
        TableMigration table = baseTable(tableName);
        table.setMigrationType("full");
        return table;
    }

    public static TableMigration filteredFullTable(String tableName, String whereClause) {
        TableMigration table = fullTable(tableName);
        FilterConfig filters = new FilterConfig();
        filters.setWhere(whereClause);
        table.setFilters(filters);
        return table;
    }

    public static TableMigration incrementalTable(String tableName) {
        TableMigration table = baseTable(tableName);
        table.setMigrationType("incremental");

        IncrementalConfig incremental = new IncrementalConfig();
        incremental.setColumn("updated_at");
        incremental.setType("timestamp");
        incremental.setStartValue("2026-01-01T00:00:00");
        incremental.setBatchSize(100);
        incremental.setLoadStrategy("upsert");
        incremental.setIdempotencyKeyColumns(List.of("id"));
        table.setIncrementalConfig(incremental);
        return table;
    }

    public static TableMigration incrementalAppendTable(String tableName) {
        TableMigration table = incrementalTable(tableName);
        table.getIncrementalConfig().setLoadStrategy("append");
        return table;
    }

    public static TableMigration inactiveTable(String tableName) {
        TableMigration table = fullTable(tableName);
        table.setEnabled(false);
        return table;
    }

    public static ConnectionConfig connection(String jdbcUrl) {
        ConnectionConfig connection = new ConnectionConfig();
        connection.setDriver("org.postgresql.Driver");
        connection.setDriverLocation("/drivers/postgresql.jar");
        connection.setDatabaseType("PostgreSQL");
        connection.setJdbcUrl(jdbcUrl);
        connection.setUsername("test_user");
        connection.setPassword("test_password");
        return connection;
    }

    private static TableMigration baseTable(String tableName) {
        TableMigration table = new TableMigration();
        table.setEnabled(true);
        table.setSource(ref("public", tableName));
        table.setTarget(ref("public", tableName));
        return table;
    }

    private static TableRef ref(String schema, String tableName) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(tableName);
        return ref;
    }
}

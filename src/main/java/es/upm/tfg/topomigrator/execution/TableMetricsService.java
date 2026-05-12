package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.FilterConfig;
import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de métricas de auditoría basado en consultas SQL reales.
 * La métrica principal de filas procesadas se calcula contando las filas
 * que devuelve la consulta de origen equivalente a la usada por NiFi.
 *
 * Además, se puede calcular el delta neto en destino como validación auxiliar,
 * pero no como métrica principal, ya que puede verse afectado por escrituras
 * concurrentes o por re-ejecuciones.
 */
public class TableMetricsService {

    public long countSourceSelectedRows(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) throws Exception {
        String selectSql = buildSourceSelectSql(tableConfig, effectiveStartValue);
        String countSql = "SELECT COUNT(*) FROM (" + selectSql + ") topo_audit_src";
        return executeCount(contract.getDatabase().getSourceConnection(), countSql);
    }

    public long countSourceSelectedRows(MigrationContract contract, TableMigration tableConfig) throws Exception {
        return countSourceSelectedRows(contract, tableConfig, getConfiguredStartValue(tableConfig));
    }

    public long countTargetRows(MigrationContract contract, TableMigration tableConfig) throws Exception {
        String qualifiedTarget = qualifyName(
                tableConfig.getTarget().getSchema(),
                tableConfig.getTarget().getTable()
        );
        String countSql = "SELECT COUNT(*) FROM " + qualifiedTarget;
        return executeCount(contract.getDatabase().getTargetConnection(), countSql);
    }

    public String findLastSelectedIncrementalValue(MigrationContract contract, TableMigration tableConfig, String effectiveStartValue) throws Exception {
        String migrationType = normalizedMigrationType(tableConfig);
        if (!"incremental".equals(migrationType)) {
            return null;
        }

        IncrementalConfig incrementalConfig = tableConfig.getIncrementalConfig();
        if (incrementalConfig == null || isBlank(incrementalConfig.getColumn())) {
            throw new IllegalStateException("La migración incremental requiere column para calcular lastProcessedValue.");
        }

        String selectedRowsSql = buildSourceSelectSql(tableConfig, effectiveStartValue);
        String sql = "SELECT MAX(" + incrementalConfig.getColumn() + ") FROM (" + selectedRowsSql + ") topo_inc_state";
        return executeSingleString(contract.getDatabase().getSourceConnection(), sql);
    }

    public String buildSourceSelectSql(TableMigration tableConfig, String effectiveStartValue) {
        String migrationType = normalizedMigrationType(tableConfig);

        String qualifiedSource = qualifyName(
                tableConfig.getSource().getSchema(),
                tableConfig.getSource().getTable()
        );

        String whereClause = buildWhereClause(tableConfig, "incremental".equals(migrationType), effectiveStartValue);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualifiedSource);
        if (!isBlank(whereClause)) {
            sql.append(" ").append(whereClause);
        }

        if ("incremental".equals(migrationType)) {
            IncrementalConfig incrementalConfig = tableConfig.getIncrementalConfig();
            int batchSize = incrementalConfig != null && incrementalConfig.getBatchSize() != null
                    ? incrementalConfig.getBatchSize()
                    : 1000;

            sql.append(" ORDER BY ").append(incrementalConfig.getColumn()).append(" ASC")
                    .append(" LIMIT ").append(batchSize);
        }

        return sql.toString();
    }

    public String buildSourceSelectSql(TableMigration tableConfig) {
        return buildSourceSelectSql(tableConfig, getConfiguredStartValue(tableConfig));
    }

    public List<String> getTargetPrimaryKeyColumns(MigrationContract contract, TableMigration tableConfig) throws Exception {
        return getPrimaryKeyColumns(contract.getDatabase().getTargetConnection(),
                tableConfig.getTarget().getSchema(),
                tableConfig.getTarget().getTable());
    }

    private List<String> getPrimaryKeyColumns(ConnectionConfig connectionConfig, String schema, String table) throws Exception {
        if (connectionConfig.getDriver() != null && !connectionConfig.getDriver().isBlank()) {
            Class.forName(connectionConfig.getDriver());
        }

        try (Connection connection = DriverManager.getConnection(
                connectionConfig.getJdbcUrl(),
                connectionConfig.getUsername(),
                connectionConfig.getPassword())) {

            DatabaseMetaData metaData = connection.getMetaData();
            List<String> columns = readPrimaryKeyColumns(metaData, schema, table);
            if (!columns.isEmpty()) {
                return columns;
            }
            columns = readPrimaryKeyColumns(metaData, schema != null ? schema.toUpperCase() : null, table.toUpperCase());
            if (!columns.isEmpty()) {
                return columns;
            }
            return readPrimaryKeyColumns(metaData, schema != null ? schema.toLowerCase() : null, table.toLowerCase());
        }
    }

    private List<String> readPrimaryKeyColumns(DatabaseMetaData metaData, String schema, String table) throws Exception {
        List<String> columns = new ArrayList<>();
        String schemaPattern = schema != null && !schema.trim().isEmpty() ? schema : null;
        try (ResultSet rs = metaData.getPrimaryKeys(null, schemaPattern, table)) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (column != null && !column.isBlank()) {
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    private long executeCount(ConnectionConfig connectionConfig, String sql) throws Exception {
        if (connectionConfig.getDriver() != null && !connectionConfig.getDriver().isBlank()) {
            Class.forName(connectionConfig.getDriver());
        }

        try (Connection connection = DriverManager.getConnection(
                connectionConfig.getJdbcUrl(),
                connectionConfig.getUsername(),
                connectionConfig.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        }
    }

    private String executeSingleString(ConnectionConfig connectionConfig, String sql) throws Exception {
        if (connectionConfig.getDriver() != null && !connectionConfig.getDriver().isBlank()) {
            Class.forName(connectionConfig.getDriver());
        }

        try (Connection connection = DriverManager.getConnection(
                connectionConfig.getJdbcUrl(),
                connectionConfig.getUsername(),
                connectionConfig.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            if (rs.next()) {
                Object value = rs.getObject(1);
                return value != null ? value.toString() : null;
            }
            return null;
        }
    }

    private String buildWhereClause(TableMigration tableConfig, boolean incremental, String effectiveStartValue) {
        StringBuilder where = new StringBuilder();

        if (incremental) {
            IncrementalConfig incrementalConfig = tableConfig.getIncrementalConfig();
            if (incrementalConfig == null || isBlank(incrementalConfig.getColumn()) || effectiveStartValue == null) {
                throw new IllegalStateException("La migración incremental requiere column y startValue/estado previo.");
            }

            where.append("WHERE ")
                    .append(incrementalConfig.getColumn())
                    .append(" > '")
                    .append(escapeSqlLiteral(effectiveStartValue))
                    .append("'");
        }

        FilterConfig filters = tableConfig.getFilters();
        String userWhere = filters != null ? filters.getWhere() : null;
        if (!isBlank(userWhere)) {
            if (where.length() == 0) {
                where.append("WHERE ").append(userWhere.trim());
            } else {
                where.append(" AND ").append(userWhere.trim());
            }
        }

        return where.toString();
    }

    private String normalizedMigrationType(TableMigration tableConfig) {
        return tableConfig.getMigrationType() == null
                ? "full"
                : tableConfig.getMigrationType().trim().toLowerCase();
    }

    private String getConfiguredStartValue(TableMigration tableConfig) {
        return tableConfig.getIncrementalConfig() != null ? tableConfig.getIncrementalConfig().getStartValue() : null;
    }

    private String qualifyName(String schema, String table) {
        if (isBlank(schema)) {
            return table;
        }
        return schema + "." + table;
    }

    private String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

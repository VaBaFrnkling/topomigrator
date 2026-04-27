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

    public long countSourceSelectedRows(MigrationContract contract, TableMigration tableConfig) throws Exception {
        String selectSql = buildSourceSelectSql(tableConfig);
        String countSql = "SELECT COUNT(*) FROM (" + selectSql + ") topo_audit_src";
        return executeCount(contract.getDatabase().getSourceConnection(), countSql);
    }

    public long countTargetRows(MigrationContract contract, TableMigration tableConfig) throws Exception {
        String qualifiedTarget = qualifyName(
                tableConfig.getTarget().getSchema(),
                tableConfig.getTarget().getTable()
        );
        String countSql = "SELECT COUNT(*) FROM " + qualifiedTarget;
        return executeCount(contract.getDatabase().getTargetConnection(), countSql);
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

    private String buildSourceSelectSql(TableMigration tableConfig) {
        String migrationType = tableConfig.getMigrationType() == null
                ? "full"
                : tableConfig.getMigrationType().trim().toLowerCase();

        String qualifiedSource = qualifyName(
                tableConfig.getSource().getSchema(),
                tableConfig.getSource().getTable()
        );

        String whereClause = buildWhereClause(tableConfig, "incremental".equals(migrationType));

        if ("incremental".equals(migrationType)) {
            IncrementalConfig incrementalConfig = tableConfig.getIncrementalConfig();
            int batchSize = incrementalConfig != null && incrementalConfig.getBatchSize() != null
                    ? incrementalConfig.getBatchSize()
                    : 1000;

            return "SELECT * FROM " + qualifiedSource + " " +
                    whereClause + " " +
                    "ORDER BY " + incrementalConfig.getColumn() + " ASC " +
                    "LIMIT " + batchSize;
        }

        return "SELECT * FROM " + qualifiedSource + " " + whereClause;
    }

    private String buildWhereClause(TableMigration tableConfig, boolean incremental) {
        StringBuilder where = new StringBuilder();

        if (incremental) {
            IncrementalConfig incrementalConfig = tableConfig.getIncrementalConfig();
            if (incrementalConfig == null || isBlank(incrementalConfig.getColumn()) || incrementalConfig.getStartValue() == null) {
                throw new IllegalStateException("La migración incremental requiere column y startValue para auditoría.");
            }

            where.append("WHERE ")
                    .append(incrementalConfig.getColumn())
                    .append(" > '")
                    .append(escapeSqlLiteral(incrementalConfig.getStartValue()))
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

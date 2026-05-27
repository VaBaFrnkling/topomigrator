package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

class FlowVariableBuilder {

    private static final String DEFAULT_DRIVER = "org.postgresql.Driver";
    private static final String DEFAULT_DRIVER_LOCATION = "/opt/nifi/drivers/postgresql-42.7.10.jar";

    private final TableMetricsService metricsService;

    FlowVariableBuilder(TableMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    Map<String, String> build(String executionId,
                              MigrationContract contract,
                              TableMigration tableConfig,
                              TableTrace tableTrace,
                              String effectiveIncrementalStartValue) {
        Map<String, String> flowConfigVariables = new LinkedHashMap<>();
        flowConfigVariables.put("##EXECUTION_ID##", executionId);
        flowConfigVariables.put("##TABLA_ORIGEN##", tableTrace.table.source.name);
        flowConfigVariables.put("##ESQUEMA_ORIGEN##", defaultSchema(tableTrace.table.source.schema));
        flowConfigVariables.put("##TABLA_DESTINO##", tableTrace.table.target.name);
        flowConfigVariables.put("##ESQUEMA_DESTINO##", defaultSchema(tableTrace.table.target.schema));

        ConnectionConfig source = contract.getDatabase().getSourceConnection();
        ConnectionConfig target = contract.getDatabase().getTargetConnection();

        flowConfigVariables.put("##SOURCE_DB_URL##", source.getJdbcUrl());
        flowConfigVariables.put("##SOURCE_DB_USER##", source.getUsername());
        flowConfigVariables.put("##SOURCE_DB_PASSWORD##", source.getPassword());
        flowConfigVariables.put("##SOURCE_DB_DRIVER##", defaultValue(source.getDriver(), DEFAULT_DRIVER));
        flowConfigVariables.put("##SOURCE_DB_DRIVER_LOCATION##", defaultValue(source.getDriverLocation(), DEFAULT_DRIVER_LOCATION));

        flowConfigVariables.put("##TARGET_DB_URL##", target.getJdbcUrl());
        flowConfigVariables.put("##TARGET_DB_USER##", target.getUsername());
        flowConfigVariables.put("##TARGET_DB_PASSWORD##", target.getPassword());
        flowConfigVariables.put("##TARGET_DB_DRIVER##", defaultValue(target.getDriver(), DEFAULT_DRIVER));
        flowConfigVariables.put("##TARGET_DB_DRIVER_LOCATION##", defaultValue(target.getDriverLocation(), DEFAULT_DRIVER_LOCATION));
        flowConfigVariables.put("##TARGET_DB_TYPE##", defaultValue(target.getDatabaseType(), inferDatabaseType(target)));

        configureWriteMode(contract, tableConfig, tableTrace, flowConfigVariables);
        try {
            flowConfigVariables.put("##QUERY_SQL##", metricsService.buildSourceSelectSql(contract, tableConfig, effectiveIncrementalStartValue));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo construir la consulta SQL de origen para " + qualifiedTargetName(tableConfig) + ".", e);
        }

        return flowConfigVariables;
    }

    private void configureWriteMode(MigrationContract contract,
                                    TableMigration tableConfig,
                                    TableTrace tableTrace,
                                    Map<String, String> flowConfigVariables) {
        String migrationType = tableConfig.getMigrationType() != null
                ? tableConfig.getMigrationType().trim().toLowerCase(Locale.ROOT)
                : "full";
        String statementType = "INSERT";
        String updateKeys = "";

        if ("incremental".equals(migrationType)) {
            IncrementalConfig incrementalConfig = tableConfig.getIncrementalConfig();
            String loadStrategy = normalizeLoadStrategy(incrementalConfig != null ? incrementalConfig.getLoadStrategy() : null);

            if ("upsert".equals(loadStrategy)) {
                List<String> updateKeyColumns = cleanColumnList(
                        incrementalConfig != null ? incrementalConfig.getIdempotencyKeyColumns() : null
                );

                if (updateKeyColumns.isEmpty()) {
                    try {
                        updateKeyColumns = cleanColumnList(metricsService.getTargetPrimaryKeyColumns(contract, tableConfig));
                    } catch (Exception e) {
                        throw new IllegalStateException("No se pudieron detectar claves primarias para la migracion incremental de "
                                + qualifiedTargetName(tableConfig)
                                + ". Configure incrementalConfig.idempotencyKeyColumns o revise los metadatos de la tabla destino.", e);
                    }
                }

                if (updateKeyColumns.isEmpty()) {
                    throw new IllegalStateException("La tabla incremental " + qualifiedTargetName(tableConfig)
                            + " usa loadStrategy=upsert, pero no tiene idempotencyKeyColumns ni clave primaria detectable. "
                            + "No se puede garantizar idempotencia ante reejecuciones.");
                }

                statementType = "UPSERT";
                updateKeys = String.join(",", updateKeyColumns);
                addWarning(tableTrace, "Migracion incremental configurada en modo UPSERT con claves de idempotencia: " + updateKeys);
            } else if ("append".equals(loadStrategy) || "append_only".equals(loadStrategy)) {
                statementType = "INSERT";
                addWarning(tableTrace, "Migracion incremental configurada en modo " + loadStrategy
                        + ": se usa INSERT y no se garantiza idempotencia ante reejecuciones.");
            } else {
                throw new IllegalStateException("loadStrategy no soportada para migracion incremental en "
                        + qualifiedTargetName(tableConfig) + ": " + loadStrategy
                        + ". Valores permitidos: upsert, append, append_only.");
            }
        }

        flowConfigVariables.put("##STATEMENT_TYPE##", statementType);
        flowConfigVariables.put("##UPDATE_KEYS##", updateKeys);
    }

    private void addWarning(TableTrace tableTrace, String warning) {
        if (tableTrace.auditMetrics == null) {
            tableTrace.auditMetrics = new TableTrace.AuditMetrics();
        }
        if (tableTrace.auditMetrics.warnings == null) {
            tableTrace.auditMetrics.warnings = new ArrayList<>();
        }
        tableTrace.auditMetrics.warnings.add(warning);
    }

    private String normalizeLoadStrategy(String loadStrategy) {
        if (isBlank(loadStrategy)) {
            return "upsert";
        }
        return loadStrategy.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> cleanColumnList(List<String> columns) {
        if (columns == null) {
            return List.of();
        }
        return columns.stream()
                .filter(column -> !isBlank(column))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private String qualifiedTargetName(TableMigration tableConfig) {
        String schema = tableConfig.getTarget() != null ? tableConfig.getTarget().getSchema() : null;
        String table = tableConfig.getTarget() != null ? tableConfig.getTarget().getTable() : null;
        return defaultSchema(schema) + "." + table;
    }

    private String defaultSchema(String schema) {
        return !isBlank(schema) ? schema : "public";
    }

    private String defaultValue(String value, String fallback) {
        return !isBlank(value) ? value : fallback;
    }

    private String inferDatabaseType(ConnectionConfig connectionConfig) {
        String driver = connectionConfig.getDriver() != null ? connectionConfig.getDriver().toLowerCase(Locale.ROOT) : "";
        String url = connectionConfig.getJdbcUrl() != null ? connectionConfig.getJdbcUrl().toLowerCase(Locale.ROOT) : "";
        if (driver.contains("oracle") || url.contains(":oracle:")) {
            return "Oracle";
        }
        if (driver.contains("mysql") || url.contains(":mysql:")) {
            return "MySQL";
        }
        if (driver.contains("mariadb") || url.contains(":mariadb:")) {
            return "MariaDB";
        }
        if (driver.contains("sqlserver") || url.contains(":sqlserver:")) {
            return "MS SQL 2012+";
        }
        return "PostgreSQL";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

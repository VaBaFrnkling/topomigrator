package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LiquibaseSchemaExecutor {
    private static final Logger log = LoggerFactory.getLogger(LiquibaseSchemaExecutor.class);

    public static void applyTargetSchemas(MigrationContract contract) {
        applyTargetSchemas(contract, DatabaseConnectionManager::getConnection);
    }

    public static void applyTargetSchemas(MigrationContract contract, List<TableNode> executionOrder) {
        applyTargetSchemas(contract, executionOrder, DatabaseConnectionManager::getConnection);
    }

    static void applyTargetSchemas(MigrationContract contract, ConnectionFactory connectionFactory) {
        applyTargetSchemas(contract, resolveChangelogsDir(), connectionFactory);
    }

    static void applyTargetSchemas(MigrationContract contract, List<TableNode> executionOrder, ConnectionFactory connectionFactory) {
        applyTargetSchemas(contract, executionOrder, resolveChangelogsDir(), connectionFactory);
    }

    static void applyTargetSchemas(MigrationContract contract, Path changelogsDir, ConnectionFactory connectionFactory) {
        applyTargetSchemas(contract, null, changelogsDir, connectionFactory);
    }

    static void applyTargetSchemas(MigrationContract contract,
                                   List<TableNode> executionOrder,
                                   Path changelogsDir,
                                   ConnectionFactory connectionFactory) {
        log.info("Fase de Liquibase: desplegando esquemas DDL en destino para tablas activas.");

        if (contract == null || contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new InvalidChangelogException("No hay tablas activas sobre las que aplicar Liquibase.");
        }
        if (changelogsDir == null) {
            throw new InvalidChangelogException("El directorio de changelogs destino no puede ser nulo.");
        }

        Map<String, Path> changelogPathsByTable = resolveChangelogPaths(contract, changelogsDir);
        List<String> orderedTableIds = resolveTableExecutionSequence(contract, executionOrder);

        try (Connection targetConn = connectionFactory.getConnection(contract.getDatabase().getTargetConnection());
             DirectoryResourceAccessor resourceAccessor = new DirectoryResourceAccessor(changelogsDir.toAbsolutePath())) {
            applyChangelogs(contract, changelogPathsByTable, orderedTableIds, targetConn, resourceAccessor);
        } catch (InvalidChangelogException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Fallo critico durante el despliegue de Liquibase en destino", e);
        }

        log.info("Despliegue estructural de Liquibase completado.");
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection getConnection(ConnectionConfig config) throws SQLException;
    }

    private static Path resolveChangelogsDir() {
        return Paths.get("changelogs", "tables");
    }

    private static void applyChangelogs(MigrationContract contract,
                                        Map<String, Path> changelogPathsByTable,
                                        List<String> orderedTableIds,
                                        Connection targetConn,
                                        DirectoryResourceAccessor resourceAccessor) throws Exception {
        DatabaseMetaData targetMeta = targetConn.getMetaData();
        Database database = null;

        for (String tableId : orderedTableIds) {
            TableMigration tableMigration = contract.getTables().get(tableId);
            String targetSchema = tableMigration.getTarget().getSchema();
            String targetTable = tableMigration.getTarget().getTable();
            Path changelogPath = changelogPathsByTable.get(tableId);

            if (tableExists(targetMeta, targetSchema, targetTable)) {
                log.warn("La tabla destino '{}.{}' ya existe. No se ejecuta su createTable de Liquibase.",
                        targetSchema,
                        targetTable);
                continue;
            }

            log.info("Ejecutando Liquibase para '{}.{}' usando {}", targetSchema, targetTable, changelogPath.getFileName());
            try {
                if (database == null) {
                    database = DatabaseFactory.getInstance()
                            .findCorrectDatabaseImplementation(new JdbcConnection(targetConn));
                }
                Database databaseForUpdate = database;
                Map<String, Object> scopeAttrs = Map.of(Scope.Attr.resourceAccessor.name(), resourceAccessor);
                Scope.child(scopeAttrs, () -> new CommandScope("update")
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, databaseForUpdate)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, changelogPath.getFileName().toString())
                        .execute());
            } catch (Exception e) {
                throw new InvalidChangelogException("Error al ejecutar Liquibase para el changelog "
                        + changelogPath.getFileName() + " en la base de datos destino.", e);
            }
        }
    }

    private static List<String> resolveTableExecutionSequence(MigrationContract contract, List<TableNode> executionOrder) {
        if (executionOrder == null || executionOrder.isEmpty()) {
            return new ArrayList<>(contract.getTables().keySet());
        }

        List<String> orderedTableIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TableNode tableNode : executionOrder) {
            if (tableNode == null || tableNode.getName() == null || tableNode.getName().trim().isEmpty()) {
                throw new InvalidChangelogException("El orden de ejecución contiene una tabla nula o vacía.");
            }

            String tableId = resolveContractTableId(contract, tableNode.getName());
            if (!seen.add(tableId)) {
                throw new InvalidChangelogException("El orden de ejecución contiene la tabla duplicada: " + tableId);
            }
            orderedTableIds.add(tableId);
        }

        if (orderedTableIds.size() != contract.getTables().size()) {
            throw new InvalidChangelogException("El orden de ejecución no contiene todas las tablas activas del contrato.");
        }

        return orderedTableIds;
    }

    private static String resolveContractTableId(MigrationContract contract, String tableName) {
        if (contract.getTables().containsKey(tableName)) {
            return tableName;
        }
        for (String contractTableId : contract.getTables().keySet()) {
            if (contractTableId.equalsIgnoreCase(tableName)) {
                return contractTableId;
            }
        }
        throw new InvalidChangelogException("El orden de ejecución contiene una tabla no definida en el contrato: " + tableName);
    }

    private static Map<String, Path> resolveChangelogPaths(MigrationContract contract, Path changelogsDir) {
        Map<String, Path> changelogPathsByTable = new LinkedHashMap<>();
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String tableId = entry.getKey();
            TableMigration tableMigration = entry.getValue();

            if (tableMigration == null || tableMigration.getTarget() == null) {
                throw new InvalidChangelogException("La tabla activa '" + tableId
                        + "' no tiene destino valido para aplicar Liquibase.");
            }

            String targetSchema = tableMigration.getTarget().getSchema();
            String targetTable = tableMigration.getTarget().getTable();
            if (targetSchema == null || targetSchema.trim().isEmpty()
                    || targetTable == null || targetTable.trim().isEmpty()) {
                throw new InvalidChangelogException("La tabla activa '" + tableId
                        + "' debe definir target.schema y target.table para aplicar Liquibase.");
            }

            Path changelogPath = getChangelogPath(changelogsDir, targetSchema, targetTable);
            if (changelogPath == null) {
                throw new InvalidChangelogException("Changelog no encontrado para aplicar de la tabla activa: "
                        + targetSchema + "." + targetTable);
            }
            changelogPathsByTable.put(tableId, changelogPath);
        }
        return changelogPathsByTable;
    }

    private static Path getChangelogPath(Path directory, String targetSchema, String targetTable) {
        Path exactMatch = directory.resolve(targetSchema + "." + targetTable + ".yaml");
        if (Files.exists(exactMatch) && Files.isRegularFile(exactMatch)) {
            return exactMatch;
        }
        return null;
    }

    private static boolean tableExists(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        if (table == null || table.trim().isEmpty()) {
            return false;
        }

        if (readTableExists(metaData, schema, table)) {
            return true;
        }

        String upperSchema = schema != null ? schema.toUpperCase(Locale.ROOT) : null;
        if (readTableExists(metaData, upperSchema, table.toUpperCase(Locale.ROOT))) {
            return true;
        }

        String lowerSchema = schema != null ? schema.toLowerCase(Locale.ROOT) : null;
        return readTableExists(metaData, lowerSchema, table.toLowerCase(Locale.ROOT));
    }

    private static boolean readTableExists(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        String schemaPattern = schema != null && !schema.trim().isEmpty() ? schema : null;
        try (ResultSet rs = metaData.getTables(null, schemaPattern, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}

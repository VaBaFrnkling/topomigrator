package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ejecutor que conecta a la Base de Datos Destino y aplica manualmente
 * los ficheros de definicion de tablas Liquibase expuestos por el usuario.
 *
 * Convencion obligatoria: changelogs/tables/<schema>.<table>.yaml
 */
public class LiquibaseSchemaExecutor {
    private static final Logger log = LoggerFactory.getLogger(LiquibaseSchemaExecutor.class);

    /**
     * Despliega estructuralmente (DDL) los cambios de Liquibase en la Base de Datos
     * destino para las tablas activas del contrato ya filtrado.
     */
    public static void applyTargetSchemas(MigrationContract contract) {
        applyTargetSchemas(contract, DatabaseConnectionManager::getConnection);
    }

    static void applyTargetSchemas(MigrationContract contract, ConnectionFactory connectionFactory) {
        log.info("Fase de Liquibase: Iniciando despliegue de esquemas DDL en Destino para tablas activas.");

        if (contract == null || contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new InvalidChangelogException("No hay tablas activas sobre las que aplicar Liquibase.");
        }

        String changelogsDirEnv = System.getenv("CHANGELOGS_DIR");
        if (changelogsDirEnv == null || changelogsDirEnv.trim().isEmpty()) {
            changelogsDirEnv = "changelogs/tables";
        }
        Path changelogsDir = Paths.get(changelogsDirEnv);
        Map<String, Path> changelogPathsByTable = resolveChangelogPaths(contract, changelogsDir);

        try (Connection targetConn = connectionFactory.getConnection(contract.getDatabase().getTargetConnection());
             DirectoryResourceAccessor resourceAccessor = new DirectoryResourceAccessor(changelogsDir.toAbsolutePath())) {

            DatabaseMetaData targetMeta = targetConn.getMetaData();
            Database database = null;

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String tableId = entry.getKey();
                TableMigration tableMigration = entry.getValue();
                String targetSchema = tableMigration.getTarget().getSchema();
                String targetTable = tableMigration.getTarget().getTable();
                Path changelogPath = changelogPathsByTable.get(tableId);

                if (tableExists(targetMeta, targetSchema, targetTable)) {
                    log.warn("La tabla destino '{}.{}' ya existe. No se ejecuta su createTable de Liquibase; se validara su estructura despues.",
                            targetSchema,
                            targetTable);
                    continue;
                }

                log.info("Ejecutando Liquibase -> Desplegando estructura para '{}.{}' usando {}",
                        targetSchema,
                        targetTable,
                        changelogPath.getFileName());
                try {
                    if (database == null) {
                        database = DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(targetConn));
                    }
                    Database databaseForUpdate = database;
                    Map<String, Object> scopeAttrs = Map.of(
                            Scope.Attr.resourceAccessor.name(), resourceAccessor);
                    Scope.child(scopeAttrs, () -> {
                        new CommandScope("update")
                                .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, databaseForUpdate)
                                .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG,
                                        changelogPath.getFileName().toString())
                                .execute();
                    });
                } catch (Exception e) {
                    throw new InvalidChangelogException("Error al ejecutar Liquibase para el changelog "
                            + changelogPath.getFileName() + " en la base de datos destino.", e);
                }
            }
        } catch (InvalidChangelogException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Fallo critico durante el despliegue de Liquibase en destino", e);
        }
        log.info("Despliegue estructural de Liquibase completado. Las tablas destino activas han sido creadas o detectadas como existentes.");
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection getConnection(ConnectionConfig config) throws SQLException;
    }

    private static Map<String, Path> resolveChangelogPaths(MigrationContract contract, Path changelogsDir) {
        Map<String, Path> changelogPathsByTable = new LinkedHashMap<>();
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String tableId = entry.getKey();
            TableMigration tableMigration = entry.getValue();

            if (tableMigration == null || tableMigration.getTarget() == null) {
                throw new InvalidChangelogException("La tabla activa '" + tableId + "' no tiene destino valido para aplicar Liquibase.");
            }

            String targetSchema = tableMigration.getTarget().getSchema();
            String targetTable = tableMigration.getTarget().getTable();
            if (targetSchema == null || targetSchema.trim().isEmpty() || targetTable == null || targetTable.trim().isEmpty()) {
                throw new InvalidChangelogException("La tabla activa '" + tableId + "' debe definir target.schema y target.table para aplicar Liquibase.");
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

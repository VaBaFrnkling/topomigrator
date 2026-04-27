package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
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
import java.util.Map;

/**
 * Ejecutor que conecta a la Base de Datos Destino y aplica manualmente
 * los ficheros de definición de tablas Liquibase expuestos por el usuario.
 *
 * Convención obligatoria: changelogs/tables/<schema>.<table>.yaml
 */
public class LiquibaseSchemaExecutor {
    private static final Logger log = LoggerFactory.getLogger(LiquibaseSchemaExecutor.class);

    /**
     * Despliega estructuralmente (DDL) los cambios de Liquibase en la Base de Datos
     * destino para las tablas activas del contrato ya filtrado.
     */
    public static void applyTargetSchemas(MigrationContract contract) {
        log.info("Fase de Liquibase: Iniciando despliegue de esquemas DDL en Destino para tablas activas.");

        if (contract == null || contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new InvalidChangelogException("No hay tablas activas sobre las que aplicar Liquibase.");
        }

        String changelogsDirEnv = System.getenv("CHANGELOGS_DIR");
        if (changelogsDirEnv == null || changelogsDirEnv.trim().isEmpty()) {
            changelogsDirEnv = "changelogs/tables";
        }
        Path changelogsDir = Paths.get(changelogsDirEnv);

        try (Connection targetConn = DatabaseConnectionManager.getConnection(contract.getDatabase().getTargetConnection());
             DirectoryResourceAccessor resourceAccessor = new DirectoryResourceAccessor(changelogsDir.toAbsolutePath())) {

            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(targetConn));

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String tableId = entry.getKey();
                TableMigration tableMigration = entry.getValue();

                if (tableMigration == null || tableMigration.getTarget() == null) {
                    throw new InvalidChangelogException("La tabla activa '" + tableId + "' no tiene destino válido para aplicar Liquibase.");
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

                log.info("Ejecutando Liquibase -> Desplegando estructura para '{}.{}' usando {}",
                        targetSchema,
                        targetTable,
                        changelogPath.getFileName());
                try {
                    Map<String, Object> scopeAttrs = Map.of(
                            Scope.Attr.resourceAccessor.name(), resourceAccessor);
                    Scope.child(scopeAttrs, () -> {
                        new CommandScope("update")
                                .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
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
            throw new RuntimeException("Fallo crítico durante el despliegue de Liquibase en destino", e);
        }
        log.info("Despliegue estructural de Liquibase completado. Las tablas destino activas han sido instanciadas.");
    }

    private static Path getChangelogPath(Path directory, String targetSchema, String targetTable) {
        Path exactMatch = directory.resolve(targetSchema + "." + targetTable + ".yaml");
        if (Files.exists(exactMatch) && Files.isRegularFile(exactMatch)) {
            return exactMatch;
        }
        return null;
    }
}

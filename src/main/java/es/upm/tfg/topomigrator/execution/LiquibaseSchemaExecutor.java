package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Map;

/**
 * Ejecutor que conecta a la Base de Datos Destino y aplica manualmente
 * los ficheros de definición de tablas Liquibase expuestos por el usuario.
 */
public class LiquibaseSchemaExecutor {
    private static final Logger log = LoggerFactory.getLogger(LiquibaseSchemaExecutor.class);

    /**
     * Despliega estructuralmente (DDL) los cambios de Liquibase en la Base de Datos destino.
     */
    public static void applyTargetSchemas(MigrationContract contract) {
        log.info("Fase de Liquibase: Iniciando despliegue de esquemas DDL en Destino.");

        String changelogsDirEnv = System.getenv("CHANGELOGS_DIR");
        if (changelogsDirEnv == null || changelogsDirEnv.trim().isEmpty()) {
            changelogsDirEnv = "changelogs/tables";
        }
        Path changelogsDir = Paths.get(changelogsDirEnv);

        try (Connection targetConn = DatabaseConnectionManager.getConnection(contract.getDatabase().getTargetConnection())) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(targetConn));

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String targetTable = entry.getValue().getTarget().getTable();
                Path changelogPath = getChangelogPath(changelogsDir, targetTable);
                
                if (changelogPath == null) {
                    throw new InvalidChangelogException("Changelog no encontrado para aplicar de la tabla: " + targetTable);
                }

                log.info("Ejecutando Liquibase -> Desplegando estructura para '{}' usando {}", targetTable, changelogPath.getFileName());
                try (DirectoryResourceAccessor resourceAccessor = new DirectoryResourceAccessor(new File(changelogsDir.toAbsolutePath().toString()))) {
                    Liquibase liquibase = new Liquibase(changelogPath.getFileName().toString(), resourceAccessor, database);
                    liquibase.update(new Contexts(), new LabelExpression());
                } catch (Exception e) {
                    throw new InvalidChangelogException("Error al ejecutar Liquibase para el changelog " + changelogPath.getFileName() + " en la base de datos destino.", e);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Fallo crítico durante el despliegue de Liquibase en destino", e);
        }
        log.info("Liquidación Estructural de Liquibase completada. Las tablas destino han sido instanciadas.");
    }

    private static Path getChangelogPath(Path directory, String targetTableName) {
        Path directMatch = directory.resolve(targetTableName + ".yaml");
        if (Files.exists(directMatch)) return directMatch;
        Path prefixedMatch = directory.resolve("changelog-" + targetTableName + ".yaml");
        if (Files.exists(prefixedMatch)) return prefixedMatch;
        return null;
    }
}

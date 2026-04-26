package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import es.upm.tfg.topomigrator.util.SchemaTableIdentifierUtils;
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
import java.util.Set;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Ejecutor que conecta a la Base de Datos Destino y aplica manualmente
 * los ficheros de definición de tablas Liquibase expuestos por el usuario.
 */
public class LiquibaseSchemaExecutor {
    private static final Logger log = LoggerFactory.getLogger(LiquibaseSchemaExecutor.class);

    /**
     * Despliega estructuralmente (DDL) los cambios de Liquibase en la Base de Datos
     * destino.
     */
    public static void applyTargetSchemas(MigrationContract contract) {
        log.info("Fase de Liquibase: Iniciando despliegue de esquemas DDL en Destino.");

        String changelogsDirEnv = System.getenv("CHANGELOGS_DIR");
        if (changelogsDirEnv == null || changelogsDirEnv.trim().isEmpty()) {
            changelogsDirEnv = "changelogs/tables";
        }
        Path changelogsDir = Paths.get(changelogsDirEnv);
        Set<String> ambiguousTargetTableNames = detectAmbiguousTargetTableNames(contract);

        try (Connection targetConn = DatabaseConnectionManager
                .getConnection(contract.getDatabase().getTargetConnection());
             DirectoryResourceAccessor resourceAccessor = new DirectoryResourceAccessor(
                     changelogsDir.toAbsolutePath())) {

            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(targetConn));

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                TableMigration migration = entry.getValue();
                String targetSchema = migration.getTarget().getSchema();
                String targetTable = migration.getTarget().getTable();
                String qualifiedTargetId = SchemaTableIdentifierUtils.toQualifiedIdentifier(targetSchema, targetTable);
                boolean allowLegacyFallback = !ambiguousTargetTableNames.contains(targetTable.trim().toLowerCase());
                Path changelogPath = getChangelogPath(changelogsDir, targetSchema, targetTable, allowLegacyFallback);

                if (changelogPath == null) {
                    String message = allowLegacyFallback
                            ? "Changelog no encontrado para aplicar de la tabla destino: " + qualifiedTargetId
                            : "Changelog no encontrado para aplicar de la tabla destino: " + qualifiedTargetId
                              + ". Al existir colisión de nombres entre esquemas, el archivo debe llamarse '" + qualifiedTargetId + ".yaml' o 'changelog-" + qualifiedTargetId + ".yaml'.";
                    throw new InvalidChangelogException(message);
                }

                log.info("Ejecutando Liquibase -> Desplegando estructura para '{}' usando {}",
                        qualifiedTargetId, changelogPath.getFileName());
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
                            + changelogPath.getFileName() + " de la tabla destino " + qualifiedTargetId + " en la base de datos destino.", e);
                }
            }
        } catch (InvalidChangelogException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Fallo crítico durante el despliegue de Liquibase en destino", e);
        }
        log.info("Despliegue estructural de Liquibase completado. Las tablas destino han sido instanciadas.");
    }

    private static Set<String> detectAmbiguousTargetTableNames(MigrationContract contract) {
        Map<String, Integer> countsBySimpleTable = new HashMap<>();

        for (TableMigration tableMigration : contract.getTables().values()) {
            if (tableMigration == null || tableMigration.getTarget() == null || tableMigration.getTarget().getTable() == null) {
                continue;
            }
            String simpleName = tableMigration.getTarget().getTable().trim().toLowerCase();
            countsBySimpleTable.merge(simpleName, 1, Integer::sum);
        }

        return countsBySimpleTable.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static Path getChangelogPath(Path directory, String targetSchema, String targetTableName, boolean allowLegacyFallback) {
        String qualifiedStem = SchemaTableIdentifierUtils.toQualifiedChangelogStem(targetSchema, targetTableName);

        Path directQualifiedMatch = findFileIgnoreCase(directory, qualifiedStem + ".yaml");
        if (directQualifiedMatch != null) {
            return directQualifiedMatch;
        }

        Path prefixedQualifiedMatch = findFileIgnoreCase(directory, "changelog-" + qualifiedStem + ".yaml");
        if (prefixedQualifiedMatch != null) {
            return prefixedQualifiedMatch;
        }

        if (allowLegacyFallback) {
            Path directLegacyMatch = findFileIgnoreCase(directory, targetTableName.trim() + ".yaml");
            if (directLegacyMatch != null) {
                return directLegacyMatch;
            }

            Path prefixedLegacyMatch = findFileIgnoreCase(directory, "changelog-" + targetTableName.trim() + ".yaml");
            if (prefixedLegacyMatch != null) {
                return prefixedLegacyMatch;
            }
        }

        return null;
    }

    private static Path findFileIgnoreCase(Path directory, String expectedFileName) {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(expectedFileName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}

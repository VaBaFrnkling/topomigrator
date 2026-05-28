package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Validates that every active target table has a Liquibase changelog with the
 * required file name and a createTable entry for that target table.
 */
public class TargetChangelogValidator {

    private static final Logger log = LoggerFactory.getLogger(TargetChangelogValidator.class);

    public static void validate(MigrationContract contract) {
        validate(contract, resolveChangelogsDir());
    }

    static void validate(MigrationContract contract, Path changelogsDir) {
        log.info("Iniciando validacion de changelogs Liquibase para tablas destino activas.");

        if (contract == null || contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new InvalidChangelogException("El contrato proporcionado es nulo o no contiene tablas activas.");
        }
        if (changelogsDir == null) {
            throw new InvalidChangelogException("El directorio de changelogs destino no puede ser nulo.");
        }

        validateChangelogsDirectory(changelogsDir);

        Yaml yamlParser = new Yaml();
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            validateTableChangelog(entry.getKey(), entry.getValue(), changelogsDir, yamlParser);
        }

        log.info("Validacion de changelogs completada correctamente.");
    }

    private static Path resolveChangelogsDir() {
        return Paths.get("changelogs", "tables");
    }

    private static void validateChangelogsDirectory(Path changelogsDir) {
        if (!Files.exists(changelogsDir) || !Files.isDirectory(changelogsDir)) {
            throw new InvalidChangelogException("No se ha encontrado el directorio de changelogs destino: "
                    + changelogsDir.toAbsolutePath());
        }

        try (Stream<Path> files = Files.list(changelogsDir)) {
            boolean hasYamlFiles = files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".yaml"));
            if (!hasYamlFiles) {
                throw new InvalidChangelogException("El directorio de changelogs esta vacio o no contiene archivos .yaml: "
                        + changelogsDir.toAbsolutePath());
            }
        } catch (InvalidChangelogException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidChangelogException("No se pudo inspeccionar el directorio de changelogs.", e);
        }
    }

    private static void validateTableChangelog(String tableId,
                                               TableMigration tableDef,
                                               Path changelogsDir,
                                               Yaml yamlParser) {
        if (tableDef == null || tableDef.getTarget() == null) {
            throw new InvalidChangelogException("La tabla activa '" + tableId + "' no tiene destino valido.");
        }

        String targetSchema = tableDef.getTarget().getSchema();
        String targetTable = tableDef.getTarget().getTable();
        if (targetSchema == null || targetSchema.trim().isEmpty()
                || targetTable == null || targetTable.trim().isEmpty()) {
            throw new InvalidChangelogException("La tabla activa '" + tableId
                    + "' debe definir target.schema y target.table para localizar su changelog.");
        }

        Path changelogPath = findChangelogForTarget(changelogsDir, targetSchema, targetTable);
        if (changelogPath == null) {
            throw new InvalidChangelogException(String.format(
                    "No se ha encontrado el archivo Liquibase para la tabla destino activa '%s.%s'. Esperado: %s en %s",
                    targetSchema,
                    targetTable,
                    buildExpectedFileName(targetSchema, targetTable),
                    changelogsDir.toAbsolutePath()));
        }

        log.info("Validando sintaxis Liquibase del archivo: {}", changelogPath.getFileName());
        try (InputStream is = new FileInputStream(changelogPath.toFile())) {
            Object parsedYaml = yamlParser.load(is);
            if (!(parsedYaml instanceof Map)) {
                throw new InvalidChangelogException("El archivo " + changelogPath.getFileName()
                        + " no contiene una estructura YAML valida para Liquibase.");
            }

            Map<?, ?> rootNode = (Map<?, ?>) parsedYaml;
            if (!rootNode.containsKey("databaseChangeLog")) {
                throw new InvalidChangelogException("El archivo " + changelogPath.getFileName()
                        + " no contiene la marca raiz 'databaseChangeLog' obligatoria en Liquibase.");
            }

            validateChangelogTargetsTable(rootNode, changelogPath, targetSchema, targetTable);
        } catch (InvalidChangelogException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidChangelogException("Error al parsear el archivo Liquibase "
                    + changelogPath.getFileName() + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateChangelogTargetsTable(Map<?, ?> rootNode,
                                                      Path changelogPath,
                                                      String targetSchema,
                                                      String targetTable) {
        Object databaseChangeLog = rootNode.get("databaseChangeLog");
        if (!(databaseChangeLog instanceof List)) {
            throw new InvalidChangelogException("El archivo " + changelogPath.getFileName()
                    + " debe contener 'databaseChangeLog' como lista de cambios Liquibase.");
        }

        List<String> createdTables = new ArrayList<>();
        for (Object item : (List<?>) databaseChangeLog) {
            if (!(item instanceof Map)) {
                continue;
            }
            Object changeSetObject = ((Map<?, ?>) item).get("changeSet");
            if (!(changeSetObject instanceof Map)) {
                continue;
            }
            Object changesObject = ((Map<?, ?>) changeSetObject).get("changes");
            if (!(changesObject instanceof List)) {
                continue;
            }
            for (Object changeObject : (List<?>) changesObject) {
                if (!(changeObject instanceof Map)) {
                    continue;
                }
                Object createTableObject = ((Map<?, ?>) changeObject).get("createTable");
                if (!(createTableObject instanceof Map)) {
                    continue;
                }
                Map<?, ?> createTable = (Map<?, ?>) createTableObject;
                String schemaName = asString(createTable.get("schemaName"));
                String tableName = asString(createTable.get("tableName"));
                if (tableName != null) {
                    createdTables.add((schemaName != null ? schemaName + "." : "") + tableName);
                }
                if (equalsIgnoreCase(schemaName, targetSchema) && equalsIgnoreCase(tableName, targetTable)) {
                    return;
                }
            }
        }

        throw new InvalidChangelogException("El archivo " + changelogPath.getFileName()
                + " existe y es YAML Liquibase valido, pero no contiene un createTable para la tabla destino esperada '"
                + targetSchema + "." + targetTable + "'. Tablas createTable encontradas: " + createdTables);
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    static Path findChangelogForTarget(Path directory, String targetSchema, String targetTable) {
        Path exactMatch = directory.resolve(buildExpectedFileName(targetSchema, targetTable));
        if (Files.exists(exactMatch) && Files.isRegularFile(exactMatch)) {
            return exactMatch;
        }
        return null;
    }

    static String buildExpectedFileName(String targetSchema, String targetTable) {
        return targetSchema + "." + targetTable + ".yaml";
    }
}

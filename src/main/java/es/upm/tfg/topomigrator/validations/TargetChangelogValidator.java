package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.util.SchemaTableIdentifierUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Validador encargado de asegurar que los ficheros Liquibase (changelogs)
 * proporcionados por el usuario para las tablas de destino existen y
 * tienen un formato YAML válido antes de comenzar la migración.
 */
public class TargetChangelogValidator {

    private static final Logger log = LoggerFactory.getLogger(TargetChangelogValidator.class);

    /**
     * Valida que para cada tabla destino declarada en el contrato, exista
     * un archivo changelog válido en el directorio correspondiente.
     *
     * @param contract El contrato de migración cargado.
     */
    public static void validate(MigrationContract contract) {
        log.info("Iniciando validación de Changelogs de Liquibase para las tablas destino.");

        if (contract == null || contract.getTables() == null) {
            throw new InvalidChangelogException("El contrato proporcionado es nulo o no contiene tablas.");
        }

        String changelogsDirEnv = System.getenv("CHANGELOGS_DIR");
        if (changelogsDirEnv == null || changelogsDirEnv.trim().isEmpty()) {
            changelogsDirEnv = "changelogs/tables";
        }

        Path changelogsDir = Paths.get(changelogsDirEnv);

        if (!Files.exists(changelogsDir) || !Files.isDirectory(changelogsDir)) {
            throw new InvalidChangelogException("No se ha encontrado el directorio de changelogs destino: " + changelogsDir.toAbsolutePath());
        }

        Set<String> ambiguousTargetTableNames = detectAmbiguousTargetTableNames(contract);
        Yaml yamlParser = new Yaml();

        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            TableMigration tableDef = entry.getValue();

            if (tableDef.getTarget() == null || tableDef.getTarget().getTable() == null) {
                continue;
            }

            String targetSchema = tableDef.getTarget().getSchema();
            String targetTable = tableDef.getTarget().getTable();
            String qualifiedTargetId = SchemaTableIdentifierUtils.toQualifiedIdentifier(targetSchema, targetTable);
            boolean allowLegacyFallback = !ambiguousTargetTableNames.contains(targetTable.trim().toLowerCase());

            Path changelogPath = findChangelogForTable(changelogsDir, targetSchema, targetTable, allowLegacyFallback);

            if (changelogPath == null) {
                String message = allowLegacyFallback
                        ? String.format("No se ha encontrado un archivo Liquibase para la tabla destino '%s' (Esperado preferentemente: %s.yaml o changelog-%s.yaml en %s; como compatibilidad, también se acepta %s.yaml)",
                            qualifiedTargetId, qualifiedTargetId, qualifiedTargetId, changelogsDir.toAbsolutePath(), targetTable)
                        : String.format("No se ha encontrado un archivo Liquibase para la tabla destino '%s'. Debe existir un changelog con identificador completo de esquema: %s.yaml o changelog-%s.yaml en %s",
                            qualifiedTargetId, qualifiedTargetId, qualifiedTargetId, changelogsDir.toAbsolutePath());
                throw new InvalidChangelogException(message);
            }

            log.info("Validando sintaxis Liquibase del archivo '{}' para la tabla destino '{}'", changelogPath.getFileName(), qualifiedTargetId);

            try (InputStream is = new FileInputStream(changelogPath.toFile())) {
                Object parsedYaml = yamlParser.load(is);

                if (!(parsedYaml instanceof Map)) {
                    throw new InvalidChangelogException("El archivo " + changelogPath.getFileName() + " no contiene una estructura YAML válida para Liquibase.");
                }

                Map<?, ?> rootNode = (Map<?, ?>) parsedYaml;
                if (!rootNode.containsKey("databaseChangeLog")) {
                    throw new InvalidChangelogException(
                            "El archivo " + changelogPath.getFileName() + " no contiene la marca raíz 'databaseChangeLog' obligatoria en Liquibase."
                    );
                }
            } catch (Exception e) {
                if (e instanceof InvalidChangelogException) {
                    throw (InvalidChangelogException) e;
                }
                throw new InvalidChangelogException("Error al parsear el archivo Liquibase " + changelogPath.getFileName() + ": " + e.getMessage(), e);
            }
        }

        log.info("Validación de Changelogs completada con éxito. Todos los archivos requeridos están presentes y son YAML válidos.");
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

    /**
     * Busca un archivo changelog correspondiente a una tabla destino específica,
     * priorizando la convención segura schema.table.yaml / changelog-schema.table.yaml.
     * Solo admite el fallback legado por nombre simple cuando no existe ambigüedad.
     */
    private static Path findChangelogForTable(Path directory, String targetSchema, String targetTableName, boolean allowLegacyFallback) {
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

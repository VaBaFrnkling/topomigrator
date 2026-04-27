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
import java.util.Map;
import java.util.stream.Stream;

/**
 * Validador encargado de asegurar que los ficheros Liquibase (changelogs)
 * proporcionados por el usuario para las tablas de destino existen y
 * tienen un formato YAML válido antes de comenzar la migración.
 *
 * Convención obligatoria: changelogs/tables/<schema>.<table>.yaml
 */
public class TargetChangelogValidator {

    private static final Logger log = LoggerFactory.getLogger(TargetChangelogValidator.class);

    /**
     * Valida que para cada tabla destino activa declarada en el contrato, exista
     * un archivo changelog válido en el directorio correspondiente.
     *
     * @param contract El contrato de migración cargado y ya filtrado por tablas activas.
     */
    public static void validate(MigrationContract contract) {
        log.info("Iniciando validación de Changelogs de Liquibase para las tablas destino activas.");

        if (contract == null || contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new InvalidChangelogException("El contrato proporcionado es nulo o no contiene tablas activas.");
        }

        String changelogsDirEnv = System.getenv("CHANGELOGS_DIR");
        if (changelogsDirEnv == null || changelogsDirEnv.trim().isEmpty()) {
            changelogsDirEnv = "changelogs/tables";
        }

        Path changelogsDir = Paths.get(changelogsDirEnv);

        if (!Files.exists(changelogsDir) || !Files.isDirectory(changelogsDir)) {
            throw new InvalidChangelogException("No se ha encontrado el directorio de changelogs destino: " + changelogsDir.toAbsolutePath());
        }

        try (Stream<Path> files = Files.list(changelogsDir)) {
            boolean hasYamlFiles = files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".yaml"));
            if (!hasYamlFiles) {
                throw new InvalidChangelogException("El directorio de changelogs está vacío o no contiene archivos .yaml: "
                        + changelogsDir.toAbsolutePath());
            }
        } catch (InvalidChangelogException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidChangelogException("No se pudo inspeccionar el directorio de changelogs.", e);
        }

        Yaml yamlParser = new Yaml();

        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String tableId = entry.getKey();
            TableMigration tableDef = entry.getValue();

            if (tableDef == null || tableDef.getTarget() == null) {
                throw new InvalidChangelogException("La tabla activa '" + tableId + "' no tiene destino válido.");
            }

            String targetSchema = tableDef.getTarget().getSchema();
            String targetTable = tableDef.getTarget().getTable();
            if (targetSchema == null || targetSchema.trim().isEmpty() || targetTable == null || targetTable.trim().isEmpty()) {
                throw new InvalidChangelogException("La tabla activa '" + tableId + "' debe definir target.schema y target.table para localizar su changelog.");
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
                            + " no contiene una estructura YAML válida para Liquibase.");
                }

                Map<?, ?> rootNode = (Map<?, ?>) parsedYaml;
                if (!rootNode.containsKey("databaseChangeLog")) {
                    throw new InvalidChangelogException(
                            "El archivo " + changelogPath.getFileName() + " no contiene la marca raíz 'databaseChangeLog' obligatoria en Liquibase.");
                }
            } catch (Exception e) {
                if (e instanceof InvalidChangelogException) {
                    throw (InvalidChangelogException) e;
                }
                throw new InvalidChangelogException("Error al parsear el archivo Liquibase " + changelogPath.getFileName()
                        + ": " + e.getMessage(), e);
            }
        }

        log.info("Validación de Changelogs completada con éxito. Todos los archivos requeridos están presentes y son YAML válidos.");
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

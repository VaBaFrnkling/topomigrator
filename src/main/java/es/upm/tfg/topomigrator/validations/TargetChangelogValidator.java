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
import java.util.List;

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

        // Obtener la ruta del directorio de changelogs desde entorno o por defecto
        String changelogsDirEnv = System.getenv("CHANGELOGS_DIR");
        if (changelogsDirEnv == null || changelogsDirEnv.trim().isEmpty()) {
            changelogsDirEnv = "changelogs/tables";
        }
        
        Path changelogsDir = Paths.get(changelogsDirEnv);

        if (!Files.exists(changelogsDir) || !Files.isDirectory(changelogsDir)) {
            throw new InvalidChangelogException("No se ha encontrado el directorio de changelogs destino: " + changelogsDir.toAbsolutePath());
        }

        Yaml yamlParser = new Yaml();

        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String tableId = entry.getKey();
            TableMigration tableDef = entry.getValue();
            
            if (tableDef.getTarget() == null || tableDef.getTarget().getTable() == null) {
                continue; // Validación cubierta por ContractValidator
            }

            String targetTable = tableDef.getTarget().getTable();
            Path changelogPath = findChangelogForTable(changelogsDir, targetTable);

            if (changelogPath == null) {
                throw new InvalidChangelogException(
                    String.format("No se ha encontrado un archivo Liquibase para la tabla destino '%s' (Esperado: %s.yaml o changelog-%s.yaml en %s)", 
                        targetTable, targetTable, targetTable, changelogsDir.toAbsolutePath())
                );
            }

            log.info("Validando sintaxis Liquibase del archivo: {}", changelogPath.getFileName());

            // Validar que el archivo se puede parsear como YAML y tiene la raíz correcta
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
                
                // Extra validation can be added here if needed (e.g. checking if it actually creates the targetTable)
                
            } catch (Exception e) {
                if (e instanceof InvalidChangelogException) {
                    throw (InvalidChangelogException) e;
                }
                throw new InvalidChangelogException("Error al parsear el archivo Liquibase " + changelogPath.getFileName() + ": " + e.getMessage(), e);
            }
        }
        
        log.info("Validación de Changelogs completada con éxito. Todos los archivos requeridos están presentes y son YAML válidos.");
    }

    /**
     * Busca un archivo changelog correspondiente a una tabla destino específica,
     * admitiendo la convención "tabla.yaml" o "changelog-tabla.yaml".
     */
    private static Path findChangelogForTable(Path directory, String targetTableName) {
        Path directMatch = directory.resolve(targetTableName + ".yaml");
        if (Files.exists(directMatch) && Files.isRegularFile(directMatch)) {
            return directMatch;
        }

        Path prefixedMatch = directory.resolve("changelog-" + targetTableName + ".yaml");
        if (Files.exists(prefixedMatch) && Files.isRegularFile(prefixedMatch)) {
            return prefixedMatch;
        }

        return null;
    }
}

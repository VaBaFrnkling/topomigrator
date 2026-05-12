package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.TableMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/**
 * Clase para centralizar las validaciones de las reglas, formato y el 
 * orden estructural del fichero contract.yaml tras su conversión a objeto.
 */
public class ContractValidator {
    
    private static final Logger log = LoggerFactory.getLogger(ContractValidator.class);

    /**
     * Punto de entrada principal para la validación de un contrato de migración.
     * Invoca secuencialmente las validaciones de orden de secciones, información
     * de la migración y la configuración detallada de las tablas.
     *
     * @param contract     El objeto MigrationContract parseado a partir del YAML.
     * @param contractPath La ruta física del archivo contract.yaml analizado.
     * @throws InvalidContractException Si alguna de las reglas de validación estricta falla.
     */
    public static void validate(MigrationContract contract, Path contractPath) {
        if (contract == null) {
            throw new InvalidContractException("El contrato proporcionado es nulo.");
        }

        log.info("Iniciando validación del contrato de migración.");

        validateSectionOrder(contractPath);
        validateMigrationSection(contract);
        validateTablesSection(contract);
        
        log.info("Validación del contrato completada con éxito.");
    }

    /**
     * Comprueba el orden físico de las secciones raíz dentro del archivo original.
     * Para garantizar la consistencia, exige que 'migration' 
     * debe ir obligatoriamente antes de 'tables'.
     *
     * @param originalFile La ruta del fichero YAML para ser leído en texto plano.
     */
    private static void validateSectionOrder(Path originalFile) {
        try {
            List<String> lines = Files.readAllLines(originalFile);
            int migrationIdx = -1, tablesIdx = -1;
            
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("migration:")) migrationIdx = i;
                else if (line.startsWith("tables:")) tablesIdx = i;
            }

            if (migrationIdx != -1 && tablesIdx != -1 && migrationIdx > tablesIdx) {
                throw new InvalidContractException("El orden del contrato es incorrecto: 'migration' debe ir antes de 'tables'.");
            }

        } catch (IOException e) {
            log.warn("No se pudo leer el archivo original para validar su orden estricto.", e);
        }
    }

    /**
     * Verifica que la sección obligatoria 'migration' exista y cuente con
     * sus propiedades fundamentales ('name' y 'version') debidamente rellenadas.
     *
     * @param contract El contrato de migración bajo evaluación.
     */
    private static void validateMigrationSection(MigrationContract contract) {
        if (contract.getMigration() == null) {
            throw new InvalidContractException("Falta la sección obligatoria 'migration' en el contrato.");
        }
        
        if (contract.getMigration().getName() == null || contract.getMigration().getName().trim().isEmpty()) {
            throw new InvalidContractException("El campo 'migration.name' es obligatorio y no puede estar vacío.");
        }
        
        if (contract.getMigration().getVersion() == null || contract.getMigration().getVersion().trim().isEmpty()) {
            throw new InvalidContractException("El campo 'migration.version' es obligatorio.");
        }
    }


    /**
     * Recorre cada una de las tablas definidas asegurando la integridad
     * de sus identificadores, referencias de esquemas origen/destino ('source' y 'target')
     * y la coherencia de sus tipos de migración ('incremental', 'full').
     * Por último, delega en subvalidaciones en caso de encontrarse configuraciones extra.
     *
     * @param contract El contrato global cuyo mapa de tablas será analizado.
     */
    private static void validateTablesSection(MigrationContract contract) {
        if (contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new InvalidContractException("El contrato debe tener al menos una tabla definida en la sección 'tables'.");
        }
        
        int activeTables = 0;
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String tableName = entry.getKey();
            TableMigration tableDef = entry.getValue();

            if (tableName == null || tableName.trim().isEmpty()) {
                throw new InvalidContractException("Se ha encontrado un bloque de tabla sin identificador en el contrato.");
            }

            if (tableDef == null) {
                throw new InvalidContractException("La configuración para la tabla '" + tableName + "' es nula.");
            }

            if (!tableDef.isEnabled()) {
                continue;
            }
            activeTables++;

            // Validar que se ha especificado correctamente el origen y el destino
            if (tableDef.getSource() == null || tableDef.getSource().getTable() == null || tableDef.getSource().getTable().trim().isEmpty()) {
                throw new InvalidContractException("La tabla '" + tableName + "' carece de especificación válida en el origen 'source.table'.");
            }
            
            if (tableDef.getTarget() == null || tableDef.getTarget().getTable() == null || tableDef.getTarget().getTable().trim().isEmpty()) {
                throw new InvalidContractException("La tabla '" + tableName + "' carece de especificación válida en el destino 'target.table'.");
            }

            // Validar que el tipo de migración está presente y concuerda con sus sub-configuraciones
            String migType = tableDef.getMigrationType();
            if (migType == null || migType.trim().isEmpty()) {
                throw new InvalidContractException("El tipo de migración 'migrationType' no está definido para la estructura '" + tableName + "'.");
            }
            
            String normalizedMigType = migType.trim().toLowerCase();
            if (!normalizedMigType.equals("full") && !normalizedMigType.equals("incremental")) {
                throw new InvalidContractException("Valor no permitido en 'migrationType' para la tabla '" + tableName + "'. Valores permitidos: 'full', 'incremental'.");
            }
            
            if ("incremental".equals(normalizedMigType)) {
                IncrementalConfig incrementalConfig = tableDef.getIncrementalConfig();
                if (incrementalConfig == null) {
                    throw new InvalidContractException("El tipo de migración es 'incremental' pero falta el bloque 'incrementalConfig' en '" + tableName + "'.");
                }
                if (incrementalConfig.getColumn() == null
                        || incrementalConfig.getColumn().trim().isEmpty()) {
                    throw new InvalidContractException("La migración incremental de '" + tableName + "' requiere incrementalConfig.column.");
                }
                if (incrementalConfig.getStartValue() == null
                        || incrementalConfig.getStartValue().trim().isEmpty()) {
                    throw new InvalidContractException("La migración incremental de '" + tableName + "' requiere incrementalConfig.startValue.");
                }
                if (incrementalConfig.getBatchSize() != null
                        && incrementalConfig.getBatchSize() <= 0) {
                    throw new InvalidContractException("La migración incremental de '" + tableName + "' requiere incrementalConfig.batchSize mayor que 0.");
                }

                validateIncrementalLoadStrategy(tableName, incrementalConfig);
            }
        }

        if (activeTables == 0) {
            throw new InvalidContractException("No hay tablas activas en el contrato de migracion.");
        }
    }

    private static void validateIncrementalLoadStrategy(String tableName, IncrementalConfig incrementalConfig) {
        String loadStrategy = incrementalConfig.getLoadStrategy();
        if (loadStrategy != null && !loadStrategy.trim().isEmpty()) {
            String normalizedLoadStrategy = loadStrategy.trim().toLowerCase();
            if (!normalizedLoadStrategy.equals("upsert")
                    && !normalizedLoadStrategy.equals("append")
                    && !normalizedLoadStrategy.equals("append_only")) {
                throw new InvalidContractException("Valor no permitido en incrementalConfig.loadStrategy para la tabla '"
                        + tableName + "'. Valores permitidos: 'upsert', 'append', 'append_only'.");
            }
        }

        if (incrementalConfig.getIdempotencyKeyColumns() != null) {
            if (incrementalConfig.getIdempotencyKeyColumns().isEmpty()) {
                throw new InvalidContractException("La tabla '" + tableName
                        + "' requiere al menos una columna en incrementalConfig.idempotencyKeyColumns si el bloque se declara.");
            }
            for (String column : incrementalConfig.getIdempotencyKeyColumns()) {
                if (column == null || column.trim().isEmpty()) {
                    throw new InvalidContractException("La tabla '" + tableName
                            + "' contiene una columna vacía en incrementalConfig.idempotencyKeyColumns.");
                }
            }
        }
    }
}

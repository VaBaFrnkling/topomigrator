package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TransformationConfig;
import es.upm.tfg.topomigrator.model.ValidationConfig;
import es.upm.tfg.topomigrator.model.ColumnTransformation;
import es.upm.tfg.topomigrator.model.ColumnValidation;
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

    private static void validateSectionOrder(Path originalFile) {
        try {
            List<String> lines = Files.readAllLines(originalFile);
            int migrationIdx = -1, liquibaseIdx = -1, tablesIdx = -1;
            
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("migration:")) migrationIdx = i;
                else if (line.startsWith("liquibase:")) liquibaseIdx = i;
                else if (line.startsWith("tables:")) tablesIdx = i;
            }

            if (migrationIdx != -1 && liquibaseIdx != -1 && migrationIdx > liquibaseIdx) {
                throw new InvalidContractException("El orden del contrato es incorrecto: 'migration' debe ir antes que 'liquibase'.");
            }
            if (migrationIdx != -1 && tablesIdx != -1 && migrationIdx > tablesIdx) {
                throw new InvalidContractException("El orden del contrato es incorrecto: 'migration' debe ir antes de 'tables'.");
            }
            if (liquibaseIdx != -1 && tablesIdx != -1 && liquibaseIdx > tablesIdx) {
                throw new InvalidContractException("El orden del contrato es incorrecto: 'liquibase' debe ir antes de 'tables'.");
            }

        } catch (IOException e) {
            log.warn("No se pudo leer el archivo original para validar su orden estricto.", e);
        }
    }

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

    private static void validateTablesSection(MigrationContract contract) {
        if (contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new InvalidContractException("El contrato debe tener al menos una tabla definida en la sección 'tables'.");
        }
        
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String tableName = entry.getKey();
            TableMigration tableDef = entry.getValue();

            if (tableName == null || tableName.trim().isEmpty()) {
                throw new InvalidContractException("Se ha encontrado un bloque de tabla sin identificador en el contrato.");
            }

            if (tableDef == null) {
                throw new InvalidContractException("La configuración para la tabla '" + tableName + "' es nula.");
            }

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
            
            if ("incremental".equalsIgnoreCase(migType.trim()) && tableDef.getIncrementalConfig() == null) {
                throw new InvalidContractException("El tipo de migración es 'incremental' pero falta el bloque 'incrementalConfig' en '" + tableName + "'.");
            }

            // Validar transformations
            validateTransformations(tableName, tableDef.getTransformations());

            // Validar validations
            validateValidations(tableName, tableDef.getValidations());
        }
    }

    private static void validateTransformations(String tableName, TransformationConfig transformations) {
        if (transformations != null) {
            if (transformations.getColumns() == null || transformations.getColumns().isEmpty()) {
                throw new InvalidContractException("La sección de transformaciones para la tabla '" + tableName + "' está presente pero no define ninguna columna.");
            }
            for (Map.Entry<String, ColumnTransformation> colEntry : transformations.getColumns().entrySet()) {
                String colName = colEntry.getKey();
                ColumnTransformation colDef = colEntry.getValue();
                if (colDef == null) {
                    throw new InvalidContractException("La columna '" + colName + "' en devoluciones de '" + tableName + "' es nula.");
                }
                
                // Asegurar de que exista al menos UNA regla de transformación
                if (colDef.getRename() == null && colDef.getType() == null && colDef.getTrim() == null 
                        && colDef.getNullDefault() == null && colDef.getCaseTransform() == null) {
                    throw new InvalidContractException("La columna '" + colName + "' de transformaciones en '" + tableName + "' no tiene dada de alta ninguna regla. Debe tener al menos algo para aplicar.");
                }

                // Check allowed values
                if (colDef.getCaseTransform() != null) {
                    String caseVal = colDef.getCaseTransform().trim().toLowerCase();
                    if (!caseVal.equals("uppercase") && !caseVal.equals("lowercase")) {
                        throw new InvalidContractException("Valor no permitido en transformaciones '" + colName + "' para 'caseTransform' ('case'). Solo se permite 'uppercase' o 'lowercase'.");
                    }
                }
            }
        }
    }

    private static void validateValidations(String tableName, ValidationConfig validations) {
        if (validations != null) {
            boolean hasGlobalCheck = (validations.getRowCountCheck() != null) || (validations.getForeignKeyChecks() != null);
            boolean hasColCheck = (validations.getColumns() != null && !validations.getColumns().isEmpty());
            
            if (!hasGlobalCheck && !hasColCheck) {
                throw new InvalidContractException("La sección de validaciones para la tabla '" + tableName + "' no define ni comprobaciones globales, ni columnas de verificación.");
            }

            if (validations.getColumns() != null) {
                for (Map.Entry<String, ColumnValidation> colEntry : validations.getColumns().entrySet()) {
                    String colName = colEntry.getKey();
                    ColumnValidation colDef = colEntry.getValue();
                    
                    if (colDef == null || (colDef.getNotNull() == null && colDef.getUnique() == null)) {
                        throw new InvalidContractException("La columna '" + colName + "' de validaciones en '" + tableName + "' no tiene reglas. Debe aplicar al menos algo (notNull, unique).");
                    }
                }
            }
        }
    }
}

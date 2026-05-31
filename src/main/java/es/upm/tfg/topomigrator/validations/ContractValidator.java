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
import java.util.regex.Pattern;

public class ContractValidator {
    
    private static final Logger log = LoggerFactory.getLogger(ContractValidator.class);
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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

            if (tableDef.getSource() == null || tableDef.getSource().getTable() == null || tableDef.getSource().getTable().trim().isEmpty()) {
                throw new InvalidContractException("La tabla '" + tableName + "' carece de especificación válida en el origen 'source.table'.");
            }
            
            if (tableDef.getTarget() == null || tableDef.getTarget().getTable() == null || tableDef.getTarget().getTable().trim().isEmpty()) {
                throw new InvalidContractException("La tabla '" + tableName + "' carece de especificación válida en el destino 'target.table'.");
            }
            validateOptionalSqlIdentifier(tableName, "source.schema", tableDef.getSource().getSchema());
            validateSqlIdentifier(tableName, "source.table", tableDef.getSource().getTable());
            validateOptionalSqlIdentifier(tableName, "target.schema", tableDef.getTarget().getSchema());
            validateSqlIdentifier(tableName, "target.table", tableDef.getTarget().getTable());

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
                validateSqlIdentifier(tableName, "incrementalConfig.column", incrementalConfig.getColumn());
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
                validateSqlIdentifier(tableName, "incrementalConfig.idempotencyKeyColumns", column);
            }
        }
    }

    private static void validateOptionalSqlIdentifier(String tableName, String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        validateSqlIdentifier(tableName, field, value);
    }

    private static void validateSqlIdentifier(String tableName, String field, String value) {
        String normalized = value != null ? value.trim() : "";
        if (!SQL_IDENTIFIER.matcher(normalized).matches()) {
            throw new InvalidContractException("La tabla '" + tableName + "' contiene un identificador SQL no valido en "
                    + field + ": '" + value + "'. Use solo letras, numeros y guion bajo, empezando por letra o guion bajo.");
        }
    }
}

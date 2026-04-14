package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.SchemaCompatibilityException;
import es.upm.tfg.topomigrator.model.ColumnTransformation;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Validador encargado de comprobar la compatibilidad de esquemas entre las tablas
 * de origen y destino ANTES de llevar a cabo la migración.
 */
public class SchemaCompatibilityValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaCompatibilityValidator.class);

    /**
     * Valida la compatibilidad de los esquemas origen y destino definidos en el contrato.
     * Conecta a ambas bases de datos y verifica que las tablas existan y las columnas de origen
     * tengan su correspondencia en destino (teniendo en cuenta transformaciones de renombrado).
     *
     * @param contract El contrato de migración con la configuración de BD y tablas.
     * @throws SchemaCompatibilityException Si las tablas no existen o los esquemas son incompatibles.
     */
    public static void validateCompatibility(MigrationContract contract) {
        log.info("Iniciando validación de compatibilidad de esquemas (origen vs destino).");

        if (contract == null || contract.getTables() == null) {
            throw new SchemaCompatibilityException("El contrato o las tablas están vacíos.");
        }

        try (Connection sourceConn = DatabaseConnectionManager.getConnection(contract.getDatabase().getSourceConnection());
             Connection targetConn = DatabaseConnectionManager.getConnection(contract.getDatabase().getTargetConnection())) {

            DatabaseMetaData sourceMeta = sourceConn.getMetaData();
            DatabaseMetaData targetMeta = targetConn.getMetaData();

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String tableNameId = entry.getKey();
                TableMigration tableDef = entry.getValue();

                log.info("Validando compatibilidad para la definición de tabla: {}", tableNameId);

                String sourceSchema = tableDef.getSource().getSchema();
                String sourceTable = tableDef.getSource().getTable();
                String targetSchema = tableDef.getTarget().getSchema();
                String targetTable = tableDef.getTarget().getTable();

                // Validar la existencia de la tabla origen y extraer sus columnas
                Map<String, String> sourceColumns = getColumns(sourceMeta, sourceSchema, sourceTable);
                if (sourceColumns.isEmpty()) {
                    throw new SchemaCompatibilityException(
                            String.format("La tabla origen '%s' no existe o no tiene columnas accesibles.", formatTableName(sourceSchema, sourceTable))
                    );
                }

                // Validar la existencia de la tabla destino y extraer sus columnas
                Map<String, String> targetColumns = getColumns(targetMeta, targetSchema, targetTable);
                if (targetColumns.isEmpty()) {
                    throw new SchemaCompatibilityException(
                            String.format("La tabla destino '%s' no existe o no tiene columnas accesibles.", formatTableName(targetSchema, targetTable))
                    );
                }

                // Validar mapeo de columnas
                validateColumnsMapping(tableNameId, tableDef, sourceColumns, targetColumns);
            }

        } catch (SQLException e) {
            throw new SchemaCompatibilityException("Error al conectar a las bases de datos para comprobar compatibilidad.", e);
        } catch (Exception e) {
            if (e instanceof SchemaCompatibilityException) {
                throw e; // Relanzar excepciones específicas
            }
            throw new SchemaCompatibilityException("Fallo inesperado al realizar la comprobación de compatibilidad de esquema.", e);
        }

        log.info("Validación de compatibilidad completada con éxito. Las tablas son compatibles.");
    }

    /**
     * Verifica que cada columna de origen existe en el destino, aplicando reglas de renombrado si aplican.
     */
    private static void validateColumnsMapping(String tableNameId, TableMigration tableDef, Map<String, String> sourceColumns, Map<String, String> targetColumns) {
        for (String sourceCol : sourceColumns.keySet()) {
            String expectedTargetCol = getExpectedTargetColumn(sourceCol, tableDef);

            if (!targetColumns.containsKey(expectedTargetCol.toLowerCase())) {
                throw new SchemaCompatibilityException(
                        String.format("La columna origen '%s' (mapeada a '%s') no existe en la tabla destino de la migración '%s'.", 
                        sourceCol, expectedTargetCol, tableNameId)
                );
            }
        }
    }

    /**
     * Determina el nombre esperado de una columna en el destino tras aplicar
     * posibles transformaciones de 'rename' configuradas en el contrato.
     */
    private static String getExpectedTargetColumn(String sourceCol, TableMigration tableDef) {
        if (tableDef.getTransformations() != null && tableDef.getTransformations().getColumns() != null) {
            for (Map.Entry<String, ColumnTransformation> entry : tableDef.getTransformations().getColumns().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(sourceCol)) {
                    if (entry.getValue() != null && entry.getValue().getRename() != null) {
                        return entry.getValue().getRename();
                    }
                }
            }
        }
        return sourceCol;
    }

    /**
     * Extrae las columnas de una tabla dada utilizando la metadata JDBC.
     * Soporta tablas en mayúsculas y minúsculas para abstraer detalles
     * de diferentes motores relacionales (Oracle vs Postgres, etc.).
     */
    private static Map<String, String> getColumns(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        String schemaPattern = (schema != null && !schema.trim().isEmpty()) ? schema : null;
        
        // 1. Intentar con el nombre tal cual
        try (ResultSet rs = metaData.getColumns(null, schemaPattern, table, null)) {
            while (rs.next()) {
                columns.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
            }
        }
        
        // 2. Intentar con UPPERCASE (típico en Oracle o H2 sin comillas)
        if (columns.isEmpty()) {
            String upperSchema = (schemaPattern != null) ? schemaPattern.toUpperCase() : null;
            try (ResultSet rs = metaData.getColumns(null, upperSchema, table.toUpperCase(), null)) {
                while (rs.next()) {
                    columns.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
                }
            }
        }
        
        // 3. Intentar con LOWERCASE (típico en PostgreSQL)
        if (columns.isEmpty()) {
            String lowerSchema = (schemaPattern != null) ? schemaPattern.toLowerCase() : null;
            try (ResultSet rs = metaData.getColumns(null, lowerSchema, table.toLowerCase(), null)) {
                while (rs.next()) {
                    columns.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
                }
            }
        }
        
        return columns;
    }

    private static String formatTableName(String schema, String table) {
        return (schema != null && !schema.trim().isEmpty() ? schema + "." : "") + table;
    }
}

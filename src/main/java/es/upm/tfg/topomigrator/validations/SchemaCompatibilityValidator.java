package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.SchemaCompatibilityException;
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
     * Valida ÚNICAMENTE la existencia y accesibilidad de las tablas de Origen.
     * Esto permite detectar fallos preventivos antes de desplegar nada en el Destino.
     *
     * @param contract El contrato de migración.
     */
    public static void validateSourceSchemas(MigrationContract contract) {
        log.info("Iniciando validación pre-migración: Comprobando Base de Datos Origen.");

        if (contract == null || contract.getTables() == null) {
            throw new SchemaCompatibilityException("El contrato o las tablas están vacíos.");
        }

        try (Connection sourceConn = DatabaseConnectionManager.getConnection(contract.getDatabase().getSourceConnection())) {
            DatabaseMetaData sourceMeta = sourceConn.getMetaData();

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String sourceSchema = entry.getValue().getSource().getSchema();
                String sourceTable = entry.getValue().getSource().getTable();

                Map<String, String> sourceColumns = getColumns(sourceMeta, sourceSchema, sourceTable);
                if (sourceColumns.isEmpty()) {
                    throw new SchemaCompatibilityException(
                            String.format("La tabla origen '%s' no existe o no se detectaron columnas accesibles.", formatTableName(sourceSchema, sourceTable))
                    );
                }
            }
        } catch (SQLException e) {
            throw new SchemaCompatibilityException("Error JDBC al conectar a la Base de Datos de Origen.", e);
        }
        log.info("Todas las tablas origen configuradas existen y son accesibles.");
    }

    /**
     * Valida que las tablas Destino contengan el mapeo de columnas exacto de las Origen.
     * Se debe invocar DESPUÉS de que Liquibase haya generado las estructuras destino.
     *
     * @param contract El contrato de migración.
     */
    public static void validateTargetAndMapping(MigrationContract contract) {
        log.info("Iniciando validación post-despliegue: Mapeo de esquemas Origen vs Destino.");

        try (Connection sourceConn = DatabaseConnectionManager.getConnection(contract.getDatabase().getSourceConnection());
             Connection targetConn = DatabaseConnectionManager.getConnection(contract.getDatabase().getTargetConnection())) {

            DatabaseMetaData sourceMeta = sourceConn.getMetaData();
            DatabaseMetaData targetMeta = targetConn.getMetaData();

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String tableNameId = entry.getKey();
                TableMigration tableDef = entry.getValue();

                String sourceSchema = tableDef.getSource().getSchema();
                String sourceTable = tableDef.getSource().getTable();
                String targetSchema = tableDef.getTarget().getSchema();
                String targetTable = tableDef.getTarget().getTable();

                Map<String, String> sourceColumns = getColumns(sourceMeta, sourceSchema, sourceTable);
                Map<String, String> targetColumns = getColumns(targetMeta, targetSchema, targetTable);

                if (targetColumns.isEmpty()) {
                    throw new SchemaCompatibilityException(
                            String.format("La tabla destino '%s' no ha sido creada por Liquibase o no es accesible.", formatTableName(targetSchema, targetTable))
                    );
                }

                // Validar mapeo directo de columnas (1:1, sin transformaciones)
                validateColumnsMapping(tableNameId, sourceColumns, targetColumns);
            }

        } catch (SQLException e) {
            throw new SchemaCompatibilityException("Error al conectar a las bases de datos para comprobar mapeo.", e);
        }
        log.info("Validación de compatibilidad de esquemas (Origen vs Destino) completada con éxito.");
    }

    /**
     * Verifica que cada columna de origen existe en el destino con mapeo directo (1:1).
     */
    private static void validateColumnsMapping(String tableNameId, Map<String, String> sourceColumns, Map<String, String> targetColumns) {
        for (String sourceCol : sourceColumns.keySet()) {
            if (!targetColumns.containsKey(sourceCol.toLowerCase())) {
                throw new SchemaCompatibilityException(
                        String.format("La columna origen '%s' no existe en la tabla destino '%s' tras generar su esquema.", 
                        sourceCol, tableNameId)
                );
            }
        }
    }

    /**
     * Extrae las columnas de una tabla dada utilizando la metadata JDBC.
     */
    private static Map<String, String> getColumns(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        String schemaPattern = (schema != null && !schema.trim().isEmpty()) ? schema : null;
        
        try (ResultSet rs = metaData.getColumns(null, schemaPattern, table, null)) {
            while (rs.next()) {
                columns.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
            }
        }
        
        if (columns.isEmpty()) {
            String upperSchema = (schemaPattern != null) ? schemaPattern.toUpperCase() : null;
            try (ResultSet rs = metaData.getColumns(null, upperSchema, table.toUpperCase(), null)) {
                while (rs.next()) {
                    columns.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
                }
            }
        }
        
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

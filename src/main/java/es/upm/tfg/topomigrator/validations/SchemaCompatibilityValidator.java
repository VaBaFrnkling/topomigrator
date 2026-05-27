package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.SchemaCompatibilityException;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validador encargado de comprobar la compatibilidad estructural entre las tablas
 * de origen y destino antes de ejecutar el flujo de datos.
 */
public class SchemaCompatibilityValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaCompatibilityValidator.class);

    /**
     * Valida la existencia y accesibilidad de las tablas de origen.
     */
    public static void validateSourceSchemas(MigrationContract contract) {
        validateSourceSchemas(contract, DatabaseConnectionManager::getConnection);
    }

    static void validateSourceSchemas(MigrationContract contract, ConnectionFactory connectionFactory) {
        log.info("Iniciando validacion pre-migracion: comprobando Base de Datos Origen.");

        ConnectionConfig sourceConnection = requireSourceConnection(contract);
        validateTables(contract);

        try (Connection sourceConn = connectionFactory.getConnection(sourceConnection)) {
            DatabaseMetaData sourceMeta = sourceConn.getMetaData();

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                TableMigration tableDef = entry.getValue();
                validateSourceTableReference(entry.getKey(), tableDef);

                String sourceSchema = tableDef.getSource().getSchema();
                String sourceTable = tableDef.getSource().getTable();

                Map<String, ColumnMetadata> sourceColumns = getColumns(sourceMeta, sourceSchema, sourceTable);
                if (sourceColumns.isEmpty()) {
                    throw new SchemaCompatibilityException(
                            String.format("La tabla origen '%s' no existe o no se detectaron columnas accesibles.", formatTableName(sourceSchema, sourceTable))
                    );
                }
            }
        } catch (SQLException e) {
            throw new SchemaCompatibilityException("Error JDBC al conectar a la Base de Datos de Origen.", e);
        } catch (IllegalArgumentException e) {
            throw new SchemaCompatibilityException("Configuracion JDBC de origen invalida: " + e.getMessage(), e);
        }
        log.info("Todas las tablas origen configuradas existen y son accesibles.");
    }

    public static void validateTargetAndMapping(MigrationContract contract) {
        validateTargetAndMapping(contract, DatabaseConnectionManager::getConnection, DatabaseConnectionManager::getConnection);
    }

    /**
     * Valida que las tablas destino existan tras Liquibase y que mantengan un mapeo estructural compatible
     * con las tablas origen: columnas, tipos JDBC, longitudes, nullability, claves primarias y claves foraneas basicas.
     */
    static void validateTargetAndMapping(MigrationContract contract,
                                         ConnectionFactory sourceConnectionFactory,
                                         ConnectionFactory targetConnectionFactory) {
        log.info("Iniciando validacion post-despliegue: compatibilidad estructural Origen vs Destino.");

        ConnectionConfig sourceConnection = requireSourceConnection(contract);
        ConnectionConfig targetConnection = requireTargetConnection(contract);
        validateTables(contract);

        try (Connection sourceConn = sourceConnectionFactory.getConnection(sourceConnection);
             Connection targetConn = targetConnectionFactory.getConnection(targetConnection)) {

            DatabaseMetaData sourceMeta = sourceConn.getMetaData();
            DatabaseMetaData targetMeta = targetConn.getMetaData();

            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String tableNameId = entry.getKey();
                TableMigration tableDef = entry.getValue();
                validateSourceTableReference(tableNameId, tableDef);
                validateTargetTableReference(tableNameId, tableDef);

                String sourceSchema = tableDef.getSource().getSchema();
                String sourceTable = tableDef.getSource().getTable();
                String targetSchema = tableDef.getTarget().getSchema();
                String targetTable = tableDef.getTarget().getTable();

                Map<String, ColumnMetadata> sourceColumns = getColumns(sourceMeta, sourceSchema, sourceTable);
                Map<String, ColumnMetadata> targetColumns = getColumns(targetMeta, targetSchema, targetTable);

                if (sourceColumns.isEmpty()) {
                    throw new SchemaCompatibilityException(
                            String.format("La tabla origen '%s' no existe o no se detectaron columnas accesibles.", formatTableName(sourceSchema, sourceTable))
                    );
                }
                if (targetColumns.isEmpty()) {
                    throw new SchemaCompatibilityException(
                            String.format("La tabla destino '%s' no ha sido creada por Liquibase o no es accesible.", formatTableName(targetSchema, targetTable))
                    );
                }

                validateColumnsMapping(tableNameId, sourceColumns, targetColumns);
                validatePrimaryKeys(tableNameId, sourceMeta, targetMeta, sourceSchema, sourceTable, targetSchema, targetTable);
                validateForeignKeys(tableNameId, contract, sourceMeta, targetMeta, sourceSchema, sourceTable, targetSchema, targetTable);
                validateSimpleUniqueConstraints(tableNameId, sourceMeta, targetMeta, sourceSchema, sourceTable, targetSchema, targetTable);
            }

        } catch (SQLException e) {
            throw new SchemaCompatibilityException("Error al conectar a las bases de datos para comprobar compatibilidad estructural.", e);
        } catch (IllegalArgumentException e) {
            throw new SchemaCompatibilityException("Configuracion JDBC invalida para comprobar compatibilidad estructural: " + e.getMessage(), e);
        }
        log.info("Validacion de compatibilidad estructural completada con exito.");
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection getConnection(ConnectionConfig config) throws SQLException;
    }

    private static ConnectionConfig requireSourceConnection(MigrationContract contract) {
        if (contract == null) {
            throw new SchemaCompatibilityException("El contrato no puede ser nulo.");
        }
        if (contract.getDatabase() == null) {
            throw new SchemaCompatibilityException("El contrato no contiene bloque database.");
        }
        if (contract.getDatabase().getSourceConnection() == null) {
            throw new SchemaCompatibilityException("El contrato no contiene conexion de origen.");
        }
        return contract.getDatabase().getSourceConnection();
    }

    private static ConnectionConfig requireTargetConnection(MigrationContract contract) {
        if (contract == null) {
            throw new SchemaCompatibilityException("El contrato no puede ser nulo.");
        }
        if (contract.getDatabase() == null) {
            throw new SchemaCompatibilityException("El contrato no contiene bloque database.");
        }
        if (contract.getDatabase().getTargetConnection() == null) {
            throw new SchemaCompatibilityException("El contrato no contiene conexion de destino.");
        }
        return contract.getDatabase().getTargetConnection();
    }

    private static void validateTables(MigrationContract contract) {
        if (contract.getTables() == null || contract.getTables().isEmpty()) {
            throw new SchemaCompatibilityException("El contrato no contiene tablas origen activas para validar.");
        }
    }

    private static void validateSourceTableReference(String tableName, TableMigration tableDef) {
        if (tableDef == null) {
            throw new SchemaCompatibilityException("La configuracion de la tabla '" + tableName + "' es nula.");
        }
        if (tableDef.getSource() == null) {
            throw new SchemaCompatibilityException("La tabla '" + tableName + "' no contiene bloque source.");
        }
        if (isBlank(tableDef.getSource().getTable())) {
            throw new SchemaCompatibilityException("La tabla '" + tableName + "' requiere source.table para validar origen.");
        }
    }

    private static void validateTargetTableReference(String tableName, TableMigration tableDef) {
        if (tableDef.getTarget() == null) {
            throw new SchemaCompatibilityException("La tabla '" + tableName + "' no contiene bloque target.");
        }
        if (isBlank(tableDef.getTarget().getTable())) {
            throw new SchemaCompatibilityException("La tabla '" + tableName + "' requiere target.table para validar destino.");
        }
    }

    private static void validateColumnsMapping(String tableNameId,
                                               Map<String, ColumnMetadata> sourceColumns,
                                               Map<String, ColumnMetadata> targetColumns) {
        for (Map.Entry<String, ColumnMetadata> sourceEntry : sourceColumns.entrySet()) {
            String sourceCol = sourceEntry.getKey();
            ColumnMetadata source = sourceEntry.getValue();
            if (!targetColumns.containsKey(sourceCol)) {
                throw new SchemaCompatibilityException(
                        String.format("La columna origen '%s' no existe en la tabla destino '%s' tras generar su esquema.",
                                source.name, tableNameId)
                );
            }

            ColumnMetadata target = targetColumns.get(sourceCol);
            if (!areCompatibleTypes(source.jdbcType, target.jdbcType)) {
                throw new SchemaCompatibilityException(
                        String.format("La columna '%s' de la tabla '%s' tiene tipo JDBC incompatible. Origen=%s(%d) Destino=%s(%d)",
                                source.name, tableNameId, source.typeName, source.jdbcType, target.typeName, target.jdbcType)
                );
            }
            if (hasComparableLength(source.jdbcType)
                    && source.columnSize != null
                    && target.columnSize != null
                    && target.columnSize < source.columnSize) {
                throw new SchemaCompatibilityException(
                        String.format("La columna '%s' de la tabla '%s' tiene longitud destino insuficiente. Origen=%d Destino=%d",
                                source.name, tableNameId, source.columnSize, target.columnSize)
                );
            }
            if (source.nullable != target.nullable) {
                throw new SchemaCompatibilityException(
                        String.format("La columna '%s' de la tabla '%s' tiene nullability incompatible. Origen=%s Destino=%s",
                                source.name, tableNameId, formatNullability(source.nullable), formatNullability(target.nullable))
                );
            }
        }
    }

    private static void validatePrimaryKeys(String tableNameId,
                                            DatabaseMetaData sourceMeta,
                                            DatabaseMetaData targetMeta,
                                            String sourceSchema,
                                            String sourceTable,
                                            String targetSchema,
                                            String targetTable) throws SQLException {
        Set<String> sourcePkColumns = getPrimaryKeyColumns(sourceMeta, sourceSchema, sourceTable);
        Set<String> targetPkColumns = getPrimaryKeyColumns(targetMeta, targetSchema, targetTable);

        if (!sourcePkColumns.isEmpty() && !targetPkColumns.containsAll(sourcePkColumns)) {
            throw new SchemaCompatibilityException("La tabla destino de '" + tableNameId
                    + "' no conserva todas las columnas con clave primaria detectadas en origen. Origen="
                    + sourcePkColumns + " Destino=" + targetPkColumns);
        }
    }

    private static void validateForeignKeys(String tableNameId,
                                            MigrationContract contract,
                                            DatabaseMetaData sourceMeta,
                                            DatabaseMetaData targetMeta,
                                            String sourceSchema,
                                            String sourceTable,
                                            String targetSchema,
                                            String targetTable) throws SQLException {
        Map<String, TableReference> migratedSourceToTargetTables = buildMigratedSourceToTargetTableIndex(contract);
        List<ForeignKeyMetadata> sourceForeignKeys = getImportedKeys(sourceMeta, sourceSchema, sourceTable);
        List<ForeignKeyMetadata> targetForeignKeys = getImportedKeys(targetMeta, targetSchema, targetTable);

        for (ForeignKeyMetadata sourceFk : sourceForeignKeys) {
            TableReference expectedReferencedTarget = migratedSourceToTargetTables.get(normalizeQualifiedName(
                    sourceFk.referencedSchema,
                    sourceFk.referencedTable
            ));
            if (expectedReferencedTarget == null) {
                continue;
            }

            ForeignKeyMetadata expectedFk = new ForeignKeyMetadata();
            expectedFk.column = sourceFk.column;
            expectedFk.referencedSchema = expectedReferencedTarget.schema;
            expectedFk.referencedTable = expectedReferencedTarget.table;
            expectedFk.referencedColumn = sourceFk.referencedColumn;

            ForeignKeyMetadata matchingColumnFk = targetForeignKeys.stream()
                    .filter(targetFk -> equalsNormalized(targetFk.column, sourceFk.column))
                    .findFirst()
                    .orElse(null);

            if (matchingColumnFk == null || !foreignKeyTargetMatches(matchingColumnFk, expectedFk)) {
                throw new SchemaCompatibilityException(
                        String.format("La FK de la tabla '%s', columna '%s', es incompatible. FK origen esperada=%s FK destino=%s",
                                tableNameId,
                                sourceFk.column,
                                formatForeignKey(expectedFk),
                                matchingColumnFk != null ? formatForeignKey(matchingColumnFk) : "AUSENTE")
                );
            }
        }
    }

    private static void validateSimpleUniqueConstraints(String tableNameId,
                                                        DatabaseMetaData sourceMeta,
                                                        DatabaseMetaData targetMeta,
                                                        String sourceSchema,
                                                        String sourceTable,
                                                        String targetSchema,
                                                        String targetTable) throws SQLException {
        Set<String> sourceUniqueColumns = getSimpleUniqueColumns(sourceMeta, sourceSchema, sourceTable);
        Set<String> targetUniqueColumns = getSimpleUniqueColumns(targetMeta, targetSchema, targetTable);

        for (String sourceUniqueColumn : sourceUniqueColumns) {
            if (!targetUniqueColumns.contains(sourceUniqueColumn)) {
                throw new SchemaCompatibilityException(
                        String.format("La columna '%s' de la tabla '%s' era UNIQUE en origen y no conserva UNIQUE simple en destino. Origen=%s Destino=%s",
                                sourceUniqueColumn, tableNameId, sourceUniqueColumns, targetUniqueColumns)
                );
            }
        }
    }

    private static Map<String, ColumnMetadata> getColumns(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        Map<String, ColumnMetadata> columns = readColumns(metaData, schema, table);
        if (!columns.isEmpty()) {
            return columns;
        }

        String upperSchema = schema != null ? schema.toUpperCase(Locale.ROOT) : null;
        columns = readColumns(metaData, upperSchema, table.toUpperCase(Locale.ROOT));
        if (!columns.isEmpty()) {
            return columns;
        }

        String lowerSchema = schema != null ? schema.toLowerCase(Locale.ROOT) : null;
        return readColumns(metaData, lowerSchema, table.toLowerCase(Locale.ROOT));
    }

    private static Map<String, ColumnMetadata> readColumns(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        Map<String, ColumnMetadata> columns = new LinkedHashMap<>();
        String schemaPattern = schema != null && !schema.trim().isEmpty() ? schema : null;

        try (ResultSet rs = metaData.getColumns(null, schemaPattern, table, null)) {
            while (rs.next()) {
                ColumnMetadata column = new ColumnMetadata();
                column.name = rs.getString("COLUMN_NAME");
                column.typeName = rs.getString("TYPE_NAME");
                column.jdbcType = rs.getInt("DATA_TYPE");
                column.columnSize = rs.getObject("COLUMN_SIZE") != null ? rs.getInt("COLUMN_SIZE") : null;
                column.nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.put(column.name.toLowerCase(Locale.ROOT), column);
            }
        }
        return columns;
    }

    private static Set<String> getPrimaryKeyColumns(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        Set<String> pkColumns = new HashSet<>();
        readPrimaryKeyColumns(metaData, schema, table, pkColumns);
        if (pkColumns.isEmpty()) {
            readPrimaryKeyColumns(metaData, schema != null ? schema.toUpperCase(Locale.ROOT) : null, table.toUpperCase(Locale.ROOT), pkColumns);
        }
        if (pkColumns.isEmpty()) {
            readPrimaryKeyColumns(metaData, schema != null ? schema.toLowerCase(Locale.ROOT) : null, table.toLowerCase(Locale.ROOT), pkColumns);
        }
        return pkColumns;
    }

    private static void readPrimaryKeyColumns(DatabaseMetaData metaData,
                                              String schema,
                                              String table,
                                              Set<String> columns) throws SQLException {
        String schemaPattern = schema != null && !schema.trim().isEmpty() ? schema : null;
        try (ResultSet rs = metaData.getPrimaryKeys(null, schemaPattern, table)) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (column != null) {
                    columns.add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private static List<ForeignKeyMetadata> getImportedKeys(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        List<ForeignKeyMetadata> foreignKeys = readImportedKeys(metaData, schema, table);
        if (!foreignKeys.isEmpty()) {
            return foreignKeys;
        }

        foreignKeys = readImportedKeys(metaData, schema != null ? schema.toUpperCase(Locale.ROOT) : null, table.toUpperCase(Locale.ROOT));
        if (!foreignKeys.isEmpty()) {
            return foreignKeys;
        }

        return readImportedKeys(metaData, schema != null ? schema.toLowerCase(Locale.ROOT) : null, table.toLowerCase(Locale.ROOT));
    }

    private static List<ForeignKeyMetadata> readImportedKeys(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        List<ForeignKeyMetadata> foreignKeys = new ArrayList<>();
        String schemaPattern = schema != null && !schema.trim().isEmpty() ? schema : null;
        try (ResultSet rs = metaData.getImportedKeys(null, schemaPattern, table)) {
            while (rs.next()) {
                ForeignKeyMetadata fk = new ForeignKeyMetadata();
                fk.column = rs.getString("FKCOLUMN_NAME");
                fk.referencedSchema = rs.getString("PKTABLE_SCHEM");
                fk.referencedTable = rs.getString("PKTABLE_NAME");
                fk.referencedColumn = rs.getString("PKCOLUMN_NAME");
                if (!isBlank(fk.column) && !isBlank(fk.referencedTable) && !isBlank(fk.referencedColumn)) {
                    foreignKeys.add(fk);
                }
            }
        }
        return foreignKeys;
    }

    private static Set<String> getSimpleUniqueColumns(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        Set<String> uniqueColumns = readSimpleUniqueColumns(metaData, schema, table);
        if (!uniqueColumns.isEmpty()) {
            return uniqueColumns;
        }

        uniqueColumns = readSimpleUniqueColumns(metaData, schema != null ? schema.toUpperCase(Locale.ROOT) : null, table.toUpperCase(Locale.ROOT));
        if (!uniqueColumns.isEmpty()) {
            return uniqueColumns;
        }

        return readSimpleUniqueColumns(metaData, schema != null ? schema.toLowerCase(Locale.ROOT) : null, table.toLowerCase(Locale.ROOT));
    }

    private static Set<String> readSimpleUniqueColumns(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        String schemaPattern = schema != null && !schema.trim().isEmpty() ? schema : null;
        Map<String, Set<String>> columnsByIndex = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getIndexInfo(null, schemaPattern, table, true, false)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                if (isBlank(columnName) || rs.getBoolean("NON_UNIQUE")) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                String indexKey = !isBlank(indexName) ? indexName : "unnamed_" + columnName;
                columnsByIndex.computeIfAbsent(indexKey.toLowerCase(Locale.ROOT), ignored -> new HashSet<>())
                        .add(columnName.toLowerCase(Locale.ROOT));
            }
        }

        return columnsByIndex.values().stream()
                .filter(columns -> columns.size() == 1)
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static Map<String, TableReference> buildMigratedSourceToTargetTableIndex(MigrationContract contract) {
        Map<String, TableReference> index = new LinkedHashMap<>();
        for (TableMigration tableMigration : contract.getTables().values()) {
            if (tableMigration == null || tableMigration.getSource() == null || tableMigration.getTarget() == null) {
                continue;
            }
            index.put(
                    normalizeQualifiedName(tableMigration.getSource().getSchema(), tableMigration.getSource().getTable()),
                    new TableReference(tableMigration.getTarget().getSchema(), tableMigration.getTarget().getTable())
            );
        }
        return index;
    }

    private static boolean foreignKeyTargetMatches(ForeignKeyMetadata actual, ForeignKeyMetadata expected) {
        return equalsNormalized(actual.column, expected.column)
                && equalsNormalized(actual.referencedTable, expected.referencedTable)
                && equalsNormalized(actual.referencedColumn, expected.referencedColumn)
                && (isBlank(expected.referencedSchema) || equalsNormalized(actual.referencedSchema, expected.referencedSchema));
    }

    private static String formatForeignKey(ForeignKeyMetadata fk) {
        return fk.column + " -> " + formatTableName(fk.referencedSchema, fk.referencedTable) + "." + fk.referencedColumn;
    }

    private static String formatNullability(boolean nullable) {
        return nullable ? "NULL" : "NOT NULL";
    }

    private static boolean areCompatibleTypes(int sourceType, int targetType) {
        if (sourceType == targetType) {
            return true;
        }
        return typeFamily(sourceType).equals(typeFamily(targetType));
    }

    private static String typeFamily(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> "NUMERIC";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> "TEXT";
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TEMPORAL";
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BINARY";
            default -> "OTHER_" + jdbcType;
        };
    }

    private static boolean hasComparableLength(int jdbcType) {
        String family = typeFamily(jdbcType);
        return "TEXT".equals(family) || "BINARY".equals(family);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean equalsNormalized(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeQualifiedName(String schema, String table) {
        return normalize(formatTableName(schema, table));
    }

    private static String formatTableName(String schema, String table) {
        return (schema != null && !schema.trim().isEmpty() ? schema + "." : "") + table;
    }

    private static final class ColumnMetadata {
        private String name;
        private String typeName;
        private int jdbcType;
        private Integer columnSize;
        private boolean nullable;
    }

    private static final class ForeignKeyMetadata {
        private String column;
        private String referencedSchema;
        private String referencedTable;
        private String referencedColumn;
    }

    private record TableReference(String schema, String table) {
    }
}

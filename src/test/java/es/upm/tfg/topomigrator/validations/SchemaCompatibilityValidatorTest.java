package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.SchemaCompatibilityException;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;
import junit.framework.TestCase;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SchemaCompatibilityValidatorTest extends TestCase {

    public void testValidateSourceSchemasPassesWhenAllActiveTablesExposeColumns() throws SQLException {
        MigrationContract contract = buildContractWithActiveSource("clientes", "public", "clientes");
        Connection connection = connectionWithTables(Map.of("public.clientes", Arrays.asList("id", "nombre")));

        SchemaCompatibilityValidator.validateSourceSchemas(contract, config -> connection);
    }

    public void testValidateSourceSchemasFailsWhenActiveSourceTableHasNoColumns() throws SQLException {
        MigrationContract contract = buildContractWithActiveSource("clientes", "public", "clientes");
        Connection connection = connectionWithTables(new HashMap<>());

        try {
            SchemaCompatibilityValidator.validateSourceSchemas(contract, config -> connection);
            fail("Se esperaba SchemaCompatibilityException por tabla origen inexistente o inaccesible.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("public.clientes"));
        }
    }

    public void testValidateSourceSchemasFailsWhenConnectionFactoryThrowsSQLException() {
        MigrationContract contract = buildContractWithActiveSource("clientes", "public", "clientes");

        try {
            SchemaCompatibilityValidator.validateSourceSchemas(contract, config -> {
                throw new SQLException("sin conexion");
            });
            fail("Se esperaba SchemaCompatibilityException por error JDBC.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("Base de Datos de Origen"));
            assertTrue(e.getCause() instanceof SQLException);
        }
    }

    public void testValidateSourceSchemasFailsOnMissingDatabaseConfigWithoutNpe() {
        MigrationContract contract = buildContractWithActiveSource("clientes", "public", "clientes");
        contract.setDatabase(null);

        try {
            SchemaCompatibilityValidator.validateSourceSchemas(contract, config -> connectionWithTables(new HashMap<>()));
            fail("Se esperaba SchemaCompatibilityException por database nulo.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("database") || e.getMessage().contains("origen"));
        }
    }

    public void testValidateSourceSchemasFailsOnBlankSourceTableWithoutNpe() {
        MigrationContract contract = buildContractWithActiveSource("clientes", "public", " ");

        try {
            SchemaCompatibilityValidator.validateSourceSchemas(contract, config -> connectionWithTables(new HashMap<>()));
            fail("Se esperaba SchemaCompatibilityException por source.table vacio.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("source.table"));
        }
    }

    public void testValidateSourceSchemasUsesCaseFallbackForMetadataLookup() throws SQLException {
        MigrationContract contract = buildContractWithActiveSource("clientes", "PUBLIC", "CLIENTES");
        Connection connection = connectionWithTables(Map.of("public.clientes", List.of("id")));

        SchemaCompatibilityValidator.validateSourceSchemas(contract, config -> connection);
    }

    public void testValidateTargetAndMappingPassesForCompatibleColumnsPrimaryKeysAndForeignKeys() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(
                        column("id", Types.INTEGER, "int4", 10, false),
                        column("nombre", Types.VARCHAR, "varchar", 100, true),
                        column("empresa_id", Types.INTEGER, "int4", 10, false))),
                Map.of("public.clientes", setOf("id")),
                Map.of("public.clientes", setOf("empresa_id")));
        Connection target = connectionWithMetadata(
                Map.of("dst.clientes", List.of(
                        column("id", Types.BIGINT, "int8", 19, false),
                        column("nombre", Types.VARCHAR, "varchar", 150, true),
                        column("empresa_id", Types.BIGINT, "int8", 19, false))),
                Map.of("dst.clientes", setOf("id")),
                Map.of("dst.clientes", setOf("empresa_id")));

        SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
    }

    public void testValidateTargetAndMappingFailsWhenTargetTableHasNoColumns() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(column("id", Types.INTEGER, "int4", 10, false))),
                Map.of(), Map.of());
        Connection target = connectionWithMetadata(new HashMap<>(), Map.of(), Map.of());

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
            fail("Se esperaba SchemaCompatibilityException por tabla destino sin columnas.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("dst.clientes"));
        }
    }

    public void testValidateTargetAndMappingFailsWhenTargetColumnIsMissing() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(
                        column("id", Types.INTEGER, "int4", 10, false),
                        column("nombre", Types.VARCHAR, "varchar", 100, true))),
                Map.of(), Map.of());
        Connection target = connectionWithMetadata(
                Map.of("dst.clientes", List.of(column("id", Types.INTEGER, "int4", 10, false))),
                Map.of(), Map.of());

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
            fail("Se esperaba SchemaCompatibilityException por columna destino ausente.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("nombre"));
            assertTrue(e.getMessage().contains("clientes"));
        }
    }

    public void testValidateTargetAndMappingFailsOnIncompatibleJdbcType() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(column("id", Types.INTEGER, "int4", 10, false))),
                Map.of(), Map.of());
        Connection target = connectionWithMetadata(
                Map.of("dst.clientes", List.of(column("id", Types.VARCHAR, "varchar", 20, false))),
                Map.of(), Map.of());

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
            fail("Se esperaba SchemaCompatibilityException por tipo incompatible.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("id"));
            assertTrue(e.getMessage().contains("tipo"));
        }
    }

    public void testValidateTargetAndMappingFailsWhenTargetLengthIsShorter() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(column("nombre", Types.VARCHAR, "varchar", 100, true))),
                Map.of(), Map.of());
        Connection target = connectionWithMetadata(
                Map.of("dst.clientes", List.of(column("nombre", Types.VARCHAR, "varchar", 50, true))),
                Map.of(), Map.of());

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
            fail("Se esperaba SchemaCompatibilityException por longitud destino menor.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("nombre"));
            assertTrue(e.getMessage().contains("longitud"));
        }
    }

    public void testValidateTargetAndMappingFailsWhenTargetNullabilityIsMoreRestrictive() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(column("nombre", Types.VARCHAR, "varchar", 100, true))),
                Map.of(), Map.of());
        Connection target = connectionWithMetadata(
                Map.of("dst.clientes", List.of(column("nombre", Types.VARCHAR, "varchar", 100, false))),
                Map.of(), Map.of());

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
            fail("Se esperaba SchemaCompatibilityException por nullability mas restrictiva.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("nombre"));
            assertTrue(e.getMessage().contains("null"));
        }
    }

    public void testValidateTargetAndMappingFailsWhenTargetPrimaryKeyDoesNotConserveSourcePk() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(column("id", Types.INTEGER, "int4", 10, false))),
                Map.of("public.clientes", setOf("id")), Map.of());
        Connection target = connectionWithMetadata(
                Map.of("dst.clientes", List.of(column("id", Types.INTEGER, "int4", 10, false))),
                Map.of("dst.clientes", new LinkedHashSet<>()), Map.of());

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
            fail("Se esperaba SchemaCompatibilityException por PK no conservada.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("clave primaria"));
            assertTrue(e.getMessage().contains("id"));
        }
    }

    public void testValidateTargetAndMappingFailsWhenTargetForeignKeyDoesNotConserveSourceFk() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        Connection source = connectionWithMetadata(
                Map.of("public.clientes", List.of(column("empresa_id", Types.INTEGER, "int4", 10, false))),
                Map.of(), Map.of("public.clientes", setOf("empresa_id")));
        Connection target = connectionWithMetadata(
                Map.of("dst.clientes", List.of(column("empresa_id", Types.INTEGER, "int4", 10, false))),
                Map.of(), Map.of("dst.clientes", new LinkedHashSet<>()));

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(contract, config -> source, config -> target);
            fail("Se esperaba SchemaCompatibilityException por FK no conservada.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("clave for"));
            assertTrue(e.getMessage().contains("empresa_id"));
        }
    }

    public void testValidateTargetAndMappingFailsOnMissingDatabaseConfigWithoutNpe() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        contract.setDatabase(null);

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(
                    contract,
                    config -> connectionWithMetadata(new HashMap<>(), Map.of(), Map.of()),
                    config -> connectionWithMetadata(new HashMap<>(), Map.of(), Map.of()));
            fail("Se esperaba SchemaCompatibilityException por database nulo.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("database"));
        }
    }

    public void testValidateTargetAndMappingFailsOnMissingTargetReferenceWithoutNpe() throws SQLException {
        MigrationContract contract = buildContractWithActiveMapping("clientes", "public", "clientes", "dst", "clientes");
        contract.getTables().get("clientes").setTarget(null);

        try {
            SchemaCompatibilityValidator.validateTargetAndMapping(
                    contract,
                    config -> connectionWithMetadata(new HashMap<>(), Map.of(), Map.of()),
                    config -> connectionWithMetadata(new HashMap<>(), Map.of(), Map.of()));
            fail("Se esperaba SchemaCompatibilityException por target nulo.");
        } catch (SchemaCompatibilityException e) {
            assertTrue(e.getMessage().contains("clientes"));
            assertTrue(e.getMessage().contains("target"));
        }
    }

    private MigrationContract buildContractWithActiveSource(String key, String sourceSchema, String sourceTable) {
        MigrationContract contract = new MigrationContract();
        DatabaseConfig database = new DatabaseConfig();
        ConnectionConfig sourceConnection = new ConnectionConfig();
        sourceConnection.setJdbcUrl("jdbc:test://source");
        database.setSourceConnection(sourceConnection);
        contract.setDatabase(database);

        TableMigration table = new TableMigration();
        table.setEnabled(true);
        table.setSource(tableRef(sourceSchema, sourceTable));
        table.setTarget(tableRef("public", "destino"));
        table.setMigrationType("full");

        Map<String, TableMigration> tables = new LinkedHashMap<>();
        tables.put(key, table);
        contract.setTables(tables);
        return contract;
    }

    private MigrationContract buildContractWithActiveMapping(String key,
                                                             String sourceSchema,
                                                             String sourceTable,
                                                             String targetSchema,
                                                             String targetTable) {
        MigrationContract contract = buildContractWithActiveSource(key, sourceSchema, sourceTable);
        ConnectionConfig targetConnection = new ConnectionConfig();
        targetConnection.setJdbcUrl("jdbc:test://target");
        contract.getDatabase().setTargetConnection(targetConnection);
        contract.getTables().get(key).setTarget(tableRef(targetSchema, targetTable));
        return contract;
    }

    private TableRef tableRef(String schema, String table) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(table);
        return ref;
    }

    private Connection connectionWithTables(Map<String, List<String>> columnsByTable) {
        Map<String, List<TestColumn>> metadataColumns = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
            List<TestColumn> columns = entry.getValue().stream()
                    .map(name -> column(name, Types.VARCHAR, "varchar", 255, true))
                    .toList();
            metadataColumns.put(entry.getKey(), columns);
        }
        DatabaseMetaData metadata = metadataWithTables(metadataColumns, Map.of(), Map.of());
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metadata;
                    }
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Connection connectionWithMetadata(Map<String, List<TestColumn>> columnsByTable,
                                              Map<String, Set<String>> primaryKeysByTable,
                                              Map<String, Set<String>> foreignKeysByTable) {
        DatabaseMetaData metadata = metadataWithTables(columnsByTable, primaryKeysByTable, foreignKeysByTable);
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metadata;
                    }
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private DatabaseMetaData metadataWithTables(Map<String, List<TestColumn>> columnsByTable,
                                                Map<String, Set<String>> primaryKeysByTable,
                                                Map<String, Set<String>> foreignKeysByTable) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getColumns".equals(method.getName())) {
                        String schema = (String) args[1];
                        String table = (String) args[2];
                        String key = (schema != null ? schema : "") + "." + table;
                        return resultSetForColumns(columnsByTable.getOrDefault(key, List.of()));
                    }
                    if ("getPrimaryKeys".equals(method.getName())) {
                        String schema = (String) args[1];
                        String table = (String) args[2];
                        String key = (schema != null ? schema : "") + "." + table;
                        return resultSetForKeys(primaryKeysByTable.getOrDefault(key, Set.of()), "COLUMN_NAME");
                    }
                    if ("getImportedKeys".equals(method.getName())) {
                        String schema = (String) args[1];
                        String table = (String) args[2];
                        String key = (schema != null ? schema : "") + "." + table;
                        return resultSetForKeys(foreignKeysByTable.getOrDefault(key, Set.of()), "FKCOLUMN_NAME");
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private ResultSet resultSetForColumns(List<TestColumn> columns) {
        final int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        index[0]++;
                        return index[0] < columns.size();
                    }
                    if ("getString".equals(method.getName())) {
                        String column = (String) args[0];
                        if ("COLUMN_NAME".equals(column)) {
                            return columns.get(index[0]).name;
                        }
                        if ("TYPE_NAME".equals(column)) {
                            return columns.get(index[0]).typeName;
                        }
                    }
                    if ("getInt".equals(method.getName())) {
                        String column = (String) args[0];
                        if ("DATA_TYPE".equals(column)) {
                            return columns.get(index[0]).jdbcType;
                        }
                        if ("COLUMN_SIZE".equals(column)) {
                            return columns.get(index[0]).size;
                        }
                        if ("NULLABLE".equals(column)) {
                            return columns.get(index[0]).nullable
                                    ? DatabaseMetaData.columnNullable
                                    : DatabaseMetaData.columnNoNulls;
                        }
                        if ("DECIMAL_DIGITS".equals(column)) {
                            return 0;
                        }
                    }
                    if ("getObject".equals(method.getName())) {
                        String column = (String) args[0];
                        if ("COLUMN_SIZE".equals(column)) {
                            return columns.get(index[0]).size;
                        }
                        if ("DECIMAL_DIGITS".equals(column)) {
                            return 0;
                        }
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private ResultSet resultSetForKeys(Set<String> columns, String columnLabel) {
        List<String> orderedColumns = List.copyOf(columns);
        final int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        index[0]++;
                        return index[0] < orderedColumns.size();
                    }
                    if ("getString".equals(method.getName()) && columnLabel.equals(args[0])) {
                        return orderedColumns.get(index[0]);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private TestColumn column(String name, int jdbcType, String typeName, Integer size, boolean nullable) {
        return new TestColumn(name, jdbcType, typeName, size, nullable);
    }

    private Set<String> setOf(String value) {
        Set<String> values = new LinkedHashSet<>();
        values.add(value);
        return values;
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (double.class.equals(returnType)) {
            return 0D;
        }
        if (float.class.equals(returnType)) {
            return 0F;
        }
        if (short.class.equals(returnType)) {
            return (short) 0;
        }
        if (byte.class.equals(returnType)) {
            return (byte) 0;
        }
        if (char.class.equals(returnType)) {
            return (char) 0;
        }
        return null;
    }

    private static final class TestColumn {
        private final String name;
        private final int jdbcType;
        private final String typeName;
        private final Integer size;
        private final boolean nullable;

        private TestColumn(String name, int jdbcType, String typeName, Integer size, boolean nullable) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.size = size;
            this.nullable = nullable;
        }
    }
}

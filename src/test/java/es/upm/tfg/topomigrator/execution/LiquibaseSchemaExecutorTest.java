package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
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
import java.util.LinkedHashMap;
import java.util.Map;

public class LiquibaseSchemaExecutorTest extends TestCase {

    public void testApplyTargetSchemasFailsOnNullContract() {
        try {
            LiquibaseSchemaExecutor.applyTargetSchemas(null);
            fail("Se esperaba InvalidChangelogException por contrato nulo.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("tablas activas"));
        }
    }

    public void testApplyTargetSchemasFailsOnEmptyTables() {
        MigrationContract contract = new MigrationContract();
        contract.setTables(new LinkedHashMap<>());

        try {
            LiquibaseSchemaExecutor.applyTargetSchemas(contract);
            fail("Se esperaba InvalidChangelogException por contrato sin tablas.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("tablas activas"));
        }
    }

    public void testApplyTargetSchemasFailsOnNullActiveTableBeforeOpeningConnection() {
        MigrationContract contract = contractWithTable("tabla_nula", null);

        try {
            LiquibaseSchemaExecutor.applyTargetSchemas(contract);
            fail("Se esperaba InvalidChangelogException por tabla activa nula.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("tabla_nula"));
            assertTrue(e.getMessage().contains("destino"));
        }
    }

    public void testApplyTargetSchemasFailsOnNullTargetBeforeOpeningConnection() {
        TableMigration table = tableWithTarget("public", "clientes");
        table.setTarget(null);
        MigrationContract contract = contractWithTable("clientes", table);

        try {
            LiquibaseSchemaExecutor.applyTargetSchemas(contract);
            fail("Se esperaba InvalidChangelogException por target nulo.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("clientes"));
            assertTrue(e.getMessage().contains("destino"));
        }
    }

    public void testApplyTargetSchemasFailsOnBlankTargetSchemaBeforeOpeningConnection() {
        MigrationContract contract = contractWithTable("clientes", tableWithTarget(" ", "clientes"));

        try {
            LiquibaseSchemaExecutor.applyTargetSchemas(contract);
            fail("Se esperaba InvalidChangelogException por target.schema vacio.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("clientes"));
            assertTrue(e.getMessage().contains("target.schema"));
            assertTrue(e.getMessage().contains("target.table"));
        }
    }

    public void testApplyTargetSchemasFailsOnBlankTargetTableBeforeOpeningConnection() {
        MigrationContract contract = contractWithTable("clientes", tableWithTarget("public", " "));

        try {
            LiquibaseSchemaExecutor.applyTargetSchemas(contract);
            fail("Se esperaba InvalidChangelogException por target.table vacio.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("clientes"));
            assertTrue(e.getMessage().contains("target.schema"));
            assertTrue(e.getMessage().contains("target.table"));
        }
    }

    public void testApplyTargetSchemasFailsOnMissingChangelogBeforeOpeningConnection() {
        MigrationContract contract = contractWithTable("clientes", tableWithTarget("schema_inexistente", "tabla_inexistente"));

        try {
            LiquibaseSchemaExecutor.applyTargetSchemas(contract);
            fail("Se esperaba InvalidChangelogException por changelog ausente.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("schema_inexistente.tabla_inexistente"));
        }
    }

    public void testApplyTargetSchemasSkipsLiquibaseWhenTargetTableAlreadyExists() {
        MigrationContract contract = contractWithTable("nombre_tabla_1", tableWithTarget("nombre_esquema", "nombre_tabla"));

        LiquibaseSchemaExecutor.applyTargetSchemas(contract, config -> connectionWithExistingTable("nombre_esquema", "nombre_tabla"));
    }

    private MigrationContract contractWithTable(String key, TableMigration table) {
        MigrationContract contract = new MigrationContract();
        DatabaseConfig database = new DatabaseConfig();
        ConnectionConfig targetConnection = new ConnectionConfig();
        targetConnection.setJdbcUrl("jdbc:invalid://target");
        targetConnection.setDriver("invalid.Driver");
        database.setTargetConnection(targetConnection);
        contract.setDatabase(database);

        Map<String, TableMigration> tables = new LinkedHashMap<>();
        tables.put(key, table);
        contract.setTables(tables);
        return contract;
    }

    private TableMigration tableWithTarget(String schema, String tableName) {
        TableMigration table = new TableMigration();
        table.setEnabled(true);
        TableRef target = new TableRef();
        target.setSchema(schema);
        target.setTable(tableName);
        table.setTarget(target);
        return table;
    }

    private Connection connectionWithExistingTable(String expectedSchema, String expectedTable) {
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getTables".equals(method.getName())) {
                        String schema = (String) args[1];
                        String table = (String) args[2];
                        boolean exists = expectedSchema.equalsIgnoreCase(schema)
                                && expectedTable.equalsIgnoreCase(table);
                        return resultSet(exists);
                    }
                    return defaultValue(method.getReturnType());
                });

        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metadata;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    throw new SQLException("Liquibase no debe usar la conexion cuando la tabla destino ya existe.");
                });
    }

    private ResultSet resultSet(boolean hasRow) {
        final boolean[] consumed = {false};
        return (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        if (!consumed[0] && hasRow) {
                            consumed[0] = true;
                            return true;
                        }
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
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
}

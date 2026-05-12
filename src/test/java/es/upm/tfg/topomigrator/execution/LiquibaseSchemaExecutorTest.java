package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;
import junit.framework.TestCase;

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
}

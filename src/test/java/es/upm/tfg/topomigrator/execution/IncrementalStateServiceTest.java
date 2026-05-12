package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class IncrementalStateServiceTest extends TestCase {

    public void testBuildStateKeyUsesCanonicalTargetTableIdentity() throws Exception {
        IncrementalStateService service = service();

        assertEquals("public.clientes", service.buildStateKey(table("Source", "Origen", "Public", "Clientes")));
    }

    public void testSuccessfulBatchPersistsInspectableStateInConfiguredFile() throws Exception {
        Path stateFile = Files.createTempDirectory("topomigrator-state").resolve("incremental-state.json");
        IncrementalStateService service = new IncrementalStateService(stateFile);
        TableMigration table = table("src", "clientes_src", "dst", "clientes");

        service.saveSuccessfulBatch(
                service.buildStateKey(table),
                table,
                "2024-01-01",
                "2024-02-01",
                "exec-1",
                "exec-1-table-1",
                7L
        );

        assertTrue(Files.exists(stateFile));
        String json = Files.readString(stateFile);
        assertTrue("El estado debe escribirse como JSON legible", json.contains("\n"));

        IncrementalStateService.IncrementalState state = service.getState("dst.clientes");
        assertNotNull(state);
        assertEquals("dst.clientes", state.stateKey);
        assertEquals("src.clientes_src", state.sourceTable);
        assertEquals("dst.clientes", state.targetTable);
        assertEquals("updated_at", state.column);
        assertEquals("2024-01-01", state.previousProcessedValue);
        assertEquals("2024-02-01", state.lastProcessedValue);
        assertEquals(Integer.valueOf(50), state.batchSize);
        assertEquals("SUCCESS", state.executionStatus);
        assertEquals("exec-1", state.executionId);
        assertEquals("exec-1-table-1", state.tableExecutionId);
        assertEquals(Long.valueOf(7L), state.recordsProcessed);
        assertNotNull(state.updatedAt);
    }

    public void testBlankCursorDoesNotCreateStateFile() throws Exception {
        Path stateFile = Files.createTempDirectory("topomigrator-state").resolve("incremental-state.json");
        IncrementalStateService service = new IncrementalStateService(stateFile);
        TableMigration table = table("src", "clientes_src", "dst", "clientes");

        service.saveSuccessfulBatch(service.buildStateKey(table), table, "2024-01-01", " ", "exec-1", "exec-1-table-1", 0L);

        assertFalse(Files.exists(stateFile));
        assertNull(service.getState("dst.clientes"));
    }

    private IncrementalStateService service() throws Exception {
        return new IncrementalStateService(Files.createTempDirectory("topomigrator-state").resolve("incremental-state.json"));
    }

    private TableMigration table(String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
        TableMigration table = new TableMigration();
        table.setEnabled(true);
        table.setMigrationType("incremental");
        table.setSource(tableRef(sourceSchema, sourceTable));
        table.setTarget(tableRef(targetSchema, targetTable));

        IncrementalConfig incrementalConfig = new IncrementalConfig();
        incrementalConfig.setColumn("updated_at");
        incrementalConfig.setStartValue("2024-01-01");
        incrementalConfig.setBatchSize(50);
        incrementalConfig.setLoadStrategy("append");
        table.setIncrementalConfig(incrementalConfig);
        return table;
    }

    private TableRef tableRef(String schema, String tableName) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(tableName);
        return ref;
    }
}

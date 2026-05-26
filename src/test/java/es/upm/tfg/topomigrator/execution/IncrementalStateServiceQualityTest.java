package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IncrementalStateServiceQualityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void saveSuccessfulBatchPersistsCursorWithCanonicalStateKey() throws Exception {
        Path stateFile = temporaryFolder.newFolder("state").toPath().resolve("incremental-state.json");
        IncrementalStateService service = new IncrementalStateService(stateFile);
        TableMigration table = QualityTestData.incrementalTable("Orders");

        String stateKey = service.buildStateKey(table);
        service.saveSuccessfulBatch(stateKey, table, "2026-01-01T00:00:00", "2026-05-26T12:00:00", "exec-1", "exec-1-t1", 10L);

        IncrementalStateService.IncrementalState state = service.getState("public.orders");
        assertEquals("public.orders", stateKey);
        assertEquals("2026-01-01T00:00:00", state.previousProcessedValue);
        assertEquals("2026-05-26T12:00:00", state.lastProcessedValue);
        assertEquals(Long.valueOf(10L), state.recordsProcessed);
        assertTrue(stateFile.toFile().isFile());
    }

    @Test
    public void saveSuccessfulBatchDoesNotPersistBlankCursor() throws Exception {
        Path stateFile = temporaryFolder.newFolder("state").toPath().resolve("incremental-state.json");
        IncrementalStateService service = new IncrementalStateService(stateFile);
        TableMigration table = QualityTestData.incrementalTable("orders");

        service.saveSuccessfulBatch("public.orders", table, "old", " ", "exec-1", "exec-1-t1", 10L);

        assertNull(service.getState("public.orders"));
    }
}

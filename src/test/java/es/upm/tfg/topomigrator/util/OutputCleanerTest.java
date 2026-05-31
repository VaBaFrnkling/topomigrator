package es.upm.tfg.topomigrator.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OutputCleanerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cleanOutputsRemovesRuntimeErrorsAndTracesButPreservesStateAndLogs() throws Exception {
        Path outputs = temporaryFolder.newFolder("outputs").toPath();
        Path errorLog = outputs.resolve("errors/error.log");
        Path traceFile = outputs.resolve("traces/tables/customers.json");
        Path lastExecutionId = outputs.resolve("traces/.last_execution_id");
        Path applicationLog = outputs.resolve("logs/application.log");
        Path incrementalState = outputs.resolve("state/incremental-state.json");

        Files.createDirectories(errorLog.getParent());
        Files.createDirectories(traceFile.getParent());
        Files.createDirectories(applicationLog.getParent());
        Files.createDirectories(incrementalState.getParent());
        Files.writeString(errorLog, "stale test error");
        Files.writeString(traceFile, "stale trace");
        Files.writeString(lastExecutionId, "7");
        Files.writeString(applicationLog, "existing app log");
        Files.writeString(incrementalState, "{}");

        OutputCleaner.cleanOutputs(outputs);

        assertFalse(Files.exists(errorLog));
        assertFalse(Files.exists(traceFile));
        assertTrue(Files.exists(lastExecutionId));
        assertTrue(Files.exists(applicationLog));
        assertTrue(Files.exists(incrementalState));
    }
}

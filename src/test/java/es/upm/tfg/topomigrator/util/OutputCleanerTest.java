package es.upm.tfg.topomigrator.util;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests para {@link OutputCleaner}.
 */
public class OutputCleanerTest extends TestCase {

    private Path outputsRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        outputsRoot = Files.createTempDirectory("output-cleaner-test-");
    }

    @Override
    protected void tearDown() throws Exception {
        if (outputsRoot != null && Files.exists(outputsRoot)) {
            Files.walk(outputsRoot)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
        super.tearDown();
    }

    public void testCleanOutputsDoesNotCrashOnMissingDirs() {
        OutputCleaner.cleanOutputs(outputsRoot);
    }

    public void testCleanOutputsDeletesTemporaryTraceErrorAndFlowFiles() throws Exception {
        Path traceFile = write("traces/table-trace.json", "{}");
        Path errorFile = write("errors/error.log", "error de test");
        Path flowFile = write("flows/generated-flow.json", "{}");

        OutputCleaner.cleanOutputs(outputsRoot);

        assertFalse(Files.exists(traceFile));
        assertFalse(Files.exists(errorFile));
        assertFalse(Files.exists(flowFile));
        assertTrue(Files.exists(outputsRoot.resolve("traces")));
        assertTrue(Files.exists(outputsRoot.resolve("errors")));
        assertTrue(Files.exists(outputsRoot.resolve("flows")));
    }

    public void testCleanOutputsDeletesFilesInTemporarySubdirectories() throws Exception {
        Path nestedTrace = write("traces/tables/clientes.json", "{}");
        Path nestedError = write("errors/nifi/failure.log", "failure");
        Path nestedFlow = write("flows/archive/generated.json", "{}");

        OutputCleaner.cleanOutputs(outputsRoot);

        assertFalse(Files.exists(nestedTrace));
        assertFalse(Files.exists(nestedError));
        assertFalse(Files.exists(nestedFlow));
    }

    public void testCleanOutputsPreservesLastExecutionIdFile() throws Exception {
        Path lastIdFile = write("traces/.last_execution_id", "27");
        Path traceFile = write("traces/temporary_trace.json", "{}");

        OutputCleaner.cleanOutputs(outputsRoot);

        assertTrue("El fichero .last_execution_id debe preservarse", Files.exists(lastIdFile));
        assertEquals("27", Files.readString(lastIdFile).trim());
        assertFalse("Las trazas normales deben eliminarse", Files.exists(traceFile));
    }

    public void testCleanOutputsPreservesLogsDirectory() throws Exception {
        Path logFile = write("logs/topomigrator.log", "Este log NO deberia borrarse");

        OutputCleaner.cleanOutputs(outputsRoot);

        assertTrue(Files.exists(outputsRoot.resolve("logs")));
        assertTrue(Files.exists(logFile));
        assertEquals("Este log NO deberia borrarse", Files.readString(logFile));
    }

    public void testCleanOutputsPreservesIncrementalStateDirectory() throws Exception {
        Path stateFile = write("state/incremental-state.json", "{\"public.clientes\":{}}");
        Path manualStateFile = write("state/manual-note.txt", "keep");
        Path nestedStateFile = write("state/nested/keep.json", "{}");
        Path traceFile = write("traces/temporary_trace.json", "{}");

        OutputCleaner.cleanOutputs(outputsRoot);

        assertTrue(Files.exists(stateFile));
        assertEquals("{\"public.clientes\":{}}", Files.readString(stateFile));
        assertTrue(Files.exists(manualStateFile));
        assertEquals("keep", Files.readString(manualStateFile));
        assertTrue(Files.exists(nestedStateFile));
        assertFalse(Files.exists(traceFile));
    }

    public void testProtectedDirectoriesGuardrailIncludesStateAndLogs() {
        assertTrue("'state' debe estar en PROTECTED_DIRECTORIES",
                OutputCleaner.PROTECTED_DIRECTORIES.contains("state"));
        assertTrue("'logs' debe estar en PROTECTED_DIRECTORIES",
                OutputCleaner.PROTECTED_DIRECTORIES.contains("logs"));
    }

    private Path write(String relativePath, String content) throws Exception {
        Path file = outputsRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}

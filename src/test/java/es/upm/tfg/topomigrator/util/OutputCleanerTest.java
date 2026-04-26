package es.upm.tfg.topomigrator.util;

import junit.framework.TestCase;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests para {@link OutputCleaner}.
 * Cubre: limpieza de archivos existentes, preservación de directorios,
 * directorios inexistentes (no crash), limpieza de subdirectorios y preservación
 * del fichero de secuencia .last_execution_id.
 */
public class OutputCleanerTest extends TestCase {

    /** Directorio temporal para los tests. */
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempDir = Files.createTempDirectory("output-cleaner-test-");
    }

    @Override
    protected void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        }
        super.tearDown();
    }

    public void testCleanOutputsDoesNotCrashOnEmptyDirs() {
        OutputCleaner.cleanOutputs();
    }

    public void testCleanOutputsDoesNotCrashOnMissingDirs() {
        OutputCleaner.cleanOutputs();
    }

    public void testCleanOutputsDeletesTraceAndErrorFiles() throws Exception {
        Path tracesDir = Path.of("outputs", "traces");
        Path errorsDir = Path.of("outputs", "errors");
        Files.createDirectories(tracesDir);
        Files.createDirectories(errorsDir);

        Path traceFile = tracesDir.resolve("test_trace_cleanup.json");
        Path errorFile = errorsDir.resolve("test_error_cleanup.log");

        try (FileWriter w1 = new FileWriter(traceFile.toFile());
             FileWriter w2 = new FileWriter(errorFile.toFile())) {
            w1.write("{\"test\": true}");
            w2.write("error de test");
        }

        assertTrue(Files.exists(traceFile));
        assertTrue(Files.exists(errorFile));

        OutputCleaner.cleanOutputs();

        assertFalse(Files.exists(traceFile));
        assertFalse(Files.exists(errorFile));
        assertTrue(Files.exists(tracesDir));
        assertTrue(Files.exists(errorsDir));
    }

    public void testCleanOutputsPreservesLogsDirectory() throws Exception {
        Path logsDir = Path.of("outputs", "logs");
        Files.createDirectories(logsDir);

        Path logFile = logsDir.resolve("test_preservation.log");
        try (FileWriter w = new FileWriter(logFile.toFile())) {
            w.write("Este log NO debería borrarse");
        }

        OutputCleaner.cleanOutputs();

        assertTrue(Files.exists(logsDir));
        assertTrue(Files.exists(logFile));

        Files.deleteIfExists(logFile);
    }

    public void testCleanOutputsDeletesFilesInSubdirectories() throws Exception {
        Path tracesTablesDir = Path.of("outputs", "traces", "tables");
        Files.createDirectories(tracesTablesDir);

        Path nestedFile = tracesTablesDir.resolve("test_nested_cleanup.json");
        try (FileWriter w = new FileWriter(nestedFile.toFile())) {
            w.write("{\"nested\": true}");
        }

        assertTrue(Files.exists(nestedFile));

        OutputCleaner.cleanOutputs();

        assertFalse(Files.exists(nestedFile));
    }

    public void testCleanOutputsPreservesLastExecutionIdFile() throws Exception {
        Path tracesDir = Path.of("outputs", "traces");
        Files.createDirectories(tracesDir);

        Path lastIdFile = tracesDir.resolve(".last_execution_id");
        Files.writeString(lastIdFile, "27");

        Path traceFile = tracesDir.resolve("temporary_trace.json");
        Files.writeString(traceFile, "{}");

        OutputCleaner.cleanOutputs();

        assertTrue("El fichero .last_execution_id debe preservarse", Files.exists(lastIdFile));
        assertEquals("27", Files.readString(lastIdFile).trim());
        assertFalse("Las trazas normales deben eliminarse", Files.exists(traceFile));
    }
}

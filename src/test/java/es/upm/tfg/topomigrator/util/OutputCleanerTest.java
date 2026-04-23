package es.upm.tfg.topomigrator.util;

import junit.framework.TestCase;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests para {@link OutputCleaner}.
 * Cubre: limpieza de archivos existentes, preservación de directorios,
 * directorios inexistentes (no crash), y limpieza de subdirectorios.
 */
public class OutputCleanerTest extends TestCase {

    /** Directorio temporal para los tests. */
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Trabajamos en un directorio temporal dentro del proyecto
        tempDir = Files.createTempDirectory("output-cleaner-test-");
    }

    @Override
    protected void tearDown() throws Exception {
        // Limpiar restos del directorio temporal
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        }
        super.tearDown();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TESTS SOBRE cleanOutputs() — INVOCACIÓN DIRECTA
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifica que cleanOutputs() se ejecuta sin errores incluso cuando los
     * directorios de outputs ya existen y están vacíos.
     */
    public void testCleanOutputsDoesNotCrashOnEmptyDirs() {
        // Los directorios outputs/traces y outputs/errors pueden existir o no.
        // cleanOutputs debe sobrevivir sin excepción en cualquier caso.
        OutputCleaner.cleanOutputs();
        // Si llegamos aquí, el test pasa
    }

    /**
     * Verifica que cleanOutputs() se ejecuta sin errores cuando los
     * directorios de outputs NO existen.
     */
    public void testCleanOutputsDoesNotCrashOnMissingDirs() {
        // Llamamos cleanOutputs — si los directorios no existen, simplemente retorna
        OutputCleaner.cleanOutputs();
        // Sin excepción = test superado
    }

    /**
     * Prueba de integración: crea archivos en outputs/traces y outputs/errors,
     * ejecuta cleanOutputs() y verifica que fueron eliminados.
     */
    public void testCleanOutputsDeletesTraceAndErrorFiles() throws Exception {
        // Crear directorios y archivos de prueba
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

        assertTrue("El fichero de traza debería existir antes de limpiar", Files.exists(traceFile));
        assertTrue("El fichero de error debería existir antes de limpiar", Files.exists(errorFile));

        // Ejecutar la limpieza
        OutputCleaner.cleanOutputs();

        // Verificar eliminación
        assertFalse("El fichero de traza debería haberse eliminado", Files.exists(traceFile));
        assertFalse("El fichero de error debería haberse eliminado", Files.exists(errorFile));

        // Los directorios deben seguir existiendo (solo se borran archivos, no carpetas)
        assertTrue("El directorio traces debe seguir existiendo", Files.exists(tracesDir));
        assertTrue("El directorio errors debe seguir existiendo", Files.exists(errorsDir));
    }

    /**
     * Verifica que cleanOutputs() respeta el directorio outputs/logs (no lo borra).
     */
    public void testCleanOutputsPreservesLogsDirectory() throws Exception {
        Path logsDir = Path.of("outputs", "logs");
        Files.createDirectories(logsDir);

        Path logFile = logsDir.resolve("test_preservation.log");
        try (FileWriter w = new FileWriter(logFile.toFile())) {
            w.write("Este log NO debería borrarse");
        }

        OutputCleaner.cleanOutputs();

        // El directorio logs y sus archivos deben seguir intactos
        assertTrue("El directorio logs debe seguir existiendo", Files.exists(logsDir));
        assertTrue("El fichero de log debe seguir existiendo", Files.exists(logFile));

        // Limpiar el fichero de test
        Files.deleteIfExists(logFile);
    }

    /**
     * Verifica que cleanOutputs() elimina archivos dentro de subdirectorios.
     */
    public void testCleanOutputsDeletesFilesInSubdirectories() throws Exception {
        Path tracesTablesDir = Path.of("outputs", "traces", "tables");
        Files.createDirectories(tracesTablesDir);

        Path nestedFile = tracesTablesDir.resolve("test_nested_cleanup.json");
        try (FileWriter w = new FileWriter(nestedFile.toFile())) {
            w.write("{\"nested\": true}");
        }

        assertTrue(Files.exists(nestedFile));

        OutputCleaner.cleanOutputs();

        assertFalse("El fichero anidado debería haberse eliminado", Files.exists(nestedFile));
    }
}

package es.upm.tfg.topomigrator.util;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class OutputDirectoryInitializerTest extends TestCase {

    public void testEnsureOutputDirectoriesCreatesRuntimeStructure() {
        OutputDirectoryInitializer.ensureOutputDirectories();

        assertDirectory("outputs");
        assertDirectory("outputs/logs");
        assertDirectory("outputs/errors");
        assertDirectory("outputs/traces");
        assertDirectory("outputs/traces/tables");
        assertDirectory("outputs/flows");
        assertDirectory("outputs/state");
    }

    private void assertDirectory(String path) {
        Path directory = Paths.get(path);
        assertTrue(path + " debe existir", Files.exists(directory));
        assertTrue(path + " debe ser un directorio", Files.isDirectory(directory));
    }
}

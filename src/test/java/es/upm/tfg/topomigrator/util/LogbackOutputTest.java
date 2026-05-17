package es.upm.tfg.topomigrator.util;

import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LogbackOutputTest extends TestCase {

    private static final Logger log = LoggerFactory.getLogger(LogbackOutputTest.class);

    public void testInfoAndErrorLogsAreWrittenToConfiguredOutputFiles() {
        OutputDirectoryInitializer.ensureOutputDirectories();

        log.info("Mensaje de prueba para application.log");
        log.error("Mensaje de prueba para error.log");

        assertLogFileExists(Paths.get("outputs", "logs", "application.log"));
        assertLogFileExists(Paths.get("outputs", "errors", "error.log"));
    }

    private void assertLogFileExists(Path path) {
        assertTrue(path + " debe existir", Files.exists(path));
        assertTrue(path + " debe contener logs", path.toFile().length() > 0);
    }
}

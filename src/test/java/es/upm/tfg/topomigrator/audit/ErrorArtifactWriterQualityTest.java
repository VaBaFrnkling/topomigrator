package es.upm.tfg.topomigrator.audit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ErrorArtifactWriterQualityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writeOrchestrationErrorCreatesCompactSanitizedJsonArtifact() throws Exception {
        Path errorsDir = temporaryFolder.newFolder("errors").toPath();
        ErrorArtifactWriter writer = new ErrorArtifactWriter(errorsDir);

        writer.writeOrchestrationError(
                "SCHEMA_COMPATIBILITY_VALIDATION",
                new IllegalStateException("password=superSecretValue token=abc123 schema mismatch"),
                Map.of("activeTables", List.of("customers", "orders"))
        );

        List<Path> files;
        try (var stream = Files.list(errorsDir)) {
            files = stream.toList();
        }

        assertEquals(1, files.size());
        String json = Files.readString(files.get(0));
        assertTrue(json.contains("\"executionPhase\": \"SCHEMA_COMPATIBILITY_VALIDATION\""));
        assertTrue(json.contains("\"exceptionType\": \"IllegalStateException\""));
        assertTrue(json.contains("\"activeTables\""));
        assertTrue(json.contains("password"));
        assertTrue(json.contains("token"));
        assertTrue(json.contains("[REDACTED]"));
        assertTrue(!json.contains("superSecretValue"));
        assertTrue(!json.contains("abc123"));
        assertTrue(!json.contains("sanitized"));
    }
}

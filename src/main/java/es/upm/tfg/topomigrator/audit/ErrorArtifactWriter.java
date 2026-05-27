package es.upm.tfg.topomigrator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class ErrorArtifactWriter {

    private static final Logger log = LoggerFactory.getLogger(ErrorArtifactWriter.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Gson gson;
    private final Path errorsDir;

    public ErrorArtifactWriter() {
        this(Paths.get("outputs", "errors"));
    }

    ErrorArtifactWriter(Path errorsDir) {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.errorsDir = errorsDir.toAbsolutePath().normalize();
    }

    public void writeOrchestrationError(String executionPhase, Exception error, Map<String, Object> context) {
        ErrorArtifact artifact = baseArtifact(executionPhase, error);
        artifact.context = context;
        write("orchestration-" + executionPhase, artifact);
    }

    public void writeExecutionError(String executionId, String executionPhase, Exception error) {
        ErrorArtifact artifact = baseArtifact(executionPhase, error);
        artifact.executionId = executionId;
        write("execution-" + executionPhase, artifact);
    }

    public void writeTableError(String executionId,
                                String tableExecutionId,
                                String executionPhase,
                                String table,
                                Exception error) {
        ErrorArtifact artifact = baseArtifact(executionPhase, error);
        artifact.executionId = executionId;
        artifact.tableExecutionId = tableExecutionId;
        artifact.table = table;
        write(tableExecutionId + "-" + table + "-error", artifact);
    }

    private ErrorArtifact baseArtifact(String executionPhase, Exception error) {
        ErrorArtifact artifact = new ErrorArtifact();
        artifact.timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        artifact.executionPhase = executionPhase;
        artifact.severity = "ERROR";
        artifact.status = "FAILED";
        artifact.exceptionType = error != null ? error.getClass().getSimpleName() : "UnknownException";
        artifact.message = sanitize(error != null ? error.getMessage() : null);
        return artifact;
    }

    private void write(String filenamePrefix, ErrorArtifact artifact) {
        try {
            Files.createDirectories(errorsDir);
            Path target = errorsDir.resolve(safeFilename(filenamePrefix) + "-" + LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER) + ".json");
            try (FileWriter writer = new FileWriter(target.toFile())) {
                gson.toJson(artifact, writer);
            }
            log.info("Artefacto de error generado: {}", target);
        } catch (IOException e) {
            log.error("No se pudo escribir el artefacto de error para fase {}: {}",
                    artifact.executionPhase, e.getMessage(), e);
        }
    }

    private String safeFilename(String raw) {
        String value = raw == null || raw.isBlank() ? "error" : raw.trim();
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Fallo sin diagnostico disponible.";
        }
        String sanitized = message;
        sanitized = sanitized.replaceAll("(?i)(password|pwd|token|jwt|secret|api_key|apikey)(\\s*[=:]\\s*)[^\\s,;}&]+", "$1$2[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)(\"(?:password|pwd|token|jwt|secret|api_key|apikey)\"\\s*:\\s*\")[^\"]*(\")", "$1[REDACTED]$2");
        sanitized = sanitized.replaceAll("(?i)(https?://[^\\s/@:]+:)[^\\s/@]+(@)", "$1[REDACTED]$2");
        sanitized = sanitized.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer [REDACTED]");
        sanitized = sanitized.replaceAll("(?i)(Authorization:\\s*Bearer\\s+)[A-Za-z0-9._\\-]+", "$1[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)([?&](?:password|pwd|token|jwt|secret|api_key|apikey)=)[^&\\s]*", "$1[REDACTED]");
        return sanitized;
    }

    private static final class ErrorArtifact {
        private String timestamp;
        private String executionId;
        private String tableExecutionId;
        private String executionPhase;
        private String severity;
        private String status;
        private String table;
        private String exceptionType;
        private String message;
        private Map<String, Object> context;
    }
}

package es.upm.tfg.topomigrator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import es.upm.tfg.topomigrator.util.SchemaTableIdentifierUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TraceabilityManager {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityManager.class);
    private final Gson gson;
    private final Path tracesBaseDir;
    private final Path tracesTablesDir;

    public TraceabilityManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        this.tracesBaseDir = Paths.get("outputs", "traces").toAbsolutePath();
        this.tracesTablesDir = this.tracesBaseDir.resolve("tables");

        initializeDirectories();
    }

    private void initializeDirectories() {
        try {
            if (!Files.exists(tracesBaseDir)) Files.createDirectories(tracesBaseDir);
            if (!Files.exists(tracesTablesDir)) Files.createDirectories(tracesTablesDir);
        } catch (IOException e) {
            log.warn("No se pudieron crear los directorios base de auditoría en {}: {}", tracesBaseDir, e.getMessage());
        }
    }

    public void writeTableTrace(TableTrace trace) {
        if (trace == null || trace.table == null || trace.table.target == null) {
            log.error("El trace de tabla proporcionado es nulo o incompleto. No se puede guardar.");
            return;
        }

        String qualifiedTargetId = SchemaTableIdentifierUtils.toQualifiedIdentifier(
                trace.table.target.schema,
                trace.table.target.name
        );
        String fileName = SchemaTableIdentifierUtils.toTraceFileName(
                trace.table.target.schema,
                trace.table.target.name
        );
        Path targetPath = tracesTablesDir.resolve(fileName);

        try (FileWriter writer = new FileWriter(targetPath.toFile())) {
            gson.toJson(trace, writer);
            log.info("Traza de tabla generada con éxito para {}: {}", qualifiedTargetId, targetPath);
        } catch (IOException e) {
            log.error("Fallo al escribir la traza JSON para la tabla {}: {}", qualifiedTargetId, e.getMessage());
        }
    }

    public void writeSummary(SummaryTrace summary) {
        if (summary == null) {
            log.error("El summary proporcionado es nulo. No se puede guardar.");
            return;
        }

        Path targetPath = tracesBaseDir.resolve("summary.json");

        try (FileWriter writer = new FileWriter(targetPath.toFile())) {
            gson.toJson(summary, writer);
            log.info("Sumario de auditoría global generado con éxito: {}", targetPath);
        } catch (IOException e) {
            log.error("Fallo al escribir el sumario global JSON: {}", e.getMessage());
        }
    }
}

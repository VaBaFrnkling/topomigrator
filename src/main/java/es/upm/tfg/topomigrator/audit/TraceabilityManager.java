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

/**
 * Gestor encargado de serializar e inyectar la información de auditoría 
 * del estado de la migración utilizando la librería GSON.
 */
public class TraceabilityManager {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityManager.class);
    private final Gson gson;
    private final Path tracesBaseDir;
    private final Path tracesTablesDir;

    public TraceabilityManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        
        // Determinar directorios, por defecto dentro de un output mapeado en Docker
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

    /**
     * Escribe un fichero JSON individual de auditoría para una tabla recién migrada.
     */
    public void writeTableTrace(TableTrace trace) {
        if (trace == null || trace.table == null || trace.table.target == null) {
            log.error("El trace de tabla proporcionado es nulo o incompleto. No se puede guardar.");
            return;
        }
        
        String fileName = trace.table.target.name + ".json";
        Path targetPath = tracesTablesDir.resolve(fileName);
        
        try (FileWriter writer = new FileWriter(targetPath.toFile())) {
            gson.toJson(trace, writer);
            log.info("Traza de tabla generada con éxito: {}", targetPath);
        } catch (IOException e) {
            log.error("Fallo al escribir la traza JSON para la tabla {}: {}", trace.table.target.name, e.getMessage());
        }
    }

    /**
     * Escribe el fichero JSON sumatorio/global de la ejecución completa de las tablas.
     */
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

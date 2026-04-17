package es.upm.tfg.topomigrator.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Utilidad responsable de limpiar el entorno de salida (ficheros residuales de ejecuciones previas)
 * antes de comenzar una nueva migración.
 */
public class OutputCleaner {
    private static final Logger log = LoggerFactory.getLogger(OutputCleaner.class);

    /**
     * Elimina todos los archivos del interior de las carpetas relacionadas con
     * la trazabilidad (traces) y los errores reportados, para evitar
     * datos fantasmas de ejecuciones anteriores.
     */
    public static void cleanOutputs() {
        log.info("Realizando purga inicial: Borrando archivos de migraciones pasadas...");
        
        // Limpiamos trazas y errores.
        // Omitimos intencionadamente "outputs/logs" para no corromper los ficheros 
        // de Logback (Java) o los logs en caliente montados por el volumen de NiFi.
        cleanDirectory(Paths.get("outputs", "traces"));
        cleanDirectory(Paths.get("outputs", "errors"));
        cleanDirectory(Paths.get("outputs", "flows")); // Limpiamos flujos residuales si los hubiese en un subdirectorio output
        
        log.info("Entorno /outputs purgado. Todo limpio y claro para iniciar.");
    }

    private static void cleanDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        log.warn("No se pudo eliminar el archivo (puede estar en uso): {}", file);
                    }
                });
        } catch (IOException e) {
            log.error("Error intentando rastrear el directorio {} para su limpieza.", directory, e);
        }
    }
}

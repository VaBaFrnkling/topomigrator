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
     * Fichero de estado que debe preservarse para no reiniciar la secuencia de executionId.
     */
    private static final Path LAST_EXECUTION_ID_FILE = Paths.get("outputs", "traces", ".last_execution_id").toAbsolutePath().normalize();

    /**
     * Elimina todos los archivos del interior de las carpetas relacionadas con
     * la trazabilidad (traces) y los errores reportados, para evitar
     * datos fantasmas de ejecuciones anteriores.
     */
    public static void cleanOutputs() {
        log.info("Realizando purga inicial: Borrando archivos de migraciones pasadas...");

        // Limpiamos trazas, errores y flujos residuales.
        // Omitimos intencionadamente:
        // - outputs/logs, para no corromper los ficheros de Logback (Java)
        //   ni los logs en caliente montados por el volumen de NiFi.
        // - outputs/traces/.last_execution_id, porque conserva el contador
        //   secuencial de ejecuciones entre invocaciones.
        cleanDirectory(Paths.get("outputs", "traces"));
        cleanDirectory(Paths.get("outputs", "errors"));
        cleanDirectory(Paths.get("outputs", "flows"));

        log.info("Entorno /outputs purgado. Todo limpio y claro para iniciar.");
    }

    private static void cleanDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                .forEach(file -> {
                    if (shouldPreserve(file)) {
                        log.debug("Preservando fichero de estado: {}", file);
                        return;
                    }
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.warn("No se pudo eliminar el archivo (puede estar en uso): {}", file);
                    }
                });
        } catch (IOException e) {
            log.error("Error intentando rastrear el directorio {} para su limpieza.", directory, e);
        }
    }

    private static boolean shouldPreserve(Path file) {
        return file.toAbsolutePath().normalize().equals(LAST_EXECUTION_ID_FILE);
    }
}

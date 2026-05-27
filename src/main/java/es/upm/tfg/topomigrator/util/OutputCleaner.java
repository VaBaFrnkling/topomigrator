package es.upm.tfg.topomigrator.util;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Utilidad responsable de limpiar el entorno de salida (ficheros residuales de ejecuciones previas)
 * antes de comenzar una nueva migración.
 */
public class OutputCleaner {
    /**
     * Directorios protegidos que nunca deben ser limpiados por esta utilidad.
     * Guardrail programático: cualquier intento de pasar uno de estos a cleanDirectory
     * será bloqueado con un log de advertencia en lugar de ejecutarse.
     */
    static final Set<String> PROTECTED_DIRECTORIES = Set.of("state", "logs");

    /**
     * Elimina todos los archivos del interior de las carpetas relacionadas con
     * la trazabilidad (traces) y los errores reportados, para evitar
     * datos fantasmas de ejecuciones anteriores.
     */
    public static void cleanOutputs() {
        cleanOutputs(Paths.get("outputs"));
    }

    static void cleanOutputs(Path outputsRoot) {
        // Limpiamos trazas, errores y flujos residuales.
        // Omitimos intencionadamente:
        // - outputs/logs, para no corromper los ficheros de Logback (Java)
        //   ni los logs en caliente montados por el volumen de NiFi.
        // - outputs/traces/.last_execution_id, porque conserva el contador
        //   secuencial de ejecuciones entre invocaciones.
        // - outputs/state, porque contiene el cursor incremental persistente.
        //   Protegido también programáticamente por PROTECTED_DIRECTORIES.
        Path normalizedRoot = outputsRoot.toAbsolutePath().normalize();
        Path lastExecutionIdFile = normalizedRoot.resolve(Paths.get("traces", ".last_execution_id")).normalize();
        cleanDirectory(normalizedRoot, normalizedRoot.resolve("traces"), lastExecutionIdFile);
        cleanDirectory(normalizedRoot, normalizedRoot.resolve("errors"), lastExecutionIdFile);
        cleanDirectory(normalizedRoot, normalizedRoot.resolve("flows"), lastExecutionIdFile);
    }

    private static void cleanDirectory(Path outputsRoot, Path directory, Path lastExecutionIdFile) {
        if (!Files.exists(directory)) {
            return;
        }

        // Guardrail programático: impide limpiar directorios protegidos.
        Path relativePath = outputsRoot.relativize(directory.toAbsolutePath().normalize());
        String topLevelDir = relativePath.getName(0).toString().toLowerCase(java.util.Locale.ROOT);
        if (PROTECTED_DIRECTORIES.contains(topLevelDir)) {
            LoggerFactory.getLogger(OutputCleaner.class)
                    .warn("Intento de limpiar directorio protegido '{}' bloqueado por guardrail. No se elimina nada.", directory);
            return;
        }

        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                .forEach(file -> {
                    if (shouldPreserve(file, lastExecutionIdFile)) {
                        return;
                    }
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        LoggerFactory.getLogger(OutputCleaner.class)
                                .warn("No se pudo eliminar el archivo (puede estar en uso): {}", file);
                    }
                });
        } catch (IOException e) {
            LoggerFactory.getLogger(OutputCleaner.class)
                    .error("Error intentando rastrear el directorio {} para su limpieza.", directory, e);
        }
    }

    private static boolean shouldPreserve(Path file, Path lastExecutionIdFile) {
        return file.toAbsolutePath().normalize().equals(lastExecutionIdFile);
    }
}

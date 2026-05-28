package es.upm.tfg.topomigrator.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class OutputCleaner {
    private static final Logger log = LoggerFactory.getLogger(OutputCleaner.class);
    static final Set<String> PROTECTED_DIRECTORIES = Set.of("state", "logs");

    public static void cleanOutputs() {
        cleanOutputs(Paths.get("outputs"));
    }

    static void cleanOutputs(Path outputsRoot) {
        Path normalizedRoot = outputsRoot.toAbsolutePath().normalize();
        Path lastExecutionIdFile = normalizedRoot.resolve(Paths.get("traces", ".last_execution_id")).normalize();
        cleanDirectory(normalizedRoot, normalizedRoot.resolve("traces"), lastExecutionIdFile);
        cleanDirectory(normalizedRoot, normalizedRoot.resolve("errors"), lastExecutionIdFile);
    }

    private static void cleanDirectory(Path outputsRoot, Path directory, Path lastExecutionIdFile) {
        if (!Files.exists(directory)) {
            return;
        }

        Path relativePath = outputsRoot.relativize(directory.toAbsolutePath().normalize());
        String topLevelDir = relativePath.getName(0).toString().toLowerCase(Locale.ROOT);
        if (PROTECTED_DIRECTORIES.contains(topLevelDir)) {
            log.warn("Intento de limpiar directorio protegido '{}' bloqueado. No se elimina nada.", directory);
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
                        log.warn("No se pudo eliminar el archivo: {}", file);
                    }
                });
        } catch (IOException e) {
            log.error("Error intentando rastrear el directorio {} para su limpieza.", directory, e);
        }
    }

    private static boolean shouldPreserve(Path file, Path lastExecutionIdFile) {
        return file.toAbsolutePath().normalize().equals(lastExecutionIdFile);
    }
}

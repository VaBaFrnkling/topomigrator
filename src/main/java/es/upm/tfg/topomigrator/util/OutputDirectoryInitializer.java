package es.upm.tfg.topomigrator.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class OutputDirectoryInitializer {
    private static final Path OUTPUTS_ROOT = Paths.get("outputs");
    private static final List<Path> REQUIRED_DIRECTORIES = List.of(
            OUTPUTS_ROOT,
            OUTPUTS_ROOT.resolve("logs"),
            OUTPUTS_ROOT.resolve("errors"),
            OUTPUTS_ROOT.resolve("traces"),
            OUTPUTS_ROOT.resolve(Paths.get("traces", "tables")),
            OUTPUTS_ROOT.resolve("flows"),
            OUTPUTS_ROOT.resolve("state")
    );

    private OutputDirectoryInitializer() {
    }

    public static void ensureOutputDirectories() {
        for (Path directory : REQUIRED_DIRECTORIES) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo crear el directorio de salida " + directory.toAbsolutePath(), e);
            }
        }
    }
}

package es.upm.tfg.topomigrator.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Generador de identificadores de ejecución con formato secuencial legible.
 * <p>
 * Produce IDs del tipo {@code "exec-001"}, {@code "exec-002"}, etc.,
 * auto-incrementándose entre ejecuciones. El último ID utilizado se
 * persiste en un fichero {@code outputs/traces/.last_execution_id}
 * para mantener la secuencia entre múltiples invocaciones del motor.
 * </p>
 * <p>
 * El limpiador de salidas preserva explícitamente este fichero de estado,
 * evitando que la secuencia se reinicie al comienzo de cada ejecución.
 * </p>
 * <p>
 * Para cada tabla procesada dentro de una ejecución, se genera un
 * sub-identificador con el formato {@code "exec-001-t1"}, {@code "exec-001-t2"}, etc.
 * </p>
 */
public class ExecutionIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionIdGenerator.class);

    /** Prefijo utilizado para todos los IDs de ejecución. */
    private static final String EXECUTION_PREFIX = "exec-";

    /** Fichero que almacena el último número de ejecución utilizado. */
    private static final Path LAST_ID_FILE = Paths.get("outputs", "traces", ".last_execution_id");

    /** Número de la ejecución actual (1, 2, 3...). */
    private final int executionNumber;

    /** ID base formateado: "exec-001", "exec-002", etc. */
    private final String executionId;

    /**
     * Crea un nuevo generador, leyendo el último número de ejecución
     * persistido y auto-incrementándolo.
     */
    public ExecutionIdGenerator() {
        this.executionNumber = readLastExecutionNumber() + 1;
        this.executionId = EXECUTION_PREFIX + String.format("%03d", executionNumber);
        persistCurrentNumber();
        log.info("ID de ejecución generado: {}", executionId);
    }

    /**
     * Devuelve el ID base de la ejecución actual.
     * Ejemplo: {@code "exec-001"}.
     *
     * @return El identificador de ejecución.
     */
    public String getExecutionId() {
        return executionId;
    }

    /**
     * Genera el ID específico para una tabla según su orden de ejecución.
     * Ejemplo: para la tabla número 3 → {@code "exec-001-t3"}.
     *
     * @param tableOrder El número de orden de la tabla (empezando en 1).
     * @return El identificador de ejecución de la tabla.
     */
    public String getTableExecutionId(int tableOrder) {
        return executionId + "-t" + tableOrder;
    }

    /**
     * Lee el último número de ejecución del fichero de persistencia.
     * Si el fichero no existe o es ilegible, devuelve 0.
     */
    private int readLastExecutionNumber() {
        try {
            if (Files.exists(LAST_ID_FILE)) {
                String content = Files.readString(LAST_ID_FILE).trim();
                return Integer.parseInt(content);
            }
        } catch (IOException | NumberFormatException e) {
            log.warn("No se pudo leer el último ID de ejecución ({}). Se reiniciará la secuencia.", e.getMessage());
        }
        return 0;
    }

    /**
     * Persiste el número de ejecución actual en el fichero para la próxima invocación.
     */
    private void persistCurrentNumber() {
        try {
            Path parentDir = LAST_ID_FILE.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            Files.writeString(LAST_ID_FILE, String.valueOf(executionNumber));
        } catch (IOException e) {
            log.warn("No se pudo persistir el ID de ejecución actual: {}", e.getMessage());
        }
    }
}

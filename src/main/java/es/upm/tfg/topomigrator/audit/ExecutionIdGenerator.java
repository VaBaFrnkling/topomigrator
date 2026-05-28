package es.upm.tfg.topomigrator.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ExecutionIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionIdGenerator.class);

    private static final String EXECUTION_PREFIX = "exec-";
    private static final Path LAST_ID_FILE = Paths.get("outputs", "traces", ".last_execution_id");
    private final int executionNumber;
    private final String executionId;

    public ExecutionIdGenerator() {
        this.executionNumber = readLastExecutionNumber() + 1;
        this.executionId = EXECUTION_PREFIX + String.format("%03d", executionNumber);
        persistCurrentNumber();
        log.info("ID de ejecución generado: {}", executionId);
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getTableExecutionId(int tableOrder) {
        return executionId + "-t" + tableOrder;
    }

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

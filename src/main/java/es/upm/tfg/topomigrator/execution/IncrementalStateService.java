package es.upm.tfg.topomigrator.execution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.util.SchemaTableIdentifierUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persiste el cursor incremental entre ejecuciones.
 *
 * Sin este estado, una migración incremental vuelve siempre a startValue y puede
 * reinsertar filas ya migradas. El estado se guarda fuera de outputs/traces para
 * que OutputCleaner no lo elimine al inicio de cada ejecución.
 */
public class IncrementalStateService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalStateService.class);
    private static final Type STATE_MAP_TYPE = new TypeToken<Map<String, IncrementalState>>() {}.getType();
    private static final String STATUS_SUCCESS = "SUCCESS";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path stateFile;

    public IncrementalStateService() {
        String configuredPath = System.getenv("INCREMENTAL_STATE_PATH");
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            configuredPath = Paths.get("outputs", "state", "incremental-state.json").toString();
        }
        this.stateFile = normalizeStateFile(Paths.get(configuredPath));
        initializeDirectory();
    }

    IncrementalStateService(Path stateFile) {
        this.stateFile = normalizeStateFile(stateFile);
        initializeDirectory();
    }

    public Path getStateFile() {
        return stateFile;
    }

    public synchronized IncrementalState getState(String stateKey) {
        return loadAllStates().get(stateKey);
    }

    public synchronized void saveSuccessfulBatch(
            String stateKey,
            TableMigration tableConfig,
            String previousValue,
            String newLastProcessedValue,
            String executionId,
            String tableExecutionId,
            long recordsProcessed) {

        if (newLastProcessedValue == null || newLastProcessedValue.trim().isEmpty()) {
            log.info("No se actualiza el estado incremental de {} porque no se obtuvo un nuevo cursor.", stateKey);
            return;
        }

        Map<String, IncrementalState> states = loadAllStates();
        IncrementalState state = new IncrementalState();
        state.stateKey = stateKey;
        state.sourceTable = SchemaTableIdentifierUtils.toQualifiedIdentifier(
                tableConfig.getSource().getSchema(),
                tableConfig.getSource().getTable()
        );
        state.targetTable = SchemaTableIdentifierUtils.toQualifiedIdentifier(
                tableConfig.getTarget().getSchema(),
                tableConfig.getTarget().getTable()
        );
        state.column = tableConfig.getIncrementalConfig().getColumn();
        state.previousProcessedValue = previousValue;
        state.lastProcessedValue = newLastProcessedValue;
        state.batchSize = tableConfig.getIncrementalConfig().getBatchSize();
        state.executionStatus = STATUS_SUCCESS;
        state.executionId = executionId;
        state.tableExecutionId = tableExecutionId;
        state.recordsProcessed = recordsProcessed;
        state.updatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        states.put(stateKey, state);
        writeAllStates(states);
        log.info("Estado incremental actualizado para {}: {} -> {}", stateKey, previousValue, newLastProcessedValue);
    }

    public String buildStateKey(TableMigration tableConfig) {
        return SchemaTableIdentifierUtils.toQualifiedIdentifier(
                tableConfig.getTarget().getSchema(),
                tableConfig.getTarget().getTable()
        ).toLowerCase(Locale.ROOT);
    }

    private Path normalizeStateFile(Path stateFile) {
        return stateFile.toAbsolutePath().normalize();
    }

    private void initializeDirectory() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo crear el directorio de estado incremental: " + stateFile.getParent(), e);
        }
    }

    private Map<String, IncrementalState> loadAllStates() {
        if (!Files.exists(stateFile)) {
            return new LinkedHashMap<>();
        }

        try (Reader reader = Files.newBufferedReader(stateFile)) {
            Map<String, IncrementalState> states = gson.fromJson(reader, STATE_MAP_TYPE);
            return states != null ? states : new LinkedHashMap<>();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el estado incremental desde " + stateFile + ": " + e.getMessage(), e);
        }
    }

    private void writeAllStates(Map<String, IncrementalState> states) {
        initializeDirectory();
        Path tmpFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmpFile)) {
            gson.toJson(states, writer);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo escribir el estado incremental temporal " + tmpFile + ": " + e.getMessage(), e);
        }

        try {
            Files.move(tmpFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveError) {
            try {
                Files.move(tmpFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                throw new IllegalStateException("No se pudo publicar el estado incremental en " + stateFile + ": " + moveError.getMessage(), moveError);
            }
        }
    }

    public static class IncrementalState {
        public String stateKey;
        public String sourceTable;
        public String targetTable;
        public String column;
        public String previousProcessedValue;
        public String lastProcessedValue;
        public Integer batchSize;
        public String executionStatus;
        public String executionId;
        public String tableExecutionId;
        public Long recordsProcessed;
        public String updatedAt;
    }
}

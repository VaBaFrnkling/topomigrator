package es.upm.tfg.topomigrator.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resultado global de la ejecución de una migración.
 * Permite distinguir entre éxito completo, fallos y bloqueos por dependencias.
 */
public class MigrationExecutionResult {

    private final String executionId;
    private final List<String> successfulTables = new ArrayList<>();
    private final List<String> failedTables = new ArrayList<>();
    private final List<String> blockedTables = new ArrayList<>();

    public MigrationExecutionResult(String executionId) {
        this.executionId = executionId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public List<String> getSuccessfulTables() {
        return Collections.unmodifiableList(successfulTables);
    }

    public List<String> getFailedTables() {
        return Collections.unmodifiableList(failedTables);
    }

    public List<String> getBlockedTables() {
        return Collections.unmodifiableList(blockedTables);
    }

    public void addSuccessfulTable(String tableName) {
        if (tableName != null && !successfulTables.contains(tableName)) {
            successfulTables.add(tableName);
        }
    }

    public void addFailedTable(String tableName) {
        if (tableName != null && !failedTables.contains(tableName)) {
            failedTables.add(tableName);
        }
    }

    public void addBlockedTable(String tableName) {
        if (tableName != null && !blockedTables.contains(tableName)) {
            blockedTables.add(tableName);
        }
    }

    public boolean isSuccessful() {
        return failedTables.isEmpty() && blockedTables.isEmpty();
    }

    public boolean hasFailures() {
        return !failedTables.isEmpty();
    }

    public boolean hasBlockedTables() {
        return !blockedTables.isEmpty();
    }
}

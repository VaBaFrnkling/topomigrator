package es.upm.tfg.topomigrator.audit;

import java.util.List;
import java.util.Map;

public class TableTrace {
    public String executionId;
    public String tableExecutionId;
    public TableMapping table;
    public IncrementalInfo incrementalInfo;
    public String migrationType;
    public String status;
    public CleanupInfo cleanup;
    public long recordsProcessed;
    public Timing timing;
    public Map<String, Object> filters;
    public List<String> errors;
    public AuditMetrics auditMetrics;

    public static class TableMapping {
        public SchemaTable source;
        public SchemaTable target;
    }

    public static class SchemaTable {
        public String schema;
        public String name;
    }

    public static class IncrementalInfo {
        public String column;
        public String previousValue;
        public String lastProcessedValue;
        public boolean stateUpdated;
    }

    public static class CleanupInfo {
        public String status;
        public String phase;
        public String processGroupId;
        public String message;
    }

    public static class AuditMetrics {
        public Long sourceSelectedRecords;
        public Long targetRowsBefore;
        public Long targetRowsAfter;
        public Long targetNetDelta;
        public String consistencyStatus;
        public String outcome;
        public List<String> warnings;
    }
}

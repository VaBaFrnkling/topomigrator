package es.upm.tfg.topomigrator.audit;

import java.util.List;
import java.util.Map;

public class TableTrace {
    public String executionId;
    public String tableExecutionId;
    public TableMapping table;
    public IncrementalInfo incrementalInfo;
    public int executionOrder;
    public String migrationType;
    public String status;
    public long recordsProcessed;
    public Timing timing;
    public Map<String, Object> filters;
    public Map<String, Object> transformations;
    public Map<String, Object> validations;
    public List<String> errors;

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
        public String lastProcessedValue;
    }
}

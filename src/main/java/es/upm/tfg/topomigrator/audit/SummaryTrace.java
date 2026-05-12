package es.upm.tfg.topomigrator.audit;

import java.util.List;
import java.util.Map;

public class SummaryTrace {
    public MigrationInfo migration;
    public String executionId;
    public Timing timing;
    public List<String> executionOrder;
    public TablesSummary tables;
    public DependencyAnalysis dependencyAnalysis;
    public List<FailedTable> failedTables;
    public List<String> blockedTables;
    public List<TableExecutionSummary> tableExecutionDetails;

    public static class MigrationInfo {
        public String name;
        public String description;
        public String version;
        public String executedBy;
    }

    public static class TablesSummary {
        public int total;
        public int successful;
        public int failed;
        public int blocked;
    }

    public static class DependencyAnalysis {
        public List<String> independentTables;
        public Map<String, List<String>> dependentTables;
    }

    public static class FailedTable {
        public String table;
        public String reason;
    }

    public static class TableExecutionSummary {
        public String table;
        public String executionId;
        public int order;
        public String status;
        public long records;
        public long durationMs;
        public Long sourceSelectedRecords;
        public Long targetNetDeltaRecords;
        public String auditConsistencyStatus;
    }
}

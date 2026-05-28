package es.upm.tfg.topomigrator.model;

import java.util.List;

public class IncrementalConfig {

    private String column;
    private String type;
    private String startValue;
    private Integer batchSize;
    private String loadStrategy;
    private List<String> idempotencyKeyColumns;

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStartValue() {
        return startValue;
    }

    public void setStartValue(String startValue) {
        this.startValue = startValue;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public String getLoadStrategy() {
        return loadStrategy;
    }

    public void setLoadStrategy(String loadStrategy) {
        this.loadStrategy = loadStrategy;
    }

    public List<String> getIdempotencyKeyColumns() {
        return idempotencyKeyColumns;
    }

    public void setIdempotencyKeyColumns(List<String> idempotencyKeyColumns) {
        this.idempotencyKeyColumns = idempotencyKeyColumns;
    }
}

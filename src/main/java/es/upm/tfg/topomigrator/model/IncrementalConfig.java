package es.upm.tfg.topomigrator.model;

/**
 * Configuración para migraciones incrementales: columna de referencia y valor
 * inicial.
 */
public class IncrementalConfig {

    private String column;
    private String type;
    private String startValue;
    private Integer batchSize;

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
}

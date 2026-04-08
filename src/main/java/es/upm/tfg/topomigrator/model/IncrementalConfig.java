package es.upm.tfg.topomigrator.model;

/**
 * Configuración para migraciones incrementales: columna de referencia y valor
 * inicial.
 */
public class IncrementalConfig {

    private String column;
    private String startValue;

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getStartValue() {
        return startValue;
    }

    public void setStartValue(String startValue) {
        this.startValue = startValue;
    }
}

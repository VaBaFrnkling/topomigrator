package es.upm.tfg.topomigrator.model;

import java.util.Map;

/**
 * Reglas de transformación aplicadas a las columnas durante la migración.
 */
public class TransformationConfig {

    private Map<String, ColumnTransformation> columns;

    public Map<String, ColumnTransformation> getColumns() {
        return columns;
    }

    public void setColumns(Map<String, ColumnTransformation> columns) {
        this.columns = columns;
    }
}

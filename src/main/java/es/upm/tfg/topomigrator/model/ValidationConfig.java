package es.upm.tfg.topomigrator.model;

import java.util.Map;

/**
 * Reglas de validación a nivel de tabla y de columna.
 */
public class ValidationConfig {

    private Boolean rowCountCheck;
    private Boolean foreignKeyChecks;
    private Map<String, ColumnValidation> columns;

    public Boolean getRowCountCheck() {
        return rowCountCheck;
    }

    public void setRowCountCheck(Boolean rowCountCheck) {
        this.rowCountCheck = rowCountCheck;
    }

    public Boolean getForeignKeyChecks() {
        return foreignKeyChecks;
    }

    public void setForeignKeyChecks(Boolean foreignKeyChecks) {
        this.foreignKeyChecks = foreignKeyChecks;
    }

    public Map<String, ColumnValidation> getColumns() {
        return columns;
    }

    public void setColumns(Map<String, ColumnValidation> columns) {
        this.columns = columns;
    }
}

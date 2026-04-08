package es.upm.tfg.topomigrator.model;

/**
 * Reglas de validación aplicables a una columna concreta.
 */
public class ColumnValidation {

    private Boolean notNull;
    private Boolean unique;

    public Boolean getNotNull() {
        return notNull;
    }

    public void setNotNull(Boolean notNull) {
        this.notNull = notNull;
    }

    public Boolean getUnique() {
        return unique;
    }

    public void setUnique(Boolean unique) {
        this.unique = unique;
    }
}

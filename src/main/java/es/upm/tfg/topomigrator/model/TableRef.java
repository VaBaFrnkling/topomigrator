package es.upm.tfg.topomigrator.model;

/**
 * Referencia a una tabla en una base de datos (esquema + nombre de tabla).
 */
public class TableRef {

    private String schema;
    private String table;

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    @Override
    public String toString() {
        return schema + "." + table;
    }
}

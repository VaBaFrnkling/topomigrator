package es.upm.tfg.topomigrator.model;

/**
 * Filtros SQL opcionales para limitar los datos migrados.
 */
public class FilterConfig {

    private String where;

    public String getWhere() {
        return where;
    }

    public void setWhere(String where) {
        this.where = where;
    }
}

package es.upm.tfg.topomigrator.model;

public class TableMigration {

    private TableRef source;
    private TableRef target;
    private boolean enabled;
    private String migrationType;
    private IncrementalConfig incrementalConfig;
    private FilterConfig filters;

    public TableRef getSource() {
        return source;
    }

    public void setSource(TableRef source) {
        this.source = source;
    }

    public TableRef getTarget() {
        return target;
    }

    public void setTarget(TableRef target) {
        this.target = target;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMigrationType() {
        return migrationType;
    }

    public void setMigrationType(String migrationType) {
        this.migrationType = migrationType;
    }

    public IncrementalConfig getIncrementalConfig() {
        return incrementalConfig;
    }

    public void setIncrementalConfig(IncrementalConfig incrementalConfig) {
        this.incrementalConfig = incrementalConfig;
    }

    public FilterConfig getFilters() {
        return filters;
    }

    public void setFilters(FilterConfig filters) {
        this.filters = filters;
    }

    @Override
    public String toString() {
        return "TableMigration{source=" + source + ", target=" + target
                + ", enabled=" + enabled + ", migrationType='" + migrationType + "'}";
    }
}


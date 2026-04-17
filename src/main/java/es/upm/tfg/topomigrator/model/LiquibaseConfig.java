package es.upm.tfg.topomigrator.model;

/**
 * Representa la configuración de Liquibase definida en contract.yaml.
 */
public class LiquibaseConfig {
    private String changelog;
    private String contexts;

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public String getContexts() {
        return contexts;
    }

    public void setContexts(String contexts) {
        this.contexts = contexts;
    }

    @Override
    public String toString() {
        return "LiquibaseConfig{changelog='" + changelog + "', contexts='" + contexts + "'}";
    }
}

package es.upm.tfg.topomigrator.model;

/**
 * Configuración de Liquibase para el control de cambios de esquema.
 */
public class LiquibaseConfig {

    private String changelog;

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }
}

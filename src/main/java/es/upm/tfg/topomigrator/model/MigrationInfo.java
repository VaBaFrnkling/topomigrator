package es.upm.tfg.topomigrator.model;

/**
 * Metadatos generales del contrato de migración.
 */
public class MigrationInfo {

    private String name;
    private String description;
    private String version;
    private String author;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    @Override
    public String toString() {
        return "MigrationInfo{name='" + name + "', version='" + version + "', author='" + author + "'}";
    }
}

package es.upm.tfg.topomigrator.model;

/**
 * Configuración de las conexiones a las bases de datos origen y destino.
 */
public class DatabaseConfig {

    private ConnectionConfig sourceConnection;
    private ConnectionConfig targetConnection;

    public ConnectionConfig getSourceConnection() {
        return sourceConnection;
    }

    public void setSourceConnection(ConnectionConfig sourceConnection) {
        this.sourceConnection = sourceConnection;
    }

    public ConnectionConfig getTargetConnection() {
        return targetConnection;
    }

    public void setTargetConnection(ConnectionConfig targetConnection) {
        this.targetConnection = targetConnection;
    }
}

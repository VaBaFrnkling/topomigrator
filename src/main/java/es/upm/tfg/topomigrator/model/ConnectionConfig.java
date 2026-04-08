package es.upm.tfg.topomigrator.model;

/**
 * Parámetros de conexión JDBC a una base de datos.
 */
public class ConnectionConfig {

    private String driver;
    private String jdbcUrl;
    private String username;
    private String password;

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "ConnectionConfig{jdbcUrl='" + jdbcUrl + "', username='" + username + "'}";
    }
}

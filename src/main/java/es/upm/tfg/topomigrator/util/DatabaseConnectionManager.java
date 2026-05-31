package es.upm.tfg.topomigrator.util;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionManager.class);

    private DatabaseConnectionManager() {
    }

    public static Connection getConnection(ConnectionConfig config) throws SQLException {
        if (config == null || config.getJdbcUrl() == null || config.getJdbcUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("La configuracion JDBC es nula o no contiene URL.");
        }

        String jdbcUrl = config.getJdbcUrl().trim();
        String driver = config.getDriver();
        if (driver != null && !driver.trim().isEmpty()) {
            try {
                Class.forName(driver.trim());
                log.debug("Driver de base de datos cargado correctamente: {}", driver);
            } catch (ClassNotFoundException e) {
                log.error("No se encontro el driver especificado: {}", driver, e);
                throw new SQLException("Driver clase no encontrada: " + driver, e);
            }
        }

        log.info("Intentando conectar a la base de datos via JDBC: {}", redactSecrets(jdbcUrl));

        Connection connection;
        String user = config.getUsername();
        String pass = config.getPassword();

        if (user != null && !user.isEmpty()) {
            connection = DriverManager.getConnection(jdbcUrl, user, pass);
        } else {
            connection = DriverManager.getConnection(jdbcUrl);
        }

        log.info("Conexion JDBC establecida correctamente.");

        return connection;
    }

    public static void testConnection(ConnectionConfig config, String label) {
        log.info("Verificando conectividad con {}...", label);
        try (Connection conn = getConnection(config)) {
            if (conn != null && !conn.isClosed()) {
                log.info("Conexion a {} verificada correctamente.", label);
            }
        } catch (SQLException | IllegalArgumentException e) {
            String safeMessage = redactSecrets(e.getMessage());
            log.error("No se pudo conectar a {}: {}", label, safeMessage);
            throw new RuntimeException("Fallo en la prueba de conexion a " + label + ": " + safeMessage, e);
        }
    }

    private static String redactSecrets(String value) {
        if (value == null) {
            return null;
        }
        String redacted = value.replaceAll("(?i)(password|passwd|pwd)=([^;&\\s]+)", "$1=***");
        redacted = redacted.replaceAll("(?i)(password|passwd|pwd):([^@\\s]+)", "$1:***");
        return redacted.replaceAll("(?i)(//[^:/\\s]+:)([^@/\\s]+)(@)", "$1***$3");
    }
}

package es.upm.tfg.topomigrator.util;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Gestor de conexiones JDBC.
 * Permite establecer conexiones nativas a bases de datos relacionales
 * utilizando la configuración extraída del contrato y las variables de entorno.
 */
public class DatabaseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionManager.class);

    private DatabaseConnectionManager() {
    }

    /**
     * Establece y devuelve una nueva conexión JDBC pura basada en el
     * ConnectionConfig.
     *
     * @param config Configuración de conexión (URL, usuario, contraseña, driver)
     * @return java.sql.Connection Lista para ejecutar queries.
     * @throws SQLException Si falla la conexión a la base de datos
     */
    public static Connection getConnection(ConnectionConfig config) throws SQLException {
        if (config == null || config.getJdbcUrl() == null || config.getJdbcUrl().isEmpty()) {
            throw new IllegalArgumentException("La configuración JDBC es nula o no contiene URL.");
        }

        String driver = config.getDriver();
        if (driver != null && !driver.isEmpty()) {
            try {
                Class.forName(driver);
                log.debug("Driver de base de datos cargado correctamente: {}", driver);
            } catch (ClassNotFoundException e) {
                log.error("No se encontró el driver especificado: {}", driver, e);
                // Lanzamos SQLException para mantener la firma del método simple
                throw new SQLException("Driver clase no encontrada: " + driver, e);
            }
        }

        // 2. Intentar establecer la conexión
        log.info("Intentando conectar a la base de datos a través de JDBC: {}", config.getJdbcUrl());

        Connection connection;
        String user = config.getUsername();
        String pass = config.getPassword();

        if (user != null && !user.isEmpty()) {
            connection = DriverManager.getConnection(config.getJdbcUrl(), user, pass);
        } else {
            connection = DriverManager.getConnection(config.getJdbcUrl());
        }

        log.info("¡Conexión exitosa!");

        return connection;
    }

    /**
     * Verifica que la conexión a la base de datos es accesible.
     * Abre y cierra una conexión JDBC para validar la conectividad previa a la migración.
     *
     * @param config Configuración de conexión a verificar.
     * @param label  Etiqueta descriptiva para los logs (e.g., "Base de Datos Origen").
     * @throws RuntimeException si la conexión no se puede establecer.
     */
    public static void testConnection(ConnectionConfig config, String label) {
        log.info("Verificando conectividad con {}...", label);
        try (Connection conn = getConnection(config)) {
            if (conn != null && !conn.isClosed()) {
                log.info("✔ Conexión a {} verificada correctamente.", label);
            }
        } catch (SQLException e) {
            log.error("✘ No se pudo conectar a {}: {}", label, e.getMessage());
            throw new RuntimeException("Fallo en la prueba de conexión a " + label + ": " + e.getMessage(), e);
        }
    }
}

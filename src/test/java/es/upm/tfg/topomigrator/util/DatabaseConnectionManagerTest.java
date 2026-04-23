package es.upm.tfg.topomigrator.util;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import junit.framework.TestCase;

import java.sql.SQLException;

/**
 * Tests para {@link DatabaseConnectionManager}.
 * Cubre todas las guardas de validación de entrada
 * (config nula, URL nula/vacía, driver inexistente).
 * NO se prueban conexiones reales (requieren BD activa).
 */
public class DatabaseConnectionManagerTest extends TestCase {

    // ═══════════════════════════════════════════════════════════════════
    //  VALIDACIÓN DE CONFIG NULA
    // ═══════════════════════════════════════════════════════════════════

    /** Config nula lanza IllegalArgumentException. */
    public void testNullConfigThrows() {
        try {
            DatabaseConnectionManager.getConnection(null);
            fail("Se esperaba IllegalArgumentException por config nula.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("nula"));
        } catch (SQLException e) {
            fail("Se esperaba IllegalArgumentException, no SQLException.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  VALIDACIÓN DE URL JDBC
    // ═══════════════════════════════════════════════════════════════════

    /** URL nula lanza IllegalArgumentException. */
    public void testNullJdbcUrlThrows() {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl(null);
        try {
            DatabaseConnectionManager.getConnection(config);
            fail("Se esperaba IllegalArgumentException por URL nula.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("nula") || e.getMessage().contains("URL"));
        } catch (SQLException e) {
            fail("Se esperaba IllegalArgumentException, no SQLException.");
        }
    }

    /** URL vacía lanza IllegalArgumentException. */
    public void testEmptyJdbcUrlThrows() {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl("");
        try {
            DatabaseConnectionManager.getConnection(config);
            fail("Se esperaba IllegalArgumentException por URL vacía.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("nula") || e.getMessage().contains("URL"));
        } catch (SQLException e) {
            fail("Se esperaba IllegalArgumentException, no SQLException.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  VALIDACIÓN DE DRIVER
    // ═══════════════════════════════════════════════════════════════════

    /** Un driver inexistente lanza SQLException con cause ClassNotFoundException. */
    public void testInvalidDriverThrowsSQLException() {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl("jdbc:fake://localhost/test");
        config.setDriver("com.fake.driver.NoExiste");
        try {
            DatabaseConnectionManager.getConnection(config);
            fail("Se esperaba SQLException por driver inexistente.");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("Driver"));
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        }
    }

    /** Si no se especifica driver (null), no intenta cargarlo. */
    public void testNullDriverDoesNotThrowClassNotFound() {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl("jdbc:fake://localhost/test");
        config.setDriver(null);
        try {
            DatabaseConnectionManager.getConnection(config);
            // Fallará por URL inválida (no hay driver registrado para jdbc:fake),
            // pero NO por ClassNotFoundException
            fail("Se esperaba SQLException por URL no resuelta.");
        } catch (SQLException e) {
            // Se espera error de conexión, NO de driver
            assertFalse("No debería ser ClassNotFoundException",
                    e.getCause() instanceof ClassNotFoundException);
        }
    }

    /** Si el driver es cadena vacía, no intenta cargarlo. */
    public void testEmptyDriverDoesNotThrowClassNotFound() {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl("jdbc:fake://localhost/test");
        config.setDriver("");
        try {
            DatabaseConnectionManager.getConnection(config);
            fail("Se esperaba SQLException por URL no resuelta.");
        } catch (SQLException e) {
            assertFalse("No debería ser ClassNotFoundException",
                    e.getCause() instanceof ClassNotFoundException);
        }
    }

    /** Driver válido (PostgreSQL) se carga sin error de ClassNotFoundException. */
    public void testValidPostgresDriverLoadsSuccessfully() {
        ConnectionConfig config = new ConnectionConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:9999/no_existe");
        config.setDriver("org.postgresql.Driver");
        try {
            DatabaseConnectionManager.getConnection(config);
            // Fallará por conexión (no hay BD), pero el driver se cargó bien
            fail("Se esperaba SQLException de conexión (no de driver).");
        } catch (SQLException e) {
            // El error debe ser de conexión, no de ClassNotFoundException
            assertFalse("El driver debería haberse cargado correctamente",
                    e.getCause() instanceof ClassNotFoundException);
        }
    }
}

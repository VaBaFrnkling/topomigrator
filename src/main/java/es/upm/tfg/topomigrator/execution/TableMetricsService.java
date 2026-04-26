package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.regex.Pattern;

/**
 * Servicio para obtener métricas reales de tablas destino.
 * Se usa para auditar filas procesadas sin depender de bytes de NiFi.
 */
public class TableMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(TableMetricsService.class);
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public long countRows(ConnectionConfig connectionConfig, String schema, String table) {
        String safeSchema = requireSqlIdentifier(defaultSchema(schema), "target.schema");
        String safeTable = requireSqlIdentifier(table, "target.table");
        String sql = "SELECT COUNT(*) FROM " + safeSchema + "." + safeTable;

        try (Connection connection = DatabaseConnectionManager.getConnection(connectionConfig);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (Exception e) {
            throw new RuntimeException("No se pudo contar filas de la tabla destino " + safeSchema + "." + safeTable, e);
        }
    }

    private String defaultSchema(String schema) {
        return (schema == null || schema.trim().isEmpty()) ? "public" : schema.trim();
    }

    private String requireSqlIdentifier(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("El identificador SQL " + fieldName + " está vacío.");
        }
        String trimmed = value.trim();
        if (!SQL_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalStateException("El identificador SQL " + fieldName + " contiene caracteres no permitidos: " + trimmed);
        }
        return trimmed;
    }
}

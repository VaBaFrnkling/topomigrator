package es.upm.tfg.topomigrator.exceptions;

/**
 * Excepción lanzada cuando hay incompatibilidades de esquema entre las tablas
 * de origen y destino (ej. diferentes columnas, tablas inexistentes).
 */
public class SchemaCompatibilityException extends RuntimeException {

    public SchemaCompatibilityException(String message) {
        super(message);
    }

    public SchemaCompatibilityException(String message, Throwable cause) {
        super(message, cause);
    }
}

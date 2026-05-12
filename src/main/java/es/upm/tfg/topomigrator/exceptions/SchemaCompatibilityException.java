package es.upm.tfg.topomigrator.exceptions;

/**
 * Excepción lanzada cuando falla la validación mínima de compatibilidad de mapeo
 * entre origen y destino: tablas inexistentes, tablas no accesibles o columnas
 * de origen sin correspondencia directa por nombre en destino.
 */
public class SchemaCompatibilityException extends RuntimeException {

    public SchemaCompatibilityException(String message) {
        super(message);
    }

    public SchemaCompatibilityException(String message, Throwable cause) {
        super(message, cause);
    }
}

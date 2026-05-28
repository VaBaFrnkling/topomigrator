package es.upm.tfg.topomigrator.exceptions;

public class SchemaCompatibilityException extends RuntimeException {

    public SchemaCompatibilityException(String message) {
        super(message);
    }

    public SchemaCompatibilityException(String message, Throwable cause) {
        super(message, cause);
    }
}

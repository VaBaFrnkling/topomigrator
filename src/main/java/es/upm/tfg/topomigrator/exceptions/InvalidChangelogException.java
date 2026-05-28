package es.upm.tfg.topomigrator.exceptions;

public class InvalidChangelogException extends RuntimeException {

    public InvalidChangelogException(String message) {
        super(message);
    }

    public InvalidChangelogException(String message, Throwable cause) {
        super(message, cause);
    }
}

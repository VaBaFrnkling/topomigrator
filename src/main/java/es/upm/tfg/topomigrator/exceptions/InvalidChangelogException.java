package es.upm.tfg.topomigrator.exceptions;

/**
 * Excepción lanzada cuando ocurre un error de validación o parseo
 * en los ficheros Liquibase (changelogs) proporcionados por el usuario
 * para las tablas destino.
 */
public class InvalidChangelogException extends RuntimeException {

    public InvalidChangelogException(String message) {
        super(message);
    }

    public InvalidChangelogException(String message, Throwable cause) {
        super(message, cause);
    }
}

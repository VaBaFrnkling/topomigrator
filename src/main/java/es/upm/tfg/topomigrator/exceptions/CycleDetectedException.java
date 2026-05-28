package es.upm.tfg.topomigrator.exceptions;

public class CycleDetectedException extends RuntimeException {
    public CycleDetectedException(String message) {
        super(message);
    }
}

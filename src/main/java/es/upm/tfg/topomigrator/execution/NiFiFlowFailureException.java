package es.upm.tfg.topomigrator.execution;

public class NiFiFlowFailureException extends RuntimeException {
    public NiFiFlowFailureException(String message) {
        super(message);
    }

    public NiFiFlowFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}

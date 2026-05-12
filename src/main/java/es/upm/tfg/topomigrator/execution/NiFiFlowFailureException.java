package es.upm.tfg.topomigrator.execution;

/**
 * Excepción lanzada cuando el orquestador detecta que NiFi ha producido un fallo interno
 * aunque el Process Group ya no tenga hilos activos ni colas pendientes.
 */
public class NiFiFlowFailureException extends RuntimeException {
    public NiFiFlowFailureException(String message) {
        super(message);
    }

    public NiFiFlowFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}

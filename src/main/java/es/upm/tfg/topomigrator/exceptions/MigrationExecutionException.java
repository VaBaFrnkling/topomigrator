package es.upm.tfg.topomigrator.exceptions;

/**
 * Excepción de ejecución con contexto funcional suficiente para trazabilidad y logs.
 * Permite registrar no solo que una tabla ha fallado, sino en qué fase, en qué
 * componente y qué decisión tomó el sistema.
 */
public class MigrationExecutionException extends RuntimeException {

    private final String phase;
    private final String processor;
    private final String action;

    public MigrationExecutionException(String phase, String processor, String message, String action) {
        super(message);
        this.phase = phase;
        this.processor = processor;
        this.action = action;
    }

    public MigrationExecutionException(String phase, String processor, String message, String action, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.processor = processor;
        this.action = action;
    }

    public String getPhase() {
        return phase;
    }

    public String getProcessor() {
        return processor;
    }

    public String getAction() {
        return action;
    }
}

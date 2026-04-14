package es.upm.tfg.topomigrator.exceptions;

/**
 * Excepción lanzada cuando se detecta una dependencia circular en el
 * ordenamiento topológico del grafo de migración. Esto previene
 * bloqueos o un orden de ejecución inválido.
 */
public class CycleDetectedException extends RuntimeException {
    public CycleDetectedException(String message) {
        super(message);
    }
}

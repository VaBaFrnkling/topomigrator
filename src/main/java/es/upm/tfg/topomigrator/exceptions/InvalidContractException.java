package es.upm.tfg.topomigrator.exceptions;

/**
 * Excepción lanzada cuando el contrato de migración (contract.yaml)
 * no cumple con las reglas de validación.
 */
public class InvalidContractException extends RuntimeException {
    public InvalidContractException(String message) {
        super(message);
    }
}

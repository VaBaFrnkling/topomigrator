package es.upm.tfg.topomigrator.util;

import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utilidades para trabajar con el contrato de migración.
 */
public final class MigrationContractUtils {

    private MigrationContractUtils() {
    }

    /**
     * Devuelve una copia del contrato que solo contiene las tablas activas.
     * El resto de capas (validación, DAG, Liquibase, ejecución, auditoría)
     * deben trabajar siempre con este contrato filtrado.
     */
    public static MigrationContract retainEnabledTables(MigrationContract originalContract) {
        if (originalContract == null) {
            throw new InvalidContractException("El contrato proporcionado es nulo.");
        }

        Map<String, TableMigration> originalTables = originalContract.getTables();
        if (originalTables == null || originalTables.isEmpty()) {
            throw new InvalidContractException("El contrato no contiene tablas definidas.");
        }

        LinkedHashMap<String, TableMigration> enabledTables = new LinkedHashMap<>();
        for (Map.Entry<String, TableMigration> entry : originalTables.entrySet()) {
            TableMigration tableMigration = entry.getValue();
            if (tableMigration != null && tableMigration.isEnabled()) {
                enabledTables.put(entry.getKey(), tableMigration);
            }
        }

        if (enabledTables.isEmpty()) {
            throw new InvalidContractException("No hay tablas activas para procesar. Revisa la propiedad 'enabled' del contrato.");
        }

        MigrationContract filteredContract = new MigrationContract();
        filteredContract.setMigration(originalContract.getMigration());
        filteredContract.setDatabase(originalContract.getDatabase());
        filteredContract.setTables(enabledTables);
        return filteredContract;
    }
}

package es.upm.tfg.topomigrator.util;

import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MigrationContractUtils {

    private MigrationContractUtils() {
    }

    public static MigrationContract retainEnabledTables(MigrationContract originalContract) {
        if (originalContract == null) {
            throw new IllegalArgumentException("El contrato no puede ser nulo.");
        }

        MigrationContract filtered = new MigrationContract();
        filtered.setMigration(originalContract.getMigration());
        filtered.setDatabase(originalContract.getDatabase());

        Map<String, TableMigration> enabledTables = new LinkedHashMap<>();
        if (originalContract.getTables() != null) {
            for (Map.Entry<String, TableMigration> entry : originalContract.getTables().entrySet()) {
                TableMigration table = entry.getValue();
                if (table != null && table.isEnabled()) {
                    enabledTables.put(entry.getKey(), table);
                }
            }
        }

        filtered.setTables(enabledTables);
        return filtered;
    }
}

package es.upm.tfg.topomigrator.orchestration.dependency;

import java.util.Objects;
import java.util.Locale;

/**
 * Representa una dependencia de clave foránea entre dos tablas.
 * 
 * Si una tabla 'pedidos' tiene una FK apuntando a 'clientes', 
 * entonces 'clientes' es la tabla padre y 'pedidos' es la tabla dependiente.
 */
public class ForeignKeyDependency {
    private final String parentTable;
    private final String dependentTable;

    public ForeignKeyDependency(String parentTable, String dependentTable) {
        if (parentTable == null || parentTable.isBlank()) {
            throw new IllegalArgumentException("La tabla padre no puede ser nula o vacía");
        }
        if (dependentTable == null || dependentTable.isBlank()) {
            throw new IllegalArgumentException("La tabla dependiente no puede ser nula o vacía");
        }
        this.parentTable = parentTable.trim().toLowerCase(Locale.ROOT);
        this.dependentTable = dependentTable.trim().toLowerCase(Locale.ROOT);
    }

    public String getParentTable() {
        return parentTable;
    }

    public String getDependentTable() {
        return dependentTable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ForeignKeyDependency that = (ForeignKeyDependency) o;
        return parentTable.equals(that.parentTable) && dependentTable.equals(that.dependentTable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parentTable, dependentTable);
    }

    @Override
    public String toString() {
        return "ForeignKeyDependency{" + parentTable + " -> " + dependentTable + "}";
    }
}

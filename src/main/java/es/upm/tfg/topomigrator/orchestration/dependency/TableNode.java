package es.upm.tfg.topomigrator.orchestration.dependency;

import java.util.Locale;
import java.util.Objects;

/**
 * Representa una tabla en el grafo de dependencias de migración.
 */
public class TableNode implements Comparable<TableNode> {
    private final String name;

    public TableNode(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("El nombre de la tabla no puede ser nulo o vacío");
        }
        this.name = name.toLowerCase(Locale.ROOT); // Formato normalizado para resolución
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableNode tableNode = (TableNode) o;
        return name.equals(tableNode.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public int compareTo(TableNode other) {
        // Orden natural: alfabético por nombre, para garantizar una iteración determinista
        return this.name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return "TableNode{" + name + "}";
    }
}

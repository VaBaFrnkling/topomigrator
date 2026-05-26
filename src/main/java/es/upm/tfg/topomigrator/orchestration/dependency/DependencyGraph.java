package es.upm.tfg.topomigrator.orchestration.dependency;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Representa el grafo dirigido acíclico (DAG) de las dependencias entre tablas.
 * Modelado internamente para soportar el algoritmo de Kahn almacenando
 * la lista de adyacencia y el grado de entrada (in-degree).
 */
public class DependencyGraph {

    private final Map<TableNode, Set<TableNode>> adjacencyList;
    private final Map<TableNode, Integer> inDegree;

    public DependencyGraph() {
        this.adjacencyList = new HashMap<>();
        this.inDegree = new HashMap<>();
    }

    /**
     * Añade una tabla al grafo si no existe previamente.
     * Inicializa un conjunto vacío de vecinos y grado de entrada en 0.
     * @param table El nodo de la tabla a añadir.
     */
    public void addTable(TableNode table) {
        adjacencyList.putIfAbsent(table, new TreeSet<>());
        inDegree.putIfAbsent(table, 0);
    }

    /**
     * Añade una arista dirigida desde la tabla padre a la dependiente.
     * Denota que la tabla 'padre' debe terminar de migrar antes de procesar la 'dependiente'.
     *
     * @param parent La tabla padre independiente.
     * @param dependent La tabla que depende de la tabla padre.
     */
    public void addDependency(TableNode parent, TableNode dependent) {
        addTable(parent);
        addTable(dependent);

        if (adjacencyList.get(parent).add(dependent)) {
            inDegree.put(dependent, inDegree.get(dependent) + 1);
        }
    }

    /**
     * Retorna una vista de solo lectura del diccionario de adyacencias.
     */
    public Map<TableNode, Set<TableNode>> getAdjacencyList() {
        return Collections.unmodifiableMap(adjacencyList);
    }

    /**
     * Retorna una vista de solo lectura de los grados de entrada.
     */
    public Map<TableNode, Integer> getInDegree() {
        return Collections.unmodifiableMap(inDegree);
    }
}

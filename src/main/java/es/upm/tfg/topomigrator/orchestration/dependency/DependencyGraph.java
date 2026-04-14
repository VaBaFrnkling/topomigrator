package es.upm.tfg.topomigrator.orchestration.dependency;

import java.util.*;

/**
 * Representa el grafo dirigido acíclico (DAG) de las dependencias entre tablas.
 * Modelado internamente para soportar el algoritmo de Kahn almacenando
 * la lista de adyacencia y el grado de entrada (in-degree).
 */
public class DependencyGraph {

    // Mapa desde una tabla padre hacia el conjunto de tablas que dependen de ella
    private final Map<TableNode, Set<TableNode>> adjacencyList;
    
    // Rastrea el grado de entrada (número de dependencias previas sin resolver) por nodo
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
        adjacencyList.putIfAbsent(table, new TreeSet<>()); // TreeSet asegura iteración determinista
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
        // Aseguramos que existan ambos nodos
        addTable(parent);
        addTable(dependent);

        // Añadimos arista: padre -> dependiente
        if (adjacencyList.get(parent).add(dependent)) {
            // Aumentamos el grado de entrada solo si la arista se añade con éxito (evito duplicados)
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

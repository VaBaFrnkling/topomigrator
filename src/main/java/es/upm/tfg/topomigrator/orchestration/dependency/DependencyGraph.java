package es.upm.tfg.topomigrator.orchestration.dependency;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class DependencyGraph {

    private final Map<TableNode, Set<TableNode>> adjacencyList;
    private final Map<TableNode, Integer> inDegree;

    public DependencyGraph() {
        this.adjacencyList = new HashMap<>();
        this.inDegree = new HashMap<>();
    }

    public void addTable(TableNode table) {
        adjacencyList.putIfAbsent(table, new TreeSet<>());
        inDegree.putIfAbsent(table, 0);
    }

    public void addDependency(TableNode parent, TableNode dependent) {
        addTable(parent);
        addTable(dependent);

        if (adjacencyList.get(parent).add(dependent)) {
            inDegree.put(dependent, inDegree.get(dependent) + 1);
        }
    }

    public Map<TableNode, Set<TableNode>> getAdjacencyList() {
        return Collections.unmodifiableMap(adjacencyList);
    }

    public Map<TableNode, Integer> getInDegree() {
        return Collections.unmodifiableMap(inDegree);
    }
}

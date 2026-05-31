package es.upm.tfg.topomigrator.orchestration.dependency;

import es.upm.tfg.topomigrator.exceptions.CycleDetectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

public class DependencyResolver {

    private static final Logger logger = LoggerFactory.getLogger(DependencyResolver.class);

    public List<TableNode> resolveExecutionOrder(Set<String> includedTables, List<ForeignKeyDependency> dependencies) {
        logger.info("Iniciando resolucion de orden topologico para {} tablas...", includedTables != null ? includedTables.size() : 0);
        DependencyGraph graph = buildGraph(includedTables, dependencies);
        if (graph.getInDegree().isEmpty()) {
            return Collections.emptyList();
        }
        return applyKahnsAlgorithm(graph);
    }

    public BestEffortPlan resolveBestEffortPlan(Set<String> includedTables, List<ForeignKeyDependency> dependencies) {
        logger.info("Iniciando resolucion best-effort de dependencias para {} tablas...", includedTables != null ? includedTables.size() : 0);
        DependencyGraph graph = buildGraph(includedTables, dependencies);
        if (graph.getInDegree().isEmpty()) {
            return new BestEffortPlan(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        KahnResult result = runKahnsAlgorithm(graph);
        if (result.executionOrder.size() == graph.getInDegree().size()) {
            return new BestEffortPlan(result.executionOrder, Collections.emptyList(), Collections.emptyList());
        }

        Set<TableNode> cyclicNodes = findNodesParticipatingInCycles(graph, result.remainingNodes);
        Set<TableNode> blockedNodes = new LinkedHashSet<>(result.remainingNodes);
        blockedNodes.removeAll(cyclicNodes);

        List<TableNode> cyclicTables = sortNodes(cyclicNodes);
        List<TableNode> blockedTables = sortNodes(blockedNodes);

        logger.warn("Plan best-effort generado. Ejecutables={}, ciclo={}, bloqueadas={}",
                result.executionOrder, cyclicTables, blockedTables);

        return new BestEffortPlan(result.executionOrder, cyclicTables, blockedTables);
    }

    private DependencyGraph buildGraph(Set<String> includedTables, List<ForeignKeyDependency> dependencies) {
        if (includedTables == null || includedTables.isEmpty()) {
            logger.warn("El conjunto de tablas incluidas esta vacio o es nulo. Se aborta el calculo.");
            return new DependencyGraph();
        }

        Set<String> normalizedIncluded = includedTables.stream()
                .map(this::normalizeTableId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        DependencyGraph graph = new DependencyGraph();

        for (String tableName : normalizedIncluded) {
            graph.addTable(new TableNode(tableName));
        }

        int validEdges = 0;
        if (dependencies != null) {
            for (ForeignKeyDependency dep : dependencies) {
                if (dep == null) {
                    throw new IllegalArgumentException("La dependencia no puede ser nula.");
                }

                String parentName = normalizeTableId(dep.getParentTable());
                String dependentName = normalizeTableId(dep.getDependentTable());

                if (parentName.equals(dependentName)) {
                    logger.debug("Ignorada dependencia autorreferenciada para '{}'. No crea arista en el DAG.", parentName);
                    continue;
                }

                if (normalizedIncluded.contains(parentName) && normalizedIncluded.contains(dependentName)) {
                    TableNode parent = new TableNode(parentName);
                    TableNode dependent = new TableNode(dependentName);
                    graph.addDependency(parent, dependent);
                    validEdges++;
                } else {
                    logger.debug("Descartada dependencia foranea hacia tabla no incluida: {} -> {}",
                            dep.getParentTable(), dep.getDependentTable());
                }
            }
        }

        logger.info("Grafo de dependencias construido. Nodos: {}, aristas validas: {}",
                normalizedIncluded.size(), validEdges);

        return graph;
    }

    private String normalizeTableId(String tableId) {
        if (tableId == null || tableId.trim().isEmpty()) {
            throw new IllegalArgumentException("El identificador de tabla no puede ser nulo o vacio.");
        }
        return tableId.trim().toLowerCase(Locale.ROOT);
    }

    private List<TableNode> applyKahnsAlgorithm(DependencyGraph graph) {
        logger.debug("Aplicando algoritmo de Kahn para resolver el orden topologico.");
        KahnResult result = runKahnsAlgorithm(graph);

        if (result.executionOrder.size() != graph.getInDegree().size()) {
            List<String> cyclicTables = result.remainingNodes.stream()
                    .map(TableNode::getName)
                    .collect(Collectors.toList());

            String cycleMsg = "Se ha detectado una dependencia ciclica entre las tablas: " + String.join(", ", cyclicTables);
            logger.error("No se puede generar un DAG valido. {}", cycleMsg);
            throw new CycleDetectedException("No se pudo hallar un orden valido. " + cycleMsg);
        }

        logger.info("Ordenamiento finalizado correctamente. Cadena de delegacion resultante: {}",
                result.executionOrder.stream().map(TableNode::getName).collect(Collectors.joining(" -> ")));

        return result.executionOrder;
    }

    private KahnResult runKahnsAlgorithm(DependencyGraph graph) {
        Map<TableNode, Integer> inDegree = new HashMap<>(graph.getInDegree());
        Map<TableNode, Set<TableNode>> adjacencyList = graph.getAdjacencyList();

        List<TableNode> executionOrder = new ArrayList<>();
        PriorityQueue<TableNode> readyQueue = new PriorityQueue<>();

        for (Map.Entry<TableNode, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                readyQueue.add(entry.getKey());
            }
        }

        while (!readyQueue.isEmpty()) {
            TableNode current = readyQueue.poll();
            executionOrder.add(current);

            Set<TableNode> neighbors = adjacencyList.getOrDefault(current, Collections.emptySet());
            for (TableNode neighbor : neighbors) {
                int newInDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newInDegree);

                if (newInDegree == 0) {
                    readyQueue.add(neighbor);
                }
            }
        }

        Set<TableNode> processed = new LinkedHashSet<>(executionOrder);
        Set<TableNode> remaining = inDegree.keySet().stream()
                .filter(node -> !processed.contains(node))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new KahnResult(executionOrder, remaining);
    }

    private Set<TableNode> findNodesParticipatingInCycles(DependencyGraph graph, Set<TableNode> candidates) {
        Set<TableNode> cyclicNodes = new LinkedHashSet<>();
        for (TableNode candidate : candidates) {
            Set<TableNode> reachable = new LinkedHashSet<>();
            collectReachable(candidate, graph.getAdjacencyList(), candidates, reachable);
            for (TableNode other : reachable) {
                if (!candidate.equals(other)
                        && pathExists(other, candidate, graph.getAdjacencyList(), candidates, new LinkedHashSet<>())) {
                    cyclicNodes.add(candidate);
                    cyclicNodes.add(other);
                }
            }
        }
        return cyclicNodes;
    }

    private void collectReachable(TableNode current,
                                  Map<TableNode, Set<TableNode>> adjacencyList,
                                  Set<TableNode> allowedNodes,
                                  Set<TableNode> visited) {
        for (TableNode neighbor : adjacencyList.getOrDefault(current, Collections.emptySet())) {
            if (allowedNodes.contains(neighbor) && visited.add(neighbor)) {
                collectReachable(neighbor, adjacencyList, allowedNodes, visited);
            }
        }
    }

    private boolean pathExists(TableNode current,
                               TableNode target,
                               Map<TableNode, Set<TableNode>> adjacencyList,
                               Set<TableNode> allowedNodes,
                               Set<TableNode> visited) {
        if (current.equals(target)) {
            return true;
        }
        if (!visited.add(current)) {
            return false;
        }
        for (TableNode neighbor : adjacencyList.getOrDefault(current, Collections.emptySet())) {
            if (allowedNodes.contains(neighbor) && pathExists(neighbor, target, adjacencyList, allowedNodes, visited)) {
                return true;
            }
        }
        return false;
    }

    private List<TableNode> sortNodes(Set<TableNode> nodes) {
        List<TableNode> sorted = new ArrayList<>(nodes);
        Collections.sort(sorted);
        return sorted;
    }

    private static final class KahnResult {
        private final List<TableNode> executionOrder;
        private final Set<TableNode> remainingNodes;

        private KahnResult(List<TableNode> executionOrder, Set<TableNode> remainingNodes) {
            this.executionOrder = executionOrder;
            this.remainingNodes = remainingNodes;
        }
    }

    public static final class BestEffortPlan {
        private final List<TableNode> executableOrder;
        private final List<TableNode> cyclicTables;
        private final List<TableNode> blockedTables;

        private BestEffortPlan(List<TableNode> executableOrder, List<TableNode> cyclicTables, List<TableNode> blockedTables) {
            this.executableOrder = executableOrder;
            this.cyclicTables = cyclicTables;
            this.blockedTables = blockedTables;
        }

        public List<TableNode> getExecutableOrder() {
            return executableOrder;
        }

        public List<TableNode> getCyclicTables() {
            return cyclicTables;
        }

        public List<TableNode> getBlockedTables() {
            return blockedTables;
        }
    }
}

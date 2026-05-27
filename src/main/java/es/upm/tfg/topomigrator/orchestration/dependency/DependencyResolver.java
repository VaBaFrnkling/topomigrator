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

/**
 * Servicio de orquestación responsable de computar el orden seguro de ejecución
 * de la migración de tablas. Utiliza el algoritmo de Kahn para el ordenamiento topológico.
 */
public class DependencyResolver {

    private static final Logger logger = LoggerFactory.getLogger(DependencyResolver.class);

    /**
     * Calcula el orden de ejecución de las tablas asegurando la integridad referencial.
     * 
     * @param includedTables Conjunto de tablas provistas para la migración.
     * @param dependencies Dependencias de clave foránea explícitas entre tablas.
     * @return Lista ordenada de TableNode listos para ejecutarse.
     * @throws CycleDetectedException Si ciclos estructurales evitan la creación de un DAG válido.
     */
    public List<TableNode> resolveExecutionOrder(Set<String> includedTables, List<ForeignKeyDependency> dependencies) {
        logger.info("Iniciando resolución de orden topológico para {} tablas...", includedTables != null ? includedTables.size() : 0);
        
        if (includedTables == null || includedTables.isEmpty()) {
            logger.warn("El conjunto de tablas incluidas está vacío o es nulo. Se aborta el cálculo.");
            return Collections.emptyList();
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
                    logger.debug("Descartada dependencia foránea hacia tabla no incluida: {} -> {}", dep.getParentTable(), dep.getDependentTable());
                }
            }
        }
        
        logger.info("Constructor de DAG finalizado internamente. Nodos instanciados: {}, Aristas válidas inyectadas: {}", normalizedIncluded.size(), validEdges);

        return applyKahnsAlgorithm(graph);
    }

    private String normalizeTableId(String tableId) {
        if (tableId == null || tableId.trim().isEmpty()) {
            throw new IllegalArgumentException("El identificador de tabla no puede ser nulo o vacio.");
        }
        return tableId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Aplica rigurosamente el algoritmo matemático de Kahn en el DAG introducido.
     */
    private List<TableNode> applyKahnsAlgorithm(DependencyGraph graph) {
        logger.debug("Aplicando procesador matemático de la capa (algoritmo de Kahn)...");
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

        if (executionOrder.size() != inDegree.size()) {
            List<String> cyclicTables = inDegree.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> entry.getKey().getName())
                    .collect(Collectors.toList());
            
            String cycleMsg = "Se ha detectado una dependencia cíclica entre las tablas: " + String.join(", ", cyclicTables);
            logger.error("No se puede generar un DAG valido. {}", cycleMsg);
            throw new CycleDetectedException("No se pudo hallar un orden válido. " + cycleMsg);
        }

        logger.info("Ordenamiento finalizado correctamente. Cadena de delegación resultante: {}", 
                executionOrder.stream().map(TableNode::getName).collect(Collectors.joining(" -> ")));

        return executionOrder;
    }
}

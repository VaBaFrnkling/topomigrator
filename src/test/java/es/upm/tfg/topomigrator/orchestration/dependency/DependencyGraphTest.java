package es.upm.tfg.topomigrator.orchestration.dependency;

import junit.framework.TestCase;

import java.util.Map;
import java.util.Set;

/**
 * Tests exhaustivos para la estructura de datos {@link DependencyGraph}.
 * Cubre: adición de nodos, aristas, duplicados, idempotencia,
 * encapsulamiento (vistas no modificables), y grados de entrada.
 */
public class DependencyGraphTest extends TestCase {

    private DependencyGraph graph;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        graph = new DependencyGraph();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ADICIÓN DE NODOS
    // ═══════════════════════════════════════════════════════════════════

    /** Un nodo recién añadido aparece en el mapa de adyacencia con set vacío. */
    public void testAddTableCreatesEmptyAdjacencyEntry() {
        TableNode node = new TableNode("clientes");
        graph.addTable(node);

        assertTrue(graph.getAdjacencyList().containsKey(node));
        assertTrue(graph.getAdjacencyList().get(node).isEmpty());
    }

    /** Un nodo recién añadido tiene grado de entrada 0. */
    public void testAddTableInitializesInDegreeToZero() {
        TableNode node = new TableNode("pedidos");
        graph.addTable(node);

        assertEquals(Integer.valueOf(0), graph.getInDegree().get(node));
    }

    /** Añadir el mismo nodo dos veces no crea duplicados ni resetea estado. */
    public void testAddDuplicateTableIsIdempotent() {
        TableNode node = new TableNode("productos");
        graph.addTable(node);
        // Simulamos que ya tiene in-degree > 0 tras añadir una arista
        graph.addDependency(new TableNode("categorias"), node);
        int degreeAfterDep = graph.getInDegree().get(node);

        // Volver a llamar addTable NO debe resetear el in-degree
        graph.addTable(node);
        assertEquals(degreeAfterDep, (int) graph.getInDegree().get(node));
    }

    /** Múltiples nodos independientes se añaden correctamente. */
    public void testAddMultipleIndependentTables() {
        graph.addTable(new TableNode("a"));
        graph.addTable(new TableNode("b"));
        graph.addTable(new TableNode("c"));

        assertEquals(3, graph.getAdjacencyList().size());
        assertEquals(3, graph.getInDegree().size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ADICIÓN DE ARISTAS (DEPENDENCIAS)
    // ═══════════════════════════════════════════════════════════════════

    /** Añadir una arista crea la relación correcta en la lista de adyacencia. */
    public void testAddDependencyCreatesEdge() {
        TableNode parent = new TableNode("clientes");
        TableNode child = new TableNode("pedidos");
        graph.addDependency(parent, child);

        assertTrue(graph.getAdjacencyList().get(parent).contains(child));
    }

    /** Añadir una arista incrementa el in-degree del nodo dependiente. */
    public void testAddDependencyIncrementsInDegree() {
        TableNode parent = new TableNode("clientes");
        TableNode child = new TableNode("pedidos");
        graph.addDependency(parent, child);

        assertEquals(Integer.valueOf(1), graph.getInDegree().get(child));
        assertEquals(Integer.valueOf(0), graph.getInDegree().get(parent));
    }

    /** Múltiples padres distintos incrementan el in-degree de forma acumulativa. */
    public void testMultipleParentsIncrementInDegree() {
        TableNode p1 = new TableNode("clientes");
        TableNode p2 = new TableNode("productos");
        TableNode child = new TableNode("lineas");
        graph.addDependency(p1, child);
        graph.addDependency(p2, child);

        assertEquals(Integer.valueOf(2), graph.getInDegree().get(child));
    }

    /** Una arista duplicada NO incrementa el in-degree (el TreeSet la rechaza). */
    public void testDuplicateEdgeDoesNotIncrementInDegree() {
        TableNode parent = new TableNode("a");
        TableNode child = new TableNode("b");
        graph.addDependency(parent, child);
        graph.addDependency(parent, child); // Duplicada
        graph.addDependency(parent, child); // Triplicada

        assertEquals(Integer.valueOf(1), graph.getInDegree().get(child));
        assertEquals(1, graph.getAdjacencyList().get(parent).size());
    }

    /** Nodos implícitos: addDependency crea los nodos si no existían. */
    public void testAddDependencyAutoCreatesNodes() {
        TableNode parent = new TableNode("nueva_a");
        TableNode child = new TableNode("nueva_b");
        // No llamamos addTable previamente
        graph.addDependency(parent, child);

        assertTrue(graph.getAdjacencyList().containsKey(parent));
        assertTrue(graph.getAdjacencyList().containsKey(child));
        assertEquals(Integer.valueOf(0), graph.getInDegree().get(parent));
        assertEquals(Integer.valueOf(1), graph.getInDegree().get(child));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ENCAPSULAMIENTO — VISTAS NO MODIFICABLES
    // ═══════════════════════════════════════════════════════════════════

    /** getAdjacencyList devuelve una vista no modificable. */
    public void testAdjacencyListIsUnmodifiable() {
        graph.addTable(new TableNode("test"));
        Map<TableNode, Set<TableNode>> adj = graph.getAdjacencyList();
        try {
            adj.put(new TableNode("intruso"), null);
            fail("Se esperaba UnsupportedOperationException al modificar la vista.");
        } catch (UnsupportedOperationException e) {
            // Esperado
        }
    }

    /** getInDegree devuelve una vista no modificable. */
    public void testInDegreeIsUnmodifiable() {
        graph.addTable(new TableNode("test"));
        Map<TableNode, Integer> deg = graph.getInDegree();
        try {
            deg.put(new TableNode("intruso"), 99);
            fail("Se esperaba UnsupportedOperationException al modificar la vista.");
        } catch (UnsupportedOperationException e) {
            // Esperado
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GRAFO VACÍO
    // ═══════════════════════════════════════════════════════════════════

    /** Un grafo recién creado tiene mapas vacíos. */
    public void testEmptyGraphHasEmptyMaps() {
        assertTrue(graph.getAdjacencyList().isEmpty());
        assertTrue(graph.getInDegree().isEmpty());
    }
}

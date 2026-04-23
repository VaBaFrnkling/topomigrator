package es.upm.tfg.topomigrator.orchestration.dependency;

import es.upm.tfg.topomigrator.exceptions.CycleDetectedException;
import junit.framework.TestCase;

import java.util.*;

/**
 * Suite exhaustiva del resolutor topológico (Algoritmo de Kahn).
 * Cubre: linealidad, multi-raíz, ciclos, tablas aisladas,
 * dependencias externas, entradas nulas/vacías, diamantes,
 * auto-referencias, duplicados y grafos complejos.
 */
public class DependencyResolverTest extends TestCase {

    private DependencyResolver resolver;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        resolver = new DependencyResolver();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CASOS POSITIVOS BÁSICOS
    // ═══════════════════════════════════════════════════════════════════

    /** Cadena lineal: clientes → pedidos → lineas_pedido. */
    public void testLinearDependencies() {
        Set<String> tables = new HashSet<>(Arrays.asList("clientes", "pedidos", "lineas_pedido"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("clientes", "pedidos"),
                new ForeignKeyDependency("pedidos", "lineas_pedido")
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(3, result.size());
        assertEquals("clientes", result.get(0).getName());
        assertEquals("pedidos", result.get(1).getName());
        assertEquals("lineas_pedido", result.get(2).getName());
    }

    /** Múltiples raíces independientes: orden determinista alfabético. */
    public void testMultipleRootsDeterministicOrder() {
        Set<String> tables = new HashSet<>(Arrays.asList("productos", "clientes", "pedidos", "lineas_pedido"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("clientes", "pedidos"),
                new ForeignKeyDependency("pedidos", "lineas_pedido"),
                new ForeignKeyDependency("productos", "lineas_pedido")
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(4, result.size());
        assertEquals("clientes", result.get(0).getName());
        assertEquals("pedidos", result.get(1).getName());
        assertEquals("productos", result.get(2).getName());
        assertEquals("lineas_pedido", result.get(3).getName());
    }

    /** Tablas sin ninguna dependencia: se ordenan alfabéticamente. */
    public void testIsolatedTablesSortedAlphabetically() {
        Set<String> tables = new HashSet<>(Arrays.asList("z_table", "a_table", "m_table"));
        List<ForeignKeyDependency> deps = Collections.emptyList();

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(3, result.size());
        assertEquals("a_table", result.get(0).getName());
        assertEquals("m_table", result.get(1).getName());
        assertEquals("z_table", result.get(2).getName());
    }

    /** Una sola tabla sin dependencias. */
    public void testSingleTableNoDependencies() {
        Set<String> tables = new HashSet<>(Collections.singletonList("unica"));
        List<ForeignKeyDependency> deps = Collections.emptyList();

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(1, result.size());
        assertEquals("unica", result.get(0).getName());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PATRONES DE GRAFO COMPLEJOS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Patrón diamante: A→B, A→C, B→D, C→D.
     * D debe ir último, A primero.
     */
    public void testDiamondDependencyPattern() {
        Set<String> tables = new HashSet<>(Arrays.asList("a", "b", "c", "d"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("a", "b"),
                new ForeignKeyDependency("a", "c"),
                new ForeignKeyDependency("b", "d"),
                new ForeignKeyDependency("c", "d")
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(4, result.size());
        assertEquals("a", result.get(0).getName());
        // b y c se resuelven por orden alfabético ya que tienen in-degree 0 simultáneamente
        assertEquals("b", result.get(1).getName());
        assertEquals("c", result.get(2).getName());
        assertEquals("d", result.get(3).getName());
    }

    /**
     * Grafo ancho: muchas raíces independientes que convergen en un solo nodo final.
     * r1, r2, r3 → sumidero
     */
    public void testWideGraphMultipleRootsOneLeaf() {
        Set<String> tables = new HashSet<>(Arrays.asList("raiz_a", "raiz_b", "raiz_c", "sumidero"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("raiz_a", "sumidero"),
                new ForeignKeyDependency("raiz_b", "sumidero"),
                new ForeignKeyDependency("raiz_c", "sumidero")
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(4, result.size());
        // Las tres raíces van primero (alfabéticas), sumidero al final
        assertEquals("raiz_a", result.get(0).getName());
        assertEquals("raiz_b", result.get(1).getName());
        assertEquals("raiz_c", result.get(2).getName());
        assertEquals("sumidero", result.get(3).getName());
    }

    /**
     * Cadena larga de 5 niveles: a→b→c→d→e.
     */
    public void testDeepChainDependency() {
        Set<String> tables = new HashSet<>(Arrays.asList("nivel_1", "nivel_2", "nivel_3", "nivel_4", "nivel_5"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("nivel_1", "nivel_2"),
                new ForeignKeyDependency("nivel_2", "nivel_3"),
                new ForeignKeyDependency("nivel_3", "nivel_4"),
                new ForeignKeyDependency("nivel_4", "nivel_5")
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(5, result.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("nivel_" + (i + 1), result.get(i).getName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DETECCIÓN DE CICLOS
    // ═══════════════════════════════════════════════════════════════════

    /** Ciclo triangular: A→B→C→A. */
    public void testCycleDetectionTriangle() {
        Set<String> tables = new HashSet<>(Arrays.asList("table_a", "table_b", "table_c"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("table_a", "table_b"),
                new ForeignKeyDependency("table_b", "table_c"),
                new ForeignKeyDependency("table_c", "table_a")
        );

        try {
            resolver.resolveExecutionOrder(tables, deps);
            fail("Se esperaba la excepción CycleDetectedException");
        } catch (CycleDetectedException ex) {
            assertTrue(ex.getMessage().contains("dependencia cíclica"));
            assertTrue(ex.getMessage().contains("table_a"));
        }
    }

    /** Ciclo directo entre dos tablas: A↔B. */
    public void testCycleDetectionBidirectional() {
        Set<String> tables = new HashSet<>(Arrays.asList("x", "y"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("x", "y"),
                new ForeignKeyDependency("y", "x")
        );

        try {
            resolver.resolveExecutionOrder(tables, deps);
            fail("Se esperaba CycleDetectedException por ciclo bidireccional.");
        } catch (CycleDetectedException ex) {
            assertTrue(ex.getMessage().contains("dependencia cíclica"));
        }
    }

    /** Auto-referencia (FK que apunta a sí misma): tratada como ciclo. */
    public void testSelfReferencingDependencyDetectedAsCycle() {
        Set<String> tables = new HashSet<>(Collections.singletonList("self_ref"));
        List<ForeignKeyDependency> deps = Collections.singletonList(
                new ForeignKeyDependency("self_ref", "self_ref")
        );

        try {
            resolver.resolveExecutionOrder(tables, deps);
            fail("Se esperaba CycleDetectedException por auto-referencia.");
        } catch (CycleDetectedException ex) {
            assertTrue(ex.getMessage().contains("self_ref"));
        }
    }

    /** Ciclo parcial: A→B→C→B (ciclo en un subconjunto del grafo). */
    public void testPartialCycleInSubgraph() {
        Set<String> tables = new HashSet<>(Arrays.asList("a", "b", "c"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("a", "b"),
                new ForeignKeyDependency("b", "c"),
                new ForeignKeyDependency("c", "b")
        );

        try {
            resolver.resolveExecutionOrder(tables, deps);
            fail("Se esperaba CycleDetectedException por ciclo parcial.");
        } catch (CycleDetectedException ex) {
            assertTrue(ex.getMessage().contains("dependencia cíclica"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MANEJO DE ENTRADAS NULAS / VACÍAS
    // ═══════════════════════════════════════════════════════════════════

    /** Conjunto de tablas nulo devuelve lista vacía. */
    public void testNullIncludedTablesReturnsEmpty() {
        List<TableNode> result = resolver.resolveExecutionOrder(null, Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /** Conjunto de tablas vacío devuelve lista vacía. */
    public void testEmptyIncludedTablesReturnsEmpty() {
        List<TableNode> result = resolver.resolveExecutionOrder(new HashSet<>(), Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /** Lista de dependencias nula: las tablas se resuelven sin aristas. */
    public void testNullDependenciesListResolves() {
        Set<String> tables = new HashSet<>(Arrays.asList("b", "a"));
        List<TableNode> result = resolver.resolveExecutionOrder(tables, null);

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).getName());
        assertEquals("b", result.get(1).getName());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FILTRADO DE DEPENDENCIAS EXTERNAS / DUPLICADOS
    // ═══════════════════════════════════════════════════════════════════

    /** Dependencias externas (tabla no incluida en el contrato) se ignoran. */
    public void testIgnoreDependenciesOutsideIncludedTables() {
        Set<String> tables = new HashSet<>(Collections.singletonList("pedidos"));
        List<ForeignKeyDependency> deps = Collections.singletonList(
                new ForeignKeyDependency("clientes", "pedidos") // 'clientes' no incluida
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(1, result.size());
        assertEquals("pedidos", result.get(0).getName());
    }

    /** Dependencias duplicadas no deben alterar el resultado (arista idempotente). */
    public void testDuplicateDependenciesHandledCorrectly() {
        Set<String> tables = new HashSet<>(Arrays.asList("padre", "hijo"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("padre", "hijo"),
                new ForeignKeyDependency("padre", "hijo"), // Duplicada
                new ForeignKeyDependency("padre", "hijo")  // Duplicada
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(2, result.size());
        assertEquals("hijo", result.get(1).getName());
        assertEquals("padre", result.get(0).getName());
    }

    /** Mezcla de dependencias internas, externas y duplicadas. */
    public void testMixedDependenciesScenario() {
        Set<String> tables = new HashSet<>(Arrays.asList("clientes", "pedidos", "productos"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("clientes", "pedidos"),        // Válida
                new ForeignKeyDependency("categorias", "productos"),    // 'categorias' externa → ignorada
                new ForeignKeyDependency("clientes", "pedidos"),        // Duplicada
                new ForeignKeyDependency("pedidos", "facturas")         // 'facturas' externa → ignorada
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(3, result.size());
        assertEquals("clientes", result.get(0).getName());
        // pedidos y productos ambos libres ahora, orden alfabético
        assertEquals("pedidos", result.get(1).getName());
        assertEquals("productos", result.get(2).getName());
    }
}

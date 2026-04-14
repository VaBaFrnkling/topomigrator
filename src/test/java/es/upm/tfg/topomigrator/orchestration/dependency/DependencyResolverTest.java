package es.upm.tfg.topomigrator.orchestration.dependency;

import es.upm.tfg.topomigrator.exceptions.CycleDetectedException;
import junit.framework.TestCase;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DependencyResolverTest extends TestCase {

    private DependencyResolver resolver;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        resolver = new DependencyResolver();
    }

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

    public void testMultipleRootsDeterministicOrder() {
        Set<String> tables = new HashSet<>(Arrays.asList("productos", "clientes", "pedidos", "lineas_pedido"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("clientes", "pedidos"),
                new ForeignKeyDependency("pedidos", "lineas_pedido"),
                new ForeignKeyDependency("productos", "lineas_pedido")
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(4, result.size());
        // Comprobación determinista: resolución alfabética ante grado de entrada nulo (0).
        // Inicialmente 'clientes' y 'productos' tienen in-degree 0. 'clientes' se extrae primero.
        assertEquals("clientes", result.get(0).getName());
        assertEquals("pedidos", result.get(1).getName());
        assertEquals("productos", result.get(2).getName());
        assertEquals("lineas_pedido", result.get(3).getName());
    }

    public void testCycleDetectionFails() {
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

    public void testIsolatedTablesSortedAlphabetically() {
        Set<String> tables = new HashSet<>(Arrays.asList("z_table", "a_table", "m_table"));
        // Utilizando una lista vacía
        java.util.List<ForeignKeyDependency> deps = java.util.Collections.emptyList();

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(3, result.size());
        assertEquals("a_table", result.get(0).getName());
        assertEquals("m_table", result.get(1).getName());
        assertEquals("z_table", result.get(2).getName());
    }

    public void testIgnoreDependenciesOutsideIncludedTables() {
        Set<String> tables = new HashSet<>(Arrays.asList("pedidos"));
        List<ForeignKeyDependency> deps = Arrays.asList(
                new ForeignKeyDependency("clientes", "pedidos") // 'clientes' no se incluyó
        );

        List<TableNode> result = resolver.resolveExecutionOrder(tables, deps);

        assertEquals(1, result.size());
        assertEquals("pedidos", result.get(0).getName());
    }
}

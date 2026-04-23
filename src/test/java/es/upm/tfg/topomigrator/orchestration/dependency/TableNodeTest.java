package es.upm.tfg.topomigrator.orchestration.dependency;

import junit.framework.TestCase;

/**
 * Tests exhaustivos para el POJO {@link TableNode}.
 * Cubre: constructor (validación y normalización), equals, hashCode,
 * compareTo (orden natural), y toString.
 */
public class TableNodeTest extends TestCase {

    // ═══════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR — VALIDACIÓN
    // ═══════════════════════════════════════════════════════════════════

    /** Constructor con nombre nulo lanza IllegalArgumentException. */
    public void testConstructorNullNameThrows() {
        try {
            new TableNode(null);
            fail("Se esperaba IllegalArgumentException por nombre nulo.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("nulo"));
        }
    }

    /** Constructor con nombre vacío lanza IllegalArgumentException. */
    public void testConstructorEmptyNameThrows() {
        try {
            new TableNode("");
            fail("Se esperaba IllegalArgumentException por nombre vacío.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("nulo") || e.getMessage().contains("vacío"));
        }
    }

    /** Constructor con nombre de solo espacios lanza IllegalArgumentException. */
    public void testConstructorBlankNameThrows() {
        try {
            new TableNode("   ");
            fail("Se esperaba IllegalArgumentException por nombre en blanco.");
        } catch (IllegalArgumentException e) {
            // Esperado
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR — NORMALIZACIÓN
    // ═══════════════════════════════════════════════════════════════════

    /** El nombre se normaliza a minúsculas. */
    public void testNameNormalizedToLowercase() {
        TableNode node = new TableNode("Clientes");
        assertEquals("clientes", node.getName());
    }

    /** Nombre en mayúsculas se normaliza. */
    public void testUppercaseNameNormalized() {
        TableNode node = new TableNode("PEDIDOS");
        assertEquals("pedidos", node.getName());
    }

    /** Nombre en minúsculas se mantiene igual. */
    public void testLowercaseNameUnchanged() {
        TableNode node = new TableNode("productos");
        assertEquals("productos", node.getName());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EQUALS
    // ═══════════════════════════════════════════════════════════════════

    /** Mismo objeto: equals devuelve true. */
    public void testEqualsSameObject() {
        TableNode node = new TableNode("test");
        assertTrue(node.equals(node));
    }

    /** Dos nodos con el mismo nombre (misma capitalización): equals true. */
    public void testEqualsSameName() {
        TableNode a = new TableNode("clientes");
        TableNode b = new TableNode("clientes");
        assertTrue(a.equals(b));
    }

    /** Dos nodos con diferente capitalización: equals true (normalización). */
    public void testEqualsDifferentCaseSameName() {
        TableNode a = new TableNode("Clientes");
        TableNode b = new TableNode("CLIENTES");
        assertTrue(a.equals(b));
    }

    /** Dos nodos con nombres distintos: equals false. */
    public void testEqualsDifferentName() {
        TableNode a = new TableNode("clientes");
        TableNode b = new TableNode("pedidos");
        assertFalse(a.equals(b));
    }

    /** Comparación con null: equals false. */
    public void testEqualsNull() {
        TableNode node = new TableNode("test");
        assertFalse(node.equals(null));
    }

    /** Comparación con objeto de clase distinta: equals false. */
    public void testEqualsDifferentClass() {
        TableNode node = new TableNode("test");
        assertFalse(node.equals("test"));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HASHCODE
    // ═══════════════════════════════════════════════════════════════════

    /** Dos nodos iguales tienen el mismo hashCode. */
    public void testHashCodeConsistentWithEquals() {
        TableNode a = new TableNode("clientes");
        TableNode b = new TableNode("clientes");
        assertEquals(a.hashCode(), b.hashCode());
    }

    /** Dos nodos con diferente case producen el mismo hashCode (normalización). */
    public void testHashCodeCaseInsensitive() {
        TableNode a = new TableNode("Clientes");
        TableNode b = new TableNode("CLIENTES");
        assertEquals(a.hashCode(), b.hashCode());
    }

    /** Invocar hashCode múltiples veces devuelve el mismo valor. */
    public void testHashCodeStable() {
        TableNode node = new TableNode("test");
        int hash1 = node.hashCode();
        int hash2 = node.hashCode();
        assertEquals(hash1, hash2);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  COMPARETO
    // ═══════════════════════════════════════════════════════════════════

    /** El orden natural es alfabético. */
    public void testCompareToAlphabeticalOrder() {
        TableNode a = new TableNode("alpha");
        TableNode b = new TableNode("beta");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
    }

    /** Nodos con el mismo nombre: compareTo devuelve 0. */
    public void testCompareToEqualNodes() {
        TableNode a = new TableNode("test");
        TableNode b = new TableNode("test");
        assertEquals(0, a.compareTo(b));
    }

    /** El compareTo es case-insensitive gracias a la normalización. */
    public void testCompareToCaseInsensitive() {
        TableNode a = new TableNode("Alpha");
        TableNode b = new TableNode("ALPHA");
        assertEquals(0, a.compareTo(b));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TOSTRING
    // ═══════════════════════════════════════════════════════════════════

    /** toString contiene el nombre del nodo. */
    public void testToStringContainsName() {
        TableNode node = new TableNode("clientes");
        assertTrue(node.toString().contains("clientes"));
    }
}

package es.upm.tfg.topomigrator.orchestration.dependency;

import junit.framework.TestCase;

import java.util.Locale;

/**
 * Tests exhaustivos para el modelo {@link ForeignKeyDependency}.
 * Cubre: constructor (validación y normalización), getters,
 * equals, hashCode, y toString.
 */
public class ForeignKeyDependencyTest extends TestCase {

    // ═══════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR — VALIDACIÓN
    // ═══════════════════════════════════════════════════════════════════

    /** Tabla padre nula lanza IllegalArgumentException. */
    public void testNullParentThrows() {
        try {
            new ForeignKeyDependency(null, "hija");
            fail("Se esperaba IllegalArgumentException por padre nulo.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("padre"));
        }
    }

    /** Tabla padre vacía lanza IllegalArgumentException. */
    public void testEmptyParentThrows() {
        try {
            new ForeignKeyDependency("", "hija");
            fail("Se esperaba IllegalArgumentException por padre vacío.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("padre"));
        }
    }

    /** Tabla padre de solo espacios lanza IllegalArgumentException. */
    public void testBlankParentThrows() {
        try {
            new ForeignKeyDependency("   ", "hija");
            fail("Se esperaba IllegalArgumentException por padre en blanco.");
        } catch (IllegalArgumentException e) {
            // Esperado
        }
    }

    /** Tabla dependiente nula lanza IllegalArgumentException. */
    public void testNullDependentThrows() {
        try {
            new ForeignKeyDependency("padre", null);
            fail("Se esperaba IllegalArgumentException por dependiente nula.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("dependiente"));
        }
    }

    /** Tabla dependiente vacía lanza IllegalArgumentException. */
    public void testEmptyDependentThrows() {
        try {
            new ForeignKeyDependency("padre", "");
            fail("Se esperaba IllegalArgumentException por dependiente vacía.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("dependiente"));
        }
    }

    /** Tabla dependiente de solo espacios lanza IllegalArgumentException. */
    public void testBlankDependentThrows() {
        try {
            new ForeignKeyDependency("padre", "   ");
            fail("Se esperaba IllegalArgumentException por dependiente en blanco.");
        } catch (IllegalArgumentException e) {
            // Esperado
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR — NORMALIZACIÓN
    // ═══════════════════════════════════════════════════════════════════

    /** Los nombres se normalizan a minúsculas. */
    public void testNamesNormalizedToLowercase() {
        ForeignKeyDependency dep = new ForeignKeyDependency("CLIENTES", "Pedidos");
        assertEquals("clientes", dep.getParentTable());
        assertEquals("pedidos", dep.getDependentTable());
    }

    /** Nombres ya en minúsculas se mantienen. */
    public void testLowercaseNamesUnchanged() {
        ForeignKeyDependency dep = new ForeignKeyDependency("clientes", "pedidos");
        assertEquals("clientes", dep.getParentTable());
        assertEquals("pedidos", dep.getDependentTable());
    }

    /** La normalizacion no depende del locale por defecto de la JVM. */
    public void testNamesNormalizedWithRootLocale() {
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            ForeignKeyDependency dep = new ForeignKeyDependency("ITEMS", "INVOICE_LINES");

            assertEquals("items", dep.getParentTable());
            assertEquals("invoice_lines", dep.getDependentTable());
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EQUALS
    // ═══════════════════════════════════════════════════════════════════

    /** Mismo objeto: equals true. */
    public void testEqualsSameObject() {
        ForeignKeyDependency dep = new ForeignKeyDependency("a", "b");
        assertTrue(dep.equals(dep));
    }

    /** Dos dependencias idénticas: equals true. */
    public void testEqualsSameValues() {
        ForeignKeyDependency d1 = new ForeignKeyDependency("clientes", "pedidos");
        ForeignKeyDependency d2 = new ForeignKeyDependency("clientes", "pedidos");
        assertTrue(d1.equals(d2));
    }

    /** Equals es case-insensitive gracias a la normalización en el constructor. */
    public void testEqualsCaseInsensitive() {
        ForeignKeyDependency d1 = new ForeignKeyDependency("Clientes", "PEDIDOS");
        ForeignKeyDependency d2 = new ForeignKeyDependency("clientes", "pedidos");
        assertTrue(d1.equals(d2));
    }

    /** Dependencias con distinto padre: equals false. */
    public void testNotEqualsDifferentParent() {
        ForeignKeyDependency d1 = new ForeignKeyDependency("a", "c");
        ForeignKeyDependency d2 = new ForeignKeyDependency("b", "c");
        assertFalse(d1.equals(d2));
    }

    /** Dependencias con distinto dependiente: equals false. */
    public void testNotEqualsDifferentDependent() {
        ForeignKeyDependency d1 = new ForeignKeyDependency("a", "b");
        ForeignKeyDependency d2 = new ForeignKeyDependency("a", "c");
        assertFalse(d1.equals(d2));
    }

    /** Comparación con null: equals false. */
    public void testEqualsNull() {
        ForeignKeyDependency dep = new ForeignKeyDependency("a", "b");
        assertFalse(dep.equals(null));
    }

    /** Comparación con objeto de otra clase: equals false. */
    public void testEqualsDifferentClass() {
        ForeignKeyDependency dep = new ForeignKeyDependency("a", "b");
        assertFalse(dep.equals("a->b"));
    }

    /** Padre y dependiente intercambiados: equals false (la dirección importa). */
    public void testEqualsReversedDirectionIsFalse() {
        ForeignKeyDependency d1 = new ForeignKeyDependency("a", "b");
        ForeignKeyDependency d2 = new ForeignKeyDependency("b", "a");
        assertFalse(d1.equals(d2));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HASHCODE
    // ═══════════════════════════════════════════════════════════════════

    /** Dos objetos iguales producen el mismo hashCode. */
    public void testHashCodeConsistent() {
        ForeignKeyDependency d1 = new ForeignKeyDependency("clientes", "pedidos");
        ForeignKeyDependency d2 = new ForeignKeyDependency("clientes", "pedidos");
        assertEquals(d1.hashCode(), d2.hashCode());
    }

    /** HashCode es case-insensitive. */
    public void testHashCodeCaseInsensitive() {
        ForeignKeyDependency d1 = new ForeignKeyDependency("CLIENTES", "Pedidos");
        ForeignKeyDependency d2 = new ForeignKeyDependency("clientes", "pedidos");
        assertEquals(d1.hashCode(), d2.hashCode());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TOSTRING
    // ═══════════════════════════════════════════════════════════════════

    /** toString contiene ambos nombres de tabla. */
    public void testToStringContainsBothTables() {
        ForeignKeyDependency dep = new ForeignKeyDependency("padre", "hijo");
        String str = dep.toString();
        assertTrue(str.contains("padre"));
        assertTrue(str.contains("hijo"));
    }
}

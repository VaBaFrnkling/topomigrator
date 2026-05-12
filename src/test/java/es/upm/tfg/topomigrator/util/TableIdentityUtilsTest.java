package es.upm.tfg.topomigrator.util;

import junit.framework.TestCase;

public class TableIdentityUtilsTest extends TestCase {

    public void testPhysicalIdMatchesSchemaTableIdentifierForDefaultSchemaAndCase() {
        assertEquals("public.orders", SchemaTableIdentifierUtils.toQualifiedIdentifier(null, "Orders"));
        assertEquals("public.orders", TableIdentityUtils.toPhysicalId(null, "Orders"));
        assertEquals(
                SchemaTableIdentifierUtils.toQualifiedIdentifier(" PUBLIC ", " Orders "),
                TableIdentityUtils.toPhysicalId(" PUBLIC ", " Orders ")
        );
    }

    public void testPhysicalIdParsingKeepsCanonicalSchemaAndTable() {
        assertEquals("ventas", TableIdentityUtils.schemaFromPhysicalId(" Ventas.Pedidos "));
        assertEquals("pedidos", TableIdentityUtils.tableFromPhysicalId(" Ventas.Pedidos "));
    }

    public void testInvalidPhysicalIdIsRejected() {
        assertInvalid(null);
        assertInvalid(" ");
        assertInvalid("clientes");
        assertInvalid("public.");
        assertInvalid(".clientes");
    }

    private void assertInvalid(String physicalId) {
        try {
            TableIdentityUtils.schemaFromPhysicalId(physicalId);
            fail("Se esperaba rechazo de physicalId invalido.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("schema.table") || expected.getMessage().contains("physicalId"));
        }
    }
}

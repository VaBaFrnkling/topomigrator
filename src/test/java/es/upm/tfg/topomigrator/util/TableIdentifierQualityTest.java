package es.upm.tfg.topomigrator.util;

import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class TableIdentifierQualityTest {

    @Test
    public void schemaTableIdentifierBuildsCanonicalQualifiedNamesAndTraceFileNames() {
        assertEquals("public.customers", SchemaTableIdentifierUtils.toQualifiedIdentifier(null, " Customers "));
        assertEquals("sales.orders.json", SchemaTableIdentifierUtils.toTraceFileName(" Sales ", " Orders "));
    }

    @Test
    public void tableIdentityExtractsSchemaAndTableFromPhysicalId() {
        assertEquals("public", TableIdentityUtils.schemaFromPhysicalId(" Public.Customers "));
        assertEquals("customers", TableIdentityUtils.tableFromPhysicalId(" Public.Customers "));
    }

    @Test
    public void tableIdentityUsesSourceTableForMigrationPhysicalId() {
        TableMigration table = QualityTestData.fullTable("customers");
        table.getSource().setSchema("CRM");

        assertEquals("crm.customers", TableIdentityUtils.toSourcePhysicalId(table));
    }

    @Test
    public void tableIdentityFailsForMalformedPhysicalIds() {
        assertThrows(IllegalArgumentException.class, () -> TableIdentityUtils.schemaFromPhysicalId("customers"));
        assertThrows(IllegalArgumentException.class, () -> SchemaTableIdentifierUtils.toQualifiedIdentifier("public", " "));
    }
}

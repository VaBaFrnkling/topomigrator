package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class TableMetricsServiceQualityTest {

    private final TableMetricsService service = new TableMetricsService();

    @Test
    public void buildSourceSelectSqlIncludesFullMigrationFilter() {
        TableMigration table = QualityTestData.filteredFullTable("customers", "active = true");

        String sql = service.buildSourceSelectSql(table);

        assertEquals("SELECT * FROM public.customers WHERE active = true", sql);
    }

    @Test
    public void buildSourceSelectSqlEscapesIncrementalCursorAndAppliesBatchLimit() {
        TableMigration table = QualityTestData.incrementalTable("orders");

        String sql = service.buildSourceSelectSql(table, "2026-05-01T10:00:00' OR '1'='1");

        assertEquals("SELECT * FROM public.orders WHERE updated_at > '2026-05-01T10:00:00'' OR ''1''=''1' ORDER BY updated_at ASC LIMIT 100", sql);
    }

    @Test
    public void buildSourceSelectSqlFailsForIncrementalTableWithoutEffectiveCursor() {
        TableMigration table = QualityTestData.incrementalTable("orders");

        assertThrows(IllegalStateException.class, () -> service.buildSourceSelectSql(table, null));
    }
}

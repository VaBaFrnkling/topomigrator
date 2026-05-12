package es.upm.tfg.topomigrator.orchestration.dependency;

import junit.framework.TestCase;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MetadataDependencyExtractorTest extends TestCase {

    private MetadataDependencyExtractor extractor;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        extractor = new MetadataDependencyExtractor();
    }

    public void testExtractDependenciesReturnsParentToDependentForIncludedTables() throws SQLException {
        RecordingMetadata metadata = new RecordingMetadata();
        metadata.addImportedKeys("public.pedidos", fk("public", "clientes", "public", "pedidos"));

        List<ForeignKeyDependency> dependencies = extractor.extractDependencies(
                connection(metadata),
                orderedSet("public.clientes", "public.pedidos")
        );

        assertEquals(1, dependencies.size());
        assertEquals("public.clientes", dependencies.get(0).getParentTable());
        assertEquals("public.pedidos", dependencies.get(0).getDependentTable());
        assertEquals(Arrays.asList("public.clientes", "public.pedidos"), metadata.requestedTables);
    }

    public void testExtractDependenciesKeepsSchemaWhenSameTableNameExistsInMultipleSchemas() throws SQLException {
        RecordingMetadata metadata = new RecordingMetadata();
        metadata.addImportedKeys("ventas.pedidos", fk("ventas", "clientes", "ventas", "pedidos"));
        metadata.addImportedKeys("crm.pedidos", fk("crm", "clientes", "crm", "pedidos"));

        List<ForeignKeyDependency> dependencies = extractor.extractDependencies(
                connection(metadata),
                orderedSet("ventas.clientes", "ventas.pedidos", "crm.clientes", "crm.pedidos")
        );

        assertEquals(2, dependencies.size());
        assertEquals("ventas.clientes", dependencies.get(0).getParentTable());
        assertEquals("ventas.pedidos", dependencies.get(0).getDependentTable());
        assertEquals("crm.clientes", dependencies.get(1).getParentTable());
        assertEquals("crm.pedidos", dependencies.get(1).getDependentTable());
    }

    public void testExtractDependenciesDiscardsForeignKeysOutsideIncludedTables() throws SQLException {
        RecordingMetadata metadata = new RecordingMetadata();
        metadata.addImportedKeys("public.pedidos", fk("public", "clientes", "public", "pedidos"));
        metadata.addImportedKeys("public.pedidos", fk("public", "catalogos", "public", "pedidos"));

        List<ForeignKeyDependency> dependencies = extractor.extractDependencies(
                connection(metadata),
                orderedSet("public.clientes", "public.pedidos")
        );

        assertEquals(1, dependencies.size());
        assertEquals("public.clientes", dependencies.get(0).getParentTable());
        assertEquals("public.pedidos", dependencies.get(0).getDependentTable());
    }

    public void testExtractDependenciesDeduplicatesAndPreservesDiscoveryOrder() throws SQLException {
        RecordingMetadata metadata = new RecordingMetadata();
        metadata.addImportedKeys("public.pedidos", fk("public", "clientes", "public", "pedidos"));
        metadata.addImportedKeys("public.pedidos", fk("public", "clientes", "public", "pedidos"));
        metadata.addImportedKeys("public.lineas", fk("public", "pedidos", "public", "lineas"));
        metadata.addImportedKeys("public.lineas", fk("public", "clientes", "public", "pedidos"));

        List<ForeignKeyDependency> dependencies = extractor.extractDependencies(
                connection(metadata),
                orderedSet("public.clientes", "public.pedidos", "public.lineas")
        );

        assertEquals(2, dependencies.size());
        assertEquals("public.clientes", dependencies.get(0).getParentTable());
        assertEquals("public.pedidos", dependencies.get(0).getDependentTable());
        assertEquals("public.pedidos", dependencies.get(1).getParentTable());
        assertEquals("public.lineas", dependencies.get(1).getDependentTable());
    }

    public void testExtractDependenciesReturnsEmptyWithoutMetadataForNullOrEmptyIncludedTables() throws SQLException {
        MetadataAccessGuard guard = new MetadataAccessGuard();

        assertTrue(extractor.extractDependencies(connection(guard), null).isEmpty());
        assertTrue(extractor.extractDependencies(connection(guard), Collections.emptySet()).isEmpty());
        assertFalse(guard.accessed);
    }

    public void testExtractDependenciesNormalizesIncludedIdsWithTrimCaseAndPublicSchemaFromMetadata() throws SQLException {
        RecordingMetadata metadata = new RecordingMetadata();
        metadata.addImportedKeys("public.pedidos", fk(null, "CLIENTES", "PUBLIC", "PEDIDOS"));

        List<ForeignKeyDependency> dependencies = extractor.extractDependencies(
                connection(metadata),
                orderedSet(" PUBLIC.CLIENTES ", " public.pedidos ")
        );

        assertEquals(1, dependencies.size());
        assertEquals("public.clientes", dependencies.get(0).getParentTable());
        assertEquals("public.pedidos", dependencies.get(0).getDependentTable());
        assertEquals(Arrays.asList("public.clientes", "public.pedidos"), metadata.requestedTables);
    }

    public void testExtractDependenciesRejectsNullBlankOrSchemaLessIncludedIdsWithoutNpe() throws SQLException {
        assertInvalidIncludedId(null);
        assertInvalidIncludedId(" ");
        assertInvalidIncludedId("clientes");
        assertInvalidIncludedId("public.");
        assertInvalidIncludedId(".clientes");
    }

    public void testExtractDependenciesPropagatesSqlExceptionFromGetMetaData() {
        SQLException expected = new SQLException("metadata unavailable");

        try {
            extractor.extractDependencies(connectionThrowingOnGetMetaData(expected), orderedSet("public.clientes"));
            fail("Se esperaba SQLException desde getMetaData.");
        } catch (SQLException ex) {
            assertSame(expected, ex);
        }
    }

    public void testExtractDependenciesPropagatesSqlExceptionFromGetImportedKeys() {
        SQLException expected = new SQLException("imported keys unavailable");
        RecordingMetadata metadata = new RecordingMetadata();
        metadata.failure = expected;

        try {
            extractor.extractDependencies(connection(metadata), orderedSet("public.clientes"));
            fail("Se esperaba SQLException desde getImportedKeys.");
        } catch (SQLException ex) {
            assertSame(expected, ex);
        }
    }

    private void assertInvalidIncludedId(String includedId) throws SQLException {
        try {
            extractor.extractDependencies(connection(new MetadataAccessGuard()), orderedSet(includedId));
            fail("Se esperaba IllegalArgumentException para id invalido.");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("schema.table") || ex.getMessage().contains("physicalId"));
        }
    }

    private Connection connection(MetadataProvider provider) {
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return provider.getMetaData();
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Connection connectionThrowingOnGetMetaData(SQLException failure) {
        return connection(() -> {
            throw failure;
        });
    }

    private DatabaseMetaData databaseMetaData(RecordingMetadata metadata) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getImportedKeys".equals(method.getName())) {
                        if (metadata.failure != null) {
                            throw metadata.failure;
                        }
                        String schema = (String) args[1];
                        String table = (String) args[2];
                        String physicalId = schema + "." + table;
                        metadata.requestedTables.add(physicalId);
                        return resultSet(metadata.importedKeysByTable.getOrDefault(physicalId, List.of()));
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private ResultSet resultSet(List<FkRow> rows) {
        final int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        index[0]++;
                        return index[0] < rows.size();
                    }
                    if ("getString".equals(method.getName())) {
                        return rows.get(index[0]).values.get((String) args[0]);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private FkRow fk(String parentSchema, String parentTable, String dependentSchema, String dependentTable) {
        Map<String, String> values = new HashMap<>();
        values.put("PKTABLE_SCHEM", parentSchema);
        values.put("PKTABLE_NAME", parentTable);
        values.put("FKTABLE_SCHEM", dependentSchema);
        values.put("FKTABLE_NAME", dependentTable);
        return new FkRow(values);
    }

    private Set<String> orderedSet(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (double.class.equals(returnType)) {
            return 0D;
        }
        if (float.class.equals(returnType)) {
            return 0F;
        }
        if (short.class.equals(returnType)) {
            return (short) 0;
        }
        if (byte.class.equals(returnType)) {
            return (byte) 0;
        }
        if (char.class.equals(returnType)) {
            return (char) 0;
        }
        return null;
    }

    private interface MetadataProvider {
        DatabaseMetaData getMetaData() throws SQLException;
    }

    private class RecordingMetadata implements MetadataProvider {
        private final Map<String, List<FkRow>> importedKeysByTable = new LinkedHashMap<>();
        private final List<String> requestedTables = new ArrayList<>();
        private SQLException failure;

        void addImportedKeys(String physicalId, FkRow row) {
            importedKeysByTable.computeIfAbsent(physicalId, ignored -> new ArrayList<>()).add(row);
        }

        @Override
        public DatabaseMetaData getMetaData() {
            return databaseMetaData(this);
        }
    }

    private class MetadataAccessGuard implements MetadataProvider {
        private boolean accessed;

        @Override
        public DatabaseMetaData getMetaData() throws SQLException {
            accessed = true;
            throw new SQLException("No se esperaba acceso a metadata.");
        }
    }

    private static class FkRow {
        private final Map<String, String> values;

        FkRow(Map<String, String> values) {
            this.values = values;
        }
    }
}

package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.junit.Assert.assertThrows;

public class LiquibaseSchemaExecutorQualityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void applyTargetSchemasFailsBeforeConnectingWhenRequiredChangelogIsMissing() throws Exception {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("missing_table"));
        Path changelogsDir = temporaryFolder.newFolder("changelogs").toPath();

        assertThrows(InvalidChangelogException.class,
                () -> LiquibaseSchemaExecutor.applyTargetSchemas(contract, changelogsDir, ignored -> {
                    throw new AssertionError("No debe conectar si el changelog requerido no existe.");
                }));
    }

    @Test
    public void applyTargetSchemasSkipsLiquibaseWhenTargetTableAlreadyExists() throws Exception {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Path changelogsDir = temporaryFolder.newFolder("changelogs").toPath();
        writeChangelog(changelogsDir, "public", "customers");
        Connection connection = connectionWithExistingTable();

        LiquibaseSchemaExecutor.applyTargetSchemas(contract, changelogsDir, ignored -> connection);
    }

    @Test
    public void applyTargetSchemasRejectsContractsWithoutTables() {
        MigrationContract contract = QualityTestData.contractWith();

        assertThrows(InvalidChangelogException.class,
                () -> LiquibaseSchemaExecutor.applyTargetSchemas(contract, ignored -> connectionWithExistingTable()));
    }

    private void writeChangelog(Path directory, String schema, String table) throws Exception {
        Files.writeString(directory.resolve(schema + "." + table + ".yaml"), ""
                + "databaseChangeLog:\n"
                + "  - changeSet:\n"
                + "      id: 1\n"
                + "      author: quality-test\n"
                + "      changes:\n"
                + "        - createTable:\n"
                + "            schemaName: " + schema + "\n"
                + "            tableName: " + table + "\n"
                + "            columns:\n"
                + "              - column:\n"
                + "                  name: id\n"
                + "                  type: int\n");
    }

    private static Connection connectionWithExistingTable() {
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if (method.getName().equals("getTables")) {
                return singleRowResultSet();
            }
            return defaultValue(method.getReturnType());
        });

        return proxy(Connection.class, (proxy, method, args) -> {
            if (method.getName().equals("getMetaData")) {
                return metadata;
            }
            if (method.getName().equals("close")) {
                return null;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static ResultSet singleRowResultSet() {
        return proxy(ResultSet.class, new InvocationHandler() {
            private boolean unread = true;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                if (method.getName().equals("next")) {
                    boolean result = unread;
                    unread = false;
                    return result;
                }
                if (method.getName().equals("close")) {
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}

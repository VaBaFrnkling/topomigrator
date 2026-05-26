package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.junit.Assert.assertThrows;

public class LiquibaseSchemaExecutorQualityTest {

    @Test
    public void applyTargetSchemasFailsBeforeConnectingWhenRequiredChangelogIsMissing() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("missing_table"));

        assertThrows(InvalidChangelogException.class,
                () -> LiquibaseSchemaExecutor.applyTargetSchemas(contract, ignored -> {
                    throw new AssertionError("No debe conectar si el changelog requerido no existe.");
                }));
    }

    @Test
    public void applyTargetSchemasSkipsLiquibaseWhenTargetTableAlreadyExists() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Connection connection = connectionWithExistingTable();

        LiquibaseSchemaExecutor.applyTargetSchemas(contract, ignored -> connection);
    }

    @Test
    public void applyTargetSchemasRejectsContractsWithoutTables() {
        MigrationContract contract = QualityTestData.contractWith();

        assertThrows(InvalidChangelogException.class,
                () -> LiquibaseSchemaExecutor.applyTargetSchemas(contract, ignored -> connectionWithExistingTable()));
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

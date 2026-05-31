package es.upm.tfg.topomigrator;

import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class AppQualityTest {

    @Test
    public void resolveDependencyPlanBuildsBestEffortPlanForCycles() throws Exception {
        MigrationContract contract = QualityTestData.contractWith(
                QualityTestData.fullTable("projects"),
                QualityTestData.fullTable("tasks"),
                QualityTestData.fullTable("task_links"),
                QualityTestData.fullTable("task_assignments")
        );
        Connection connection = connection(List.of(
                Map.of(
                        "PKTABLE_SCHEM", "public",
                        "PKTABLE_NAME", "tasks",
                        "FKTABLE_SCHEM", "public",
                        "FKTABLE_NAME", "task_links"
                ),
                Map.of(
                        "PKTABLE_SCHEM", "public",
                        "PKTABLE_NAME", "task_links",
                        "FKTABLE_SCHEM", "public",
                        "FKTABLE_NAME", "tasks"
                ),
                Map.of(
                        "PKTABLE_SCHEM", "public",
                        "PKTABLE_NAME", "tasks",
                        "FKTABLE_SCHEM", "public",
                        "FKTABLE_NAME", "task_assignments"
                )
        ));

        App.DependencyPlan plan = App.resolveDependencyPlan(contract, ignored -> connection);

        assertEquals(List.of("projects"), tableNames(plan.getExecutionOrder()));
        assertEquals(List.of("task_links", "tasks"), tableNames(plan.getCyclicTables()));
        assertEquals(List.of("task_assignments"), tableNames(plan.getBlockedTablesByCycle()));
        assertEquals(List.of("projects"), App.filterContractForExecutableTables(contract, plan.getExecutionOrder())
                .getTables()
                .keySet()
                .stream()
                .collect(Collectors.toList()));
    }

    private static List<String> tableNames(List<TableNode> tableNodes) {
        return tableNodes.stream().map(TableNode::getName).collect(Collectors.toList());
    }

    private static Connection connection(List<Map<String, Object>> importedKeys) {
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if (method.getName().equals("getImportedKeys")) {
                return resultSet(importedKeys);
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

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                if (method.getName().equals("next")) {
                    index++;
                    return index < rows.size();
                }
                if (method.getName().equals("getString")) {
                    Object value = rows.get(index).get((String) args[0]);
                    return value != null ? value.toString() : null;
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

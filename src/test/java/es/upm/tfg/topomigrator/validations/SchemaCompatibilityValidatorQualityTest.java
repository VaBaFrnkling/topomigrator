package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.SchemaCompatibilityException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertThrows;

public class SchemaCompatibilityValidatorQualityTest {

    @Test
    public void validateTargetAndMappingAcceptsCompatibleSourceAndTargetSchemas() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Connection source = connection(metadata(
                columns("customers", column("id", Types.INTEGER, "int4", 10, false), column("email", Types.VARCHAR, "varchar", 255, true)),
                primaryKeys("customers", "id"),
                foreignKeys("customers")
        ));
        Connection target = connection(metadata(
                columns("customers", column("id", Types.BIGINT, "int8", 19, false), column("email", Types.VARCHAR, "varchar", 255, true)),
                primaryKeys("customers", "id"),
                foreignKeys("customers")
        ));

        SchemaCompatibilityValidator.validateTargetAndMapping(contract, ignored -> source, ignored -> target);
    }

    @Test
    public void validateTargetAndMappingFailsWhenTargetColumnIsMissing() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Connection source = connection(metadata(
                columns("customers", column("id", Types.INTEGER, "int4", 10, false), column("email", Types.VARCHAR, "varchar", 255, true)),
                primaryKeys("customers", "id"),
                foreignKeys("customers")
        ));
        Connection target = connection(metadata(
                columns("customers", column("id", Types.INTEGER, "int4", 10, false)),
                primaryKeys("customers", "id"),
                foreignKeys("customers")
        ));

        assertThrows(SchemaCompatibilityException.class,
                () -> SchemaCompatibilityValidator.validateTargetAndMapping(contract, ignored -> source, ignored -> target));
    }

    @Test
    public void validateTargetAndMappingFailsWhenTargetTypeIsIncompatible() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Connection source = connection(metadata(
                columns("customers", column("created_at", Types.TIMESTAMP, "timestamp", 26, false)),
                primaryKeys("customers"),
                foreignKeys("customers")
        ));
        Connection target = connection(metadata(
                columns("customers", column("created_at", Types.VARCHAR, "varchar", 26, false)),
                primaryKeys("customers"),
                foreignKeys("customers")
        ));

        assertThrows(SchemaCompatibilityException.class,
                () -> SchemaCompatibilityValidator.validateTargetAndMapping(contract, ignored -> source, ignored -> target));
    }

    @Test
    public void validateTargetAndMappingFailsWhenTargetLosesPrimaryKeyColumns() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Connection source = connection(metadata(
                columns("customers", column("id", Types.INTEGER, "int4", 10, false)),
                primaryKeys("customers", "id"),
                foreignKeys("customers")
        ));
        Connection target = connection(metadata(
                columns("customers", column("id", Types.INTEGER, "int4", 10, false)),
                primaryKeys("customers"),
                foreignKeys("customers")
        ));

        assertThrows(SchemaCompatibilityException.class,
                () -> SchemaCompatibilityValidator.validateTargetAndMapping(contract, ignored -> source, ignored -> target));
    }

    private static Connection connection(DatabaseMetaData metadata) {
        return proxy(Connection.class, (proxy, method, args) -> {
            if (method.getName().equals("getMetaData")) {
                return metadata;
            }
            if (method.getName().equals("close")) {
                return null;
            }
            if (method.getName().equals("isClosed")) {
                return false;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static DatabaseMetaData metadata(Map<String, List<Map<String, Object>>> columnsByTable,
                                             Map<String, List<Map<String, Object>>> primaryKeysByTable,
                                             Map<String, List<Map<String, Object>>> foreignKeysByTable) {
        return proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            String methodName = method.getName();
            if (methodName.equals("getColumns")) {
                return resultSet(columnsByTable.getOrDefault(normalize((String) args[2]), List.of()));
            }
            if (methodName.equals("getPrimaryKeys")) {
                return resultSet(primaryKeysByTable.getOrDefault(normalize((String) args[2]), List.of()));
            }
            if (methodName.equals("getImportedKeys")) {
                return resultSet(foreignKeysByTable.getOrDefault(normalize((String) args[2]), List.of()));
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                String methodName = method.getName();
                if (methodName.equals("next")) {
                    index++;
                    return index < rows.size();
                }
                if (methodName.equals("close")) {
                    return null;
                }
                if (methodName.equals("getString")) {
                    Object value = currentRow().get((String) args[0]);
                    return value != null ? value.toString() : null;
                }
                if (methodName.equals("getInt")) {
                    Object value = currentRow().get((String) args[0]);
                    return value instanceof Number ? ((Number) value).intValue() : 0;
                }
                if (methodName.equals("getObject")) {
                    return currentRow().get((String) args[0]);
                }
                return defaultValue(method.getReturnType());
            }

            private Map<String, Object> currentRow() {
                return rows.get(index);
            }
        });
    }

    @SafeVarargs
    private static Map<String, List<Map<String, Object>>> columns(String table, Map<String, Object>... columns) {
        return Map.of(normalize(table), List.of(columns));
    }

    private static Map<String, List<Map<String, Object>>> primaryKeys(String table, String... columns) {
        return Map.of(normalize(table), List.of(columns).stream()
                .map(column -> Map.<String, Object>of("COLUMN_NAME", column))
                .toList());
    }

    private static Map<String, List<Map<String, Object>>> foreignKeys(String table, String... columns) {
        return Map.of(normalize(table), List.of(columns).stream()
                .map(column -> Map.<String, Object>of("FKCOLUMN_NAME", column))
                .toList());
    }

    private static Map<String, Object> column(String name, int jdbcType, String typeName, int size, boolean nullable) {
        return Map.of(
                "COLUMN_NAME", name,
                "DATA_TYPE", jdbcType,
                "TYPE_NAME", typeName,
                "COLUMN_SIZE", size,
                "DECIMAL_DIGITS", 0,
                "NULLABLE", nullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls
        );
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
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

package es.upm.tfg.topomigrator.util;

import java.util.Locale;

public final class SchemaTableIdentifierUtils {

    public static final String DEFAULT_SCHEMA = "public";

    private SchemaTableIdentifierUtils() {
    }

    public static String normalizeSchema(String schema) {
        if (schema == null || schema.trim().isEmpty()) {
            return DEFAULT_SCHEMA;
        }
        return schema.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeTable(String table) {
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de tabla no puede ser nulo o vacío");
        }
        return table.trim().toLowerCase(Locale.ROOT);
    }

    public static String toQualifiedIdentifier(String schema, String table) {
        return normalizeSchema(schema) + "." + normalizeTable(table);
    }

    public static String toTraceFileName(String schema, String table) {
        return toQualifiedIdentifier(schema, table) + ".json";
    }
}

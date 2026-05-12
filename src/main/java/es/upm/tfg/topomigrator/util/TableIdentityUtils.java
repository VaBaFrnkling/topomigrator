package es.upm.tfg.topomigrator.util;

import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.model.TableRef;

import java.util.Locale;

/**
 * Utilidad para construir una identidad física única de tabla basada en schema.table.
 * Se usa únicamente para resolución de dependencias, comparación y búsqueda interna.
 */
public final class TableIdentityUtils {

    public static final String DEFAULT_SCHEMA = "public";

    private TableIdentityUtils() {
    }

    public static String normalizeSchema(String schema) {
        return SchemaTableIdentifierUtils.normalizeSchema(schema);
    }

    public static String normalizeTable(String table) {
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de tabla no puede ser nulo o vacío.");
        }
        return table.trim().toLowerCase(Locale.ROOT);
    }

    public static String toPhysicalId(String schema, String table) {
        return SchemaTableIdentifierUtils.toQualifiedIdentifier(schema, table);
    }

    public static String toPhysicalId(TableRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("La referencia de tabla no puede ser nula.");
        }
        return toPhysicalId(ref.getSchema(), ref.getTable());
    }

    public static String toSourcePhysicalId(TableMigration migration) {
        if (migration == null || migration.getSource() == null) {
            throw new IllegalArgumentException("La configuración de tabla o su origen no pueden ser nulos.");
        }
        return toPhysicalId(migration.getSource());
    }

    public static String schemaFromPhysicalId(String physicalId) {
        int separator = requireSeparator(physicalId);
        return physicalId.substring(0, separator).trim().toLowerCase(Locale.ROOT);
    }

    public static String tableFromPhysicalId(String physicalId) {
        int separator = requireSeparator(physicalId);
        return physicalId.substring(separator + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static int requireSeparator(String physicalId) {
        if (physicalId == null || physicalId.trim().isEmpty()) {
            throw new IllegalArgumentException("El physicalId no puede ser nulo o vacío.");
        }

        int separator = physicalId.indexOf('.');
        if (separator <= 0 || separator == physicalId.length() - 1) {
            throw new IllegalArgumentException("El physicalId debe tener formato schema.table: " + physicalId);
        }
        return separator;
    }
}

package es.upm.tfg.topomigrator.orchestration.dependency;

import es.upm.tfg.topomigrator.util.TableIdentityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MetadataDependencyExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MetadataDependencyExtractor.class);

    public List<ForeignKeyDependency> extractDependencies(Connection connection, Set<String> includedTableIds) throws SQLException {
        if (includedTableIds == null || includedTableIds.isEmpty()) {
            logger.warn("Se ha invocado el extractor físico pero no existen tablas en el lote. Retornando matriz vacía.");
            return new ArrayList<>();
        }

        Set<String> normalizedIncluded = new LinkedHashSet<>();
        for (String tableId : includedTableIds) {
            normalizedIncluded.add(normalizeIncludedPhysicalId(tableId));
        }

        logger.info("Iniciando escaneo transaccional de metadatos (JDBC) para {} tablas físicas...", normalizedIncluded.size());

        DatabaseMetaData metaData = connection.getMetaData();
        Set<ForeignKeyDependency> uniqueDependencies = new LinkedHashSet<>();

        for (String physicalId : normalizedIncluded) {
            String schema = TableIdentityUtils.schemaFromPhysicalId(physicalId);
            String table = TableIdentityUtils.tableFromPhysicalId(physicalId);

            logger.debug("Examinando metadatos (ImportedKeys) para tabla física [{}]", physicalId);

            try (ResultSet rs = metaData.getImportedKeys(null, schema, table)) {
                while (rs.next()) {
                    String parentId = TableIdentityUtils.toPhysicalId(
                            rs.getString("PKTABLE_SCHEM"),
                            rs.getString("PKTABLE_NAME")
                    );
                    String dependentId = TableIdentityUtils.toPhysicalId(
                            rs.getString("FKTABLE_SCHEM"),
                            rs.getString("FKTABLE_NAME")
                    );

                    if (parentId.equals(dependentId)) {
                        logger.debug("Ignorada FK autorreferenciada dentro de {}. No afecta al orden entre tablas.", parentId);
                    } else if (normalizedIncluded.contains(parentId) && normalizedIncluded.contains(dependentId)) {
                        uniqueDependencies.add(new ForeignKeyDependency(parentId, dependentId));
                        logger.trace("Descubierta FK incluida en el lote: Padre {} <- Hija {}", parentId, dependentId);
                    } else {
                        logger.debug("Descartada FK fuera del lote actual: {} -> {}", parentId, dependentId);
                    }
                }
            }
        }

        logger.info("Escaneo finalizado por completo. Halladas {} dependencias estructurales dentro del lote configurado.", uniqueDependencies.size());
        return new ArrayList<>(uniqueDependencies);
    }

    private String normalizeIncludedPhysicalId(String tableId) {
        if (tableId == null || tableId.trim().isEmpty()) {
            throw new IllegalArgumentException("El id de tabla incluida debe tener formato schema.table.");
        }

        String normalized = tableId.trim().toLowerCase(Locale.ROOT);
        int firstSeparator = normalized.indexOf('.');
        int lastSeparator = normalized.lastIndexOf('.');
        if (firstSeparator <= 0 || firstSeparator != lastSeparator || firstSeparator == normalized.length() - 1) {
            throw new IllegalArgumentException("El id de tabla incluida debe tener formato schema.table: " + tableId);
        }

        return TableIdentityUtils.toPhysicalId(
                TableIdentityUtils.schemaFromPhysicalId(normalized),
                TableIdentityUtils.tableFromPhysicalId(normalized)
        );
    }
}

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
import java.util.Set;

/**
 * Componente responsable de extraer metadatos físicos de la base de datos origen.
 * Conecta el mundo de la base de datos relacional con el modelo de dependencias
 * del Topomigrator, recuperando las Foreign Keys dinámicamente mediante la API JDBC.
 */
public class MetadataDependencyExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MetadataDependencyExtractor.class);

    /**
     * Extrae todas las dependencias de clave foránea (padre-hija) basándose en
     * el diccionario de la base de datos y la lista de tablas físicas a migrar.
     *
     * Cada tabla incluida debe venir con el formato schema.table.
     */
    public List<ForeignKeyDependency> extractDependencies(Connection connection, Set<String> includedTableIds) throws SQLException {
        if (includedTableIds == null || includedTableIds.isEmpty()) {
            logger.warn("Se ha invocado el extractor físico pero no existen tablas en el lote. Retornando matriz vacía.");
            return new ArrayList<>();
        }

        Set<String> normalizedIncluded = new LinkedHashSet<>();
        for (String tableId : includedTableIds) {
            normalizedIncluded.add(tableId.toLowerCase());
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

                    if (normalizedIncluded.contains(parentId) && normalizedIncluded.contains(dependentId)) {
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
}

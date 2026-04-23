package es.upm.tfg.topomigrator.orchestration.dependency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
     * el diccionario de la base de datos y la lista de tablas a migrar.
     *
     * @param connection     Conexión SQL activa contra la base de datos origen (ej: PostgreSQL).
     * @param schema         Esquema de base de datos (por ejemplo, "public"). 
     *                       Puede ser nulo para aplicar la búsqueda global o al esquema predeterminado.
     * @param includedTables Conjunto de nombres de las tablas explícitamente citadas en el `Contrato.yaml`.
     * @return Lista de objetos ForeignKeyDependency que modelan la dirección padre -> dependiente.
     * @throws SQLException Si ocurre un error de red o al consultar los metadatos de la base de datos.
     */
    public List<ForeignKeyDependency> extractDependencies(Connection connection, String schema, Set<String> includedTables) throws SQLException {
        List<ForeignKeyDependency> dependencias = new ArrayList<>();

        if (includedTables == null || includedTables.isEmpty()) {
            logger.warn("Se ha invocado el extractor físico pero no existen tablas en el lote. Retornando matriz vacía.");
            return dependencias; // Retornamos temprano si no hay tablas que evaluar
        }

        logger.info("Iniciando escaneo transaccional de metadatos (JDBC) para {} tablas...", includedTables.size());

        DatabaseMetaData metaData = connection.getMetaData();
        int totalDependenciesFound = 0;

        for (String tableName : includedTables) {
            logger.debug("Examinando metadatos (ImportedKeys) para tabla física [{}]", tableName);
            
            // Se invoca el método getImportedKeys() el cual busca todas las columnas (Foreign Keys)
            // mediante las cuales tableName (Hija) depende de otras (Padres).
            try (ResultSet rs = metaData.getImportedKeys(null, schema, tableName)) {
                while (rs.next()) {
                    String parentTable = rs.getString("PKTABLE_NAME");    // La tabla a la que apunta (Padre)
                    String dependentTable = rs.getString("FKTABLE_NAME"); // La tabla que tiene la FK (Hija)
                    
                    if (parentTable != null && dependentTable != null) {
                        dependencias.add(new ForeignKeyDependency(parentTable, dependentTable));
                        totalDependenciesFound++;
                        logger.trace("Descubierto enjambre de datos relacional (FK): Padre {} <- Hija {}", parentTable, dependentTable);
                    }
                }
            }
        }
        
        logger.info("Escaneo finalizado por completo. Halladas {} dependencias estructurales ancladas a las tablas configuradas.", totalDependenciesFound);
        
        return dependencias;
    }
}

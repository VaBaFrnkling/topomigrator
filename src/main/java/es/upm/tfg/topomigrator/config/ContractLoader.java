package es.upm.tfg.topomigrator.config;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Lee y parsea el fichero contract.yaml usando SnakeYAML,
 * devolviendo un objeto MigrationContract listo para usar.
 */
public class ContractLoader {

    private static final Logger log = LoggerFactory.getLogger(ContractLoader.class);

    /**
     * Carga el contrato desde la ruta indicada y fusiona sus propiedades
     * con las variables de entorno.
     *
     * @param contractPath Ruta absoluta o relativa al fichero contract.yaml
     * @return MigrationContract con toda la configuración parseada y actualizada
     * @throws IOException si el fichero no existe o no puede leerse
     */
    public MigrationContract load(Path contractPath) throws IOException {
        log.info("Cargando contrato desde: {}", contractPath.toAbsolutePath());

        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(MigrationContract.class, options));

        try (InputStream is = new FileInputStream(contractPath.toFile())) {
            MigrationContract contract = yaml.load(is);

            // Inyectar configuración de base de datos puramente desde variables de entorno
            injectDatabaseConfigurationFromEnv(contract);

            log.info("Contrato cargado y unificado con entorno: {} (v{})",
                    contract.getMigration().getName(),
                    contract.getMigration().getVersion());
            log.info("Tablas a procesar: {}",
                    contract.getTables() != null ? contract.getTables().keySet() : "ninguna");

            return contract;
        } catch (Exception e) {
            log.error("Error al parsear el contrato: {}", e.getMessage(), e);
            throw new IOException("No se pudo cargar el contrato desde: " + contractPath, e);
        }
    }

    /**
     * Construye la configuración de la BD (DatabaseConfig, ConnectionConfig)
     * leyendo exclusivamente del entorno, ya que se ha sacado del YAML.
     */
    private void injectDatabaseConfigurationFromEnv(MigrationContract contract) {
        DatabaseConfig dbConfig = new DatabaseConfig();

        // --- Conexión Origen ---
        ConnectionConfig source = new ConnectionConfig();
        source.setDriver("org.postgresql.Driver"); // Valor asumido por defecto
        source.setJdbcUrl(System.getenv("SOURCE_DB_URL"));
        source.setUsername(System.getenv("SOURCE_DB_USER"));
        source.setPassword(System.getenv("SOURCE_DB_PASSWORD"));
        dbConfig.setSourceConnection(source);

        // --- Conexión Destino ---
        ConnectionConfig target = new ConnectionConfig();
        target.setDriver("org.postgresql.Driver"); // Valor asumido por defecto
        target.setJdbcUrl(System.getenv("TARGET_DB_URL"));
        target.setUsername(System.getenv("TARGET_DB_USER"));
        target.setPassword(System.getenv("TARGET_DB_PASSWORD"));
        dbConfig.setTargetConnection(target);

        // Se lo inyectamos al contrato
        contract.setDatabase(dbConfig);
        log.info("Configuración de base de datos inyectada desde variables de entorno.");
    }
}
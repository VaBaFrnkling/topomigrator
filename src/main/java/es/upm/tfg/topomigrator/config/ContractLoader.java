package es.upm.tfg.topomigrator.config;

import es.upm.tfg.topomigrator.model.ConnectionConfig;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee y parsea el fichero contract.yaml usando SnakeYAML,
 * devolviendo un objeto MigrationContract listo para usar.
 */
public class ContractLoader {

    private static final Logger log = LoggerFactory.getLogger(ContractLoader.class);
    private static final Pattern ENV_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

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

            loadDatabaseConfigurationFromYaml(contract);

            if (contract.getMigration() != null) {
                String execUser = contract.getMigration().getAuthor();
                if ("${USERNAME}".equals(execUser) || "${USER}".equals(execUser)) {
                    String sysUser = System.getProperty("user.name");
                    if (sysUser == null || sysUser.trim().isEmpty()) {
                        sysUser = System.getenv("USERNAME");
                    }
                    if (sysUser == null || sysUser.trim().isEmpty()) {
                        sysUser = System.getenv("USER");
                    }
                    contract.getMigration().setAuthor(sysUser != null ? sysUser : "unknown_user");
                    log.info("Usuario de ejecución dinámico evaluado a: {}", contract.getMigration().getAuthor());
                }
            }

            es.upm.tfg.topomigrator.validations.ContractValidator.validate(contract, contractPath);

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

    @SuppressWarnings("unchecked")
    private void loadDatabaseConfigurationFromYaml(MigrationContract contract) throws IOException {
        String dsPathStr = System.getenv("DATASOURCES_CONFIG_PATH");
        if (dsPathStr == null || dsPathStr.trim().isEmpty()) {
            dsPathStr = "configs/datasources.yaml";
        }
        Path dsPath = Paths.get(dsPathStr);

        log.info("Cargando configuración de bases de datos desde: {}", dsPath.toAbsolutePath());

        if (!Files.exists(dsPath)) {
            throw new IOException("Fichero de datasources no encontrado: " + dsPath.toAbsolutePath());
        }

        Yaml yaml = new Yaml();
        try (InputStream dsIs = new FileInputStream(dsPath.toFile())) {
            Map<String, Object> data = (Map<String, Object>) yaml.load(dsIs);
            DatabaseConfig dbConfig = new DatabaseConfig();

            dbConfig.setSourceConnection(buildConnectionConfig(data, "source", dsPath));
            dbConfig.setTargetConnection(buildConnectionConfig(data, "target", dsPath));

            contract.setDatabase(dbConfig);
            log.info("Configuración de bases de datos cargada desde {}.", dsPath.getFileName());
        }
    }

    @SuppressWarnings("unchecked")
    private ConnectionConfig buildConnectionConfig(Map<String, Object> data, String section, Path filePath) throws IOException {
        Map<String, Object> sectionMap = (Map<String, Object>) data.get(section);
        if (sectionMap == null) {
            throw new IOException("Sección '" + section + "' no encontrada en " + filePath);
        }

        ConnectionConfig config = new ConnectionConfig();
        config.setDriver(resolveEnvironmentPlaceholders(getMapValue(sectionMap, "driver"), section + ".driver"));
        config.setJdbcUrl(resolveEnvironmentPlaceholders(getMapValue(sectionMap, "jdbcUrl"), section + ".jdbcUrl"));
        config.setUsername(resolveEnvironmentPlaceholders(getMapValue(sectionMap, "username"), section + ".username"));
        config.setPassword(resolveEnvironmentPlaceholders(getMapValue(sectionMap, "password"), section + ".password"));
        return config;
    }

    private String getMapValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private String resolveEnvironmentPlaceholders(String rawValue, String fieldName) throws IOException {
        if (rawValue == null) {
            return null;
        }

        Matcher matcher = ENV_PLACEHOLDER_PATTERN.matcher(rawValue);
        StringBuffer resolved = new StringBuffer();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            String variableName = matcher.group(1);
            String defaultValue = matcher.group(2);
            String envValue = System.getenv(variableName);

            if (envValue == null || envValue.trim().isEmpty()) {
                if (defaultValue != null) {
                    envValue = defaultValue;
                } else {
                    throw new IOException("Falta la variable de entorno requerida '" + variableName + "' para " + fieldName);
                }
            }

            matcher.appendReplacement(resolved, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(resolved);

        return found ? resolved.toString() : rawValue;
    }
}

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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee y parsea el fichero contract.yaml usando SnakeYAML,
 * devolviendo un objeto MigrationContract listo para usar.
 */
public class ContractLoader {

    private static final Logger log = LoggerFactory.getLogger(ContractLoader.class);
    private static final Pattern ENV_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

    private final Path datasourceConfigPath;
    private final Function<String, String> envProvider;
    private final Supplier<String> systemUserProvider;

    public ContractLoader() {
        this(null, System::getenv, () -> System.getProperty("user.name"));
    }

    public ContractLoader(Path datasourceConfigPath) {
        this(datasourceConfigPath, System::getenv, () -> System.getProperty("user.name"));
    }

    ContractLoader(Path datasourceConfigPath, Function<String, String> envProvider, Supplier<String> systemUserProvider) {
        this.datasourceConfigPath = datasourceConfigPath;
        this.envProvider = envProvider;
        this.systemUserProvider = systemUserProvider;
    }

    /**
     * Carga el contrato desde la ruta indicada y fusiona sus propiedades
     * con las variables de entorno.
     *
     * @param contractPath Ruta absoluta o relativa al fichero contract.yaml
     * @return MigrationContract con toda la configuracion parseada y actualizada
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
                    contract.getMigration().setAuthor(resolveExecutionUser());
                    log.info("Usuario de ejecucion dinamico evaluado a: {}", contract.getMigration().getAuthor());
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

    private void loadDatabaseConfigurationFromYaml(MigrationContract contract) throws IOException {
        Path dsPath = resolveDatasourceConfigPath();

        log.info("Cargando configuracion de bases de datos desde: {}", dsPath.toAbsolutePath());

        if (!Files.exists(dsPath)) {
            throw new IOException("Fichero de datasources no encontrado: " + dsPath.toAbsolutePath());
        }

        Yaml yaml = new Yaml();
        try (InputStream dsIs = new FileInputStream(dsPath.toFile())) {
            Object loaded = yaml.load(dsIs);
            if (!(loaded instanceof Map)) {
                throw new IOException("El fichero de datasources debe contener un mapa raiz: " + dsPath.toAbsolutePath());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) loaded;
            DatabaseConfig dbConfig = new DatabaseConfig();

            dbConfig.setSourceConnection(buildConnectionConfig(data, "source", dsPath));
            dbConfig.setTargetConnection(buildConnectionConfig(data, "target", dsPath));

            contract.setDatabase(dbConfig);
            log.info("Configuracion de bases de datos cargada desde {}.", dsPath.getFileName());
        }
    }

    private Path resolveDatasourceConfigPath() {
        if (datasourceConfigPath != null) {
            return datasourceConfigPath;
        }

        String dsPathStr = envProvider.apply("DATASOURCES_CONFIG_PATH");
        if (dsPathStr == null || dsPathStr.trim().isEmpty()) {
            dsPathStr = "configs/datasources.yaml";
        } else {
            dsPathStr = dsPathStr.trim();
        }
        return Paths.get(dsPathStr);
    }

    @SuppressWarnings("unchecked")
    private ConnectionConfig buildConnectionConfig(Map<String, Object> data, String section, Path filePath) throws IOException {
        Object sectionValue = data.get(section);
        if (sectionValue == null) {
            throw new IOException("Seccion '" + section + "' no encontrada en " + filePath);
        }
        if (!(sectionValue instanceof Map)) {
            throw new IOException("Seccion '" + section + "' debe ser un mapa en " + filePath);
        }

        Map<String, Object> sectionMap = (Map<String, Object>) sectionValue;

        ConnectionConfig config = new ConnectionConfig();
        config.setDriver(resolveRequiredField(sectionMap, "driver", section + ".driver"));
        config.setDriverLocation(resolveRequiredField(sectionMap, "driverLocation", section + ".driverLocation"));
        config.setDatabaseType(resolveRequiredField(sectionMap, "databaseType", section + ".databaseType"));
        config.setJdbcUrl(resolveRequiredField(sectionMap, "jdbcUrl", section + ".jdbcUrl"));
        config.setUsername(resolveRequiredField(sectionMap, "username", section + ".username"));
        config.setPassword(resolveRequiredField(sectionMap, "password", section + ".password"));
        return config;
    }

    private String resolveRequiredField(Map<String, Object> map, String key, String fieldName) throws IOException {
        String resolvedValue = resolveEnvironmentPlaceholders(getMapValue(map, key), fieldName);
        if (resolvedValue == null || resolvedValue.trim().isEmpty()) {
            throw new IOException("El campo requerido '" + fieldName + "' no puede estar vacio.");
        }
        return resolvedValue;
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
            String envValue = envProvider.apply(variableName);

            if (envValue == null || envValue.trim().isEmpty()) {
                if (defaultValue != null && !defaultValue.trim().isEmpty()) {
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

    private String resolveExecutionUser() {
        String sysUser = systemUserProvider.get();
        if (sysUser == null || sysUser.trim().isEmpty()) {
            sysUser = envProvider.apply("USERNAME");
        }
        if (sysUser == null || sysUser.trim().isEmpty()) {
            sysUser = envProvider.apply("USER");
        }
        return sysUser == null || sysUser.trim().isEmpty() ? "unknown_user" : sysUser;
    }
}

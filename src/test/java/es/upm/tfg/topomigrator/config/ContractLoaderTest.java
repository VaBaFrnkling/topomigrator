package es.upm.tfg.topomigrator.config;

import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import junit.framework.TestCase;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests exhaustivos para {@link ContractLoader}.
 * Cubre: carga exitosa de YAML real, resolución dinámica de autor
 * (${USERNAME}, ${USER}), fichero inexistente, YAML malformado,
 * YAML vacío, y contrato que viola reglas del ContractValidator.
 */
public class ContractLoaderTest extends TestCase {

    private ContractLoader loader;
    private Path defaultDatasourcePath;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        defaultDatasourcePath = writeTempYaml(buildDatasourceYamlWithDefaults());
        loader = new ContractLoader(defaultDatasourcePath);
    }

    @Override
    protected void tearDown() throws Exception {
        Files.deleteIfExists(defaultDatasourcePath);
        super.tearDown();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HELPER
    // ═══════════════════════════════════════════════════════════════════

    private Path writeTempYaml(String content) throws IOException {
        Path path = Files.createTempFile("contract-loader-test-", ".yaml");
        try (FileWriter w = new FileWriter(path.toFile())) {
            w.write(content);
        }
        return path;
    }

    private String buildDatasourceYamlWithDefaults() {
        return "source:\n" +
                "  driver: ${SOURCE_DB_DRIVER:org.postgresql.Driver}\n" +
                "  driverLocation: ${SOURCE_DB_DRIVER_LOCATION:/opt/nifi/drivers/postgresql-42.7.10.jar}\n" +
                "  databaseType: ${SOURCE_DB_TYPE:PostgreSQL}\n" +
                "  jdbcUrl: ${SOURCE_DB_JDBC_URL:jdbc:postgresql://localhost:5432/source_test}\n" +
                "  username: ${SOURCE_DB_USERNAME:source_user}\n" +
                "  password: ${SOURCE_DB_PASSWORD:source_password}\n" +
                "target:\n" +
                "  driver: ${TARGET_DB_DRIVER:org.postgresql.Driver}\n" +
                "  driverLocation: ${TARGET_DB_DRIVER_LOCATION:/opt/nifi/drivers/postgresql-42.7.10.jar}\n" +
                "  databaseType: ${TARGET_DB_TYPE:PostgreSQL}\n" +
                "  jdbcUrl: ${TARGET_DB_JDBC_URL:jdbc:postgresql://localhost:5432/target_test}\n" +
                "  username: ${TARGET_DB_USERNAME:target_user}\n" +
                "  password: ${TARGET_DB_PASSWORD:target_password}\n";
    }

    /**
     * Construye un contrato YAML mínimo válido con el author indicado.
     * Incluye una tabla con changelog existente para evitar fallos en TargetChangelogValidator.
     */
    private String buildMinimalYaml(String author) {
        return "migration:\n" +
                "  name: test_loader\n" +
                "  version: \"1.0\"\n" +
                "  author: " + author + "\n" +
                "tables:\n" +
                "  nombre_tabla_1:\n" +
                "    source:\n" +
                "      schema: public\n" +
                "      table: nombre_tabla\n" +
                "    target:\n" +
                "      schema: public\n" +
                "      table: nombre_tabla\n" +
                "    enabled: true\n" +
                "    migrationType: full\n";
    }

    private boolean messageChainContains(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void assertMissingRequiredDatasourcePlaceholder(String fieldName, String variableName) throws Exception {
        String datasource = "source:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: " + ("jdbcUrl".equals(fieldName) ? "${" + variableName + "}" : "jdbc:postgresql://localhost:5432/source_test") + "\n" +
                "  username: " + ("username".equals(fieldName) ? "${" + variableName + "}" : "source_user") + "\n" +
                "  password: " + ("password".equals(fieldName) ? "${" + variableName + "}" : "source_password") + "\n" +
                "target:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: jdbc:postgresql://localhost:5432/target_test\n" +
                "  username: target_user\n" +
                "  password: target_password\n";
        Path datasourcePath = writeTempYaml(datasource);
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            new ContractLoader(datasourcePath).load(contractPath);
            fail("Se esperaba IOException por placeholder requerido sin default en source." + fieldName);
        } catch (IOException e) {
            assertTrue("El error deberia mencionar la variable requerida",
                    messageChainContains(e, variableName));
            assertTrue("El error deberia mencionar el campo afectado",
                    messageChainContains(e, "source." + fieldName));
        } finally {
            Files.deleteIfExists(datasourcePath);
            Files.deleteIfExists(contractPath);
        }
    }

    private ContractLoader loaderWithEnv(Path datasourcePath, Map<String, String> env, String systemUser) {
        return new ContractLoader(datasourcePath, env::get, () -> systemUser);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HAPPY PATH — CARGA DEL FICHERO REAL DEL PROYECTO
    // ═══════════════════════════════════════════════════════════════════

    /** Carga el contract.yaml real del proyecto y verifica el parseo completo. */
    public void testLoadRealContractFile() throws Exception {
        Path contractPath = Path.of("configs/contract.yaml");
        MigrationContract contract = loader.load(contractPath);

        assertNotNull(contract);
        assertNotNull(contract.getMigration());
        assertEquals("nombre_migracion", contract.getMigration().getName());
        assertEquals("version", contract.getMigration().getVersion());
        assertNotNull(contract.getTables());
        assertFalse(contract.getTables().isEmpty());
    }

    /** Verifica que las tablas del contrato real están mapeadas correctamente. */
    public void testLoadRealContractTablesMapped() throws Exception {
        Path contractPath = Path.of("configs/contract.yaml");
        MigrationContract contract = loader.load(contractPath);

        assertTrue(contract.getTables().containsKey("nombre_tabla_1"));
        assertNotNull(contract.getTables().get("nombre_tabla_1").getSource());
        assertNotNull(contract.getTables().get("nombre_tabla_1").getTarget());
        assertEquals("incremental", contract.getTables().get("nombre_tabla_1").getMigrationType());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  RESOLUCIÓN DINÁMICA DE AUTOR
    // ═══════════════════════════════════════════════════════════════════

    /** El marcador ${USERNAME} se resuelve al usuario del sistema. */
    public void testUsernameVariableResolved() throws Exception {
        Path file = writeTempYaml(buildMinimalYaml("${USERNAME}"));
        try {
            MigrationContract contract = loader.load(file);
            String author = contract.getMigration().getAuthor();
            assertNotNull("El autor resuelto no debería ser nulo", author);
            assertFalse("El autor no debería seguir siendo ${USERNAME}", "${USERNAME}".equals(author));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** El marcador ${USER} se resuelve al usuario del sistema. */
    public void testUserVariableResolved() throws Exception {
        Path file = writeTempYaml(buildMinimalYaml("${USER}"));
        try {
            MigrationContract contract = loader.load(file);
            String author = contract.getMigration().getAuthor();
            assertNotNull("El autor resuelto no debería ser nulo", author);
            assertFalse("El autor no debería seguir siendo ${USER}", "${USER}".equals(author));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Un autor literal ('juan') se mantiene tal cual sin sustitución. */
    public void testLiteralAuthorPreserved() throws Exception {
        Path file = writeTempYaml(buildMinimalYaml("juan_perez"));
        try {
            MigrationContract contract = loader.load(file);
            assertEquals("juan_perez", contract.getMigration().getAuthor());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INYECCIÓN DE DATABASE CONFIG DESDE ENTORNO
    // ═══════════════════════════════════════════════════════════════════

    /** La configuración de base de datos se carga desde configs/datasources.yaml. */
    public void testDatabaseConfigLoadedFromDatasourcesYaml() throws Exception {
        Path contractPath = Path.of("configs/contract.yaml");
        MigrationContract contract = loader.load(contractPath);

        // DatabaseConfig se carga desde el fichero datasources.yaml
        assertNotNull("La configuración de BD debería existir", contract.getDatabase());
        assertNotNull("La conexión origen debería existir", contract.getDatabase().getSourceConnection());
        assertNotNull("La conexión destino debería existir", contract.getDatabase().getTargetConnection());
        // El driver por defecto es PostgreSQL
        assertEquals("org.postgresql.Driver", contract.getDatabase().getSourceConnection().getDriver());
        assertEquals("org.postgresql.Driver", contract.getDatabase().getTargetConnection().getDriver());
    }

    /** Los defaults de datasources.yaml rellenan todos los campos externalizables. */
    public void testDatasourceDefaultsResolveConnectionFields() throws Exception {
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            MigrationContract contract = loader.load(contractPath);

            assertNotNull(contract.getDatabase());
            assertEquals("org.postgresql.Driver", contract.getDatabase().getSourceConnection().getDriver());
            assertEquals("/opt/nifi/drivers/postgresql-42.7.10.jar",
                    contract.getDatabase().getSourceConnection().getDriverLocation());
            assertEquals("PostgreSQL", contract.getDatabase().getSourceConnection().getDatabaseType());
            assertEquals("jdbc:postgresql://localhost:5432/source_test",
                    contract.getDatabase().getSourceConnection().getJdbcUrl());
            assertEquals("source_user", contract.getDatabase().getSourceConnection().getUsername());
            assertEquals("source_password", contract.getDatabase().getSourceConnection().getPassword());
            assertEquals("jdbc:postgresql://localhost:5432/target_test",
                    contract.getDatabase().getTargetConnection().getJdbcUrl());
            assertEquals("target_user", contract.getDatabase().getTargetConnection().getUsername());
            assertEquals("target_password", contract.getDatabase().getTargetConnection().getPassword());
        } finally {
            Files.deleteIfExists(contractPath);
        }
    }

    /** Un jdbcUrl requerido sin default falla indicando variable y campo. */
    public void testMissingRequiredJdbcUrlPlaceholderThrowsIOException() throws Exception {
        assertMissingRequiredDatasourcePlaceholder("jdbcUrl", "TOPOMIGRATOR_MISSING_SOURCE_JDBC_URL_TEST");
    }

    /** Un username requerido sin default falla indicando variable y campo. */
    public void testMissingRequiredUsernamePlaceholderThrowsIOException() throws Exception {
        assertMissingRequiredDatasourcePlaceholder("username", "TOPOMIGRATOR_MISSING_SOURCE_USERNAME_TEST");
    }

    /** Un password requerido sin default falla indicando variable y campo. */
    public void testMissingRequiredPasswordPlaceholderThrowsIOException() throws Exception {
        assertMissingRequiredDatasourcePlaceholder("password", "TOPOMIGRATOR_MISSING_SOURCE_PASSWORD_TEST");
    }

    /** La ausencia de la seccion source en datasources.yaml falla de forma explicita. */
    public void testMissingSourceDatasourceSectionThrowsIOException() throws Exception {
        Path datasourcePath = writeTempYaml("target:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: jdbc:postgresql://localhost:5432/target_test\n" +
                "  username: target_user\n" +
                "  password: target_password\n");
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            new ContractLoader(datasourcePath).load(contractPath);
            fail("Se esperaba IOException por falta de seccion source.");
        } catch (IOException e) {
            assertTrue(messageChainContains(e, "Seccion 'source'")
                    || messageChainContains(e, "Secci"));
            assertTrue(messageChainContains(e, "source"));
        } finally {
            Files.deleteIfExists(datasourcePath);
            Files.deleteIfExists(contractPath);
        }
    }

    /** La ausencia de la seccion target en datasources.yaml falla de forma explicita. */
    public void testMissingTargetDatasourceSectionThrowsIOException() throws Exception {
        Path datasourcePath = writeTempYaml("source:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: jdbc:postgresql://localhost:5432/source_test\n" +
                "  username: source_user\n" +
                "  password: source_password\n");
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            new ContractLoader(datasourcePath).load(contractPath);
            fail("Se esperaba IOException por falta de seccion target.");
        } catch (IOException e) {
            assertTrue(messageChainContains(e, "Seccion 'target'")
                    || messageChainContains(e, "Secci"));
            assertTrue(messageChainContains(e, "target"));
        } finally {
            Files.deleteIfExists(datasourcePath);
            Files.deleteIfExists(contractPath);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ERRORES — FICHERO INEXISTENTE
    // ═══════════════════════════════════════════════════════════════════

    /** Un fichero inexistente lanza IOException. */
    public void testNonExistentFileThrowsIOException() {
        Path fakePath = Path.of("no_existe_xyz_contract.yaml");
        try {
            loader.load(fakePath);
            fail("Se esperaba IOException por fichero inexistente.");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("no_existe_xyz_contract.yaml"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ERRORES — YAML MALFORMADO
    // ═══════════════════════════════════════════════════════════════════

    /** Un YAML con sintaxis inválida lanza IOException. */
    public void testMalformedYamlThrowsIOException() throws Exception {
        Path file = writeTempYaml("esto: no: es: valido:\n  [[mal");
        try {
            loader.load(file);
            fail("Se esperaba IOException por YAML malformado.");
        } catch (IOException e) {
            // La excepción envuelve el error de parseo
            assertNotNull(e.getMessage());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Un fichero YAML vacío causa error (no se puede parsear como MigrationContract). */
    public void testEmptyYamlThrowsIOException() throws Exception {
        Path file = writeTempYaml("");
        try {
            loader.load(file);
            fail("Se esperaba IOException por YAML vacío.");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ERRORES — CONTRATO QUE NO PASA VALIDACIÓN
    // ═══════════════════════════════════════════════════════════════════

    /** Un YAML sin sección 'migration' falla en ContractValidator (envuelto en IOException). */
    public void testYamlWithoutMigrationSectionFails() throws Exception {
        String yaml = "tables:\n  t:\n    source:\n      table: s\n    target:\n      table: d\n    migrationType: full\n";
        Path file = writeTempYaml(yaml);
        try {
            loader.load(file);
            fail("Se esperaba fallo por falta de sección migration.");
        } catch (IOException e) {
            // ContractValidator lanza InvalidContractException, envuelta en IOException por ContractLoader
            assertTrue(e.getCause() instanceof InvalidContractException
                    || e.getMessage().contains("migration"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Un YAML sin sección 'tables' falla en ContractValidator (envuelto en IOException). */
    public void testYamlWithoutTablesSectionFails() throws Exception {
        String yaml = "migration:\n  name: test\n  version: '1'\n";
        Path file = writeTempYaml(yaml);
        try {
            loader.load(file);
            fail("Se esperaba fallo por falta de sección tables.");
        } catch (IOException e) {
            assertTrue(e.getCause() instanceof InvalidContractException
                    || e.getMessage().contains("tabla"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** El loader por defecto intenta usar configs/datasources.yaml cuando DATASOURCES_CONFIG_PATH no existe. */
    public void testDefaultDatasourcesPathIsUsedWhenEnvUnset() throws Exception {
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            loaderWithEnv(null, new HashMap<>(), "tester").load(contractPath);
            fail("Se esperaba IOException porque configs/datasources.yaml requiere variables de entorno reales.");
        } catch (IOException e) {
            assertTrue(messageChainContains(e, "SOURCE_DB_JDBC_URL"));
            assertTrue(messageChainContains(e, "source.jdbcUrl"));
        } finally {
            Files.deleteIfExists(contractPath);
        }
    }

    /** DATASOURCES_CONFIG_PATH tolera espacios laterales. */
    public void testDatasourcesConfigPathIsTrimmed() throws Exception {
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        Map<String, String> env = new HashMap<>();
        env.put("DATASOURCES_CONFIG_PATH", " " + defaultDatasourcePath + " ");
        try {
            MigrationContract contract = loaderWithEnv(null, env, "tester").load(contractPath);
            assertNotNull(contract.getDatabase());
            assertEquals("source_user", contract.getDatabase().getSourceConnection().getUsername());
        } finally {
            Files.deleteIfExists(contractPath);
        }
    }

    /** Si no hay usuario de sistema ni variables USERNAME/USER, se usa unknown_user. */
    public void testUnknownUserFallbackWhenAllUserSourcesBlank() throws Exception {
        Path file = writeTempYaml(buildMinimalYaml("${USERNAME}"));
        try {
            MigrationContract contract = loaderWithEnv(defaultDatasourcePath, new HashMap<>(), " ").load(file);
            assertEquals("unknown_user", contract.getMigration().getAuthor());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Un default vacio se trata como configuracion requerida ausente. */
    public void testEmptyPlaceholderDefaultThrowsIOException() throws Exception {
        Path datasourcePath = writeTempYaml("source:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: ${TOPOMIGRATOR_EMPTY_DEFAULT_TEST:}\n" +
                "  username: source_user\n" +
                "  password: source_password\n" +
                "target:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: jdbc:postgresql://localhost:5432/target_test\n" +
                "  username: target_user\n" +
                "  password: target_password\n");
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            new ContractLoader(datasourcePath).load(contractPath);
            fail("Se esperaba IOException por default vacio.");
        } catch (IOException e) {
            assertTrue(messageChainContains(e, "TOPOMIGRATOR_EMPTY_DEFAULT_TEST"));
            assertTrue(messageChainContains(e, "source.jdbcUrl"));
        } finally {
            Files.deleteIfExists(datasourcePath);
            Files.deleteIfExists(contractPath);
        }
    }

    /** Un campo datasource ausente falla con el campo afectado. */
    public void testMissingDatasourceFieldThrowsIOException() throws Exception {
        Path datasourcePath = writeTempYaml("source:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: jdbc:postgresql://localhost:5432/source_test\n" +
                "  password: source_password\n" +
                "target:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: jdbc:postgresql://localhost:5432/target_test\n" +
                "  username: target_user\n" +
                "  password: target_password\n");
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            new ContractLoader(datasourcePath).load(contractPath);
            fail("Se esperaba IOException por campo requerido ausente.");
        } catch (IOException e) {
            assertTrue(messageChainContains(e, "source.username"));
        } finally {
            Files.deleteIfExists(datasourcePath);
            Files.deleteIfExists(contractPath);
        }
    }

    /** El datasources.yaml real mantiene las credenciales como variables requeridas. */
    public void testRealDatasourcesPasswordsRemainRequired() throws Exception {
        String yaml = Files.readString(Path.of("configs/datasources.yaml"));
        assertTrue(yaml.contains("password: ${SOURCE_DB_PASSWORD}"));
        assertTrue(yaml.contains("password: ${TARGET_DB_PASSWORD}"));
        assertFalse(yaml.contains("source_password"));
        assertFalse(yaml.contains("target_password"));
    }

    /** Un datasources.yaml escalar falla con diagnostico explicito. */
    public void testInvalidDatasourceRootThrowsIOException() throws Exception {
        Path datasourcePath = writeTempYaml("solo texto\n");
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            new ContractLoader(datasourcePath).load(contractPath);
            fail("Se esperaba IOException por raiz no-map.");
        } catch (IOException e) {
            assertTrue(messageChainContains(e, "mapa raiz"));
        } finally {
            Files.deleteIfExists(datasourcePath);
            Files.deleteIfExists(contractPath);
        }
    }

    /** Una seccion source/target no-map falla con diagnostico explicito. */
    public void testNonMapDatasourceSectionThrowsIOException() throws Exception {
        Path datasourcePath = writeTempYaml("source: []\n" +
                "target:\n" +
                "  driver: org.postgresql.Driver\n" +
                "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                "  databaseType: PostgreSQL\n" +
                "  jdbcUrl: jdbc:postgresql://localhost:5432/target_test\n" +
                "  username: target_user\n" +
                "  password: target_password\n");
        Path contractPath = writeTempYaml(buildMinimalYaml("tester"));
        try {
            new ContractLoader(datasourcePath).load(contractPath);
            fail("Se esperaba IOException por seccion source no-map.");
        } catch (IOException e) {
            assertTrue(messageChainContains(e, "source"));
            assertTrue(messageChainContains(e, "mapa"));
        } finally {
            Files.deleteIfExists(datasourcePath);
            Files.deleteIfExists(contractPath);
        }
    }
}

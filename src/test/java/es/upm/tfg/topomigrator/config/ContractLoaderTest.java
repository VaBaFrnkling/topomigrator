package es.upm.tfg.topomigrator.config;

import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import junit.framework.TestCase;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests exhaustivos para {@link ContractLoader}.
 * Cubre: carga exitosa de YAML real, resolución dinámica de autor
 * (${USERNAME}, ${USER}), fichero inexistente, YAML malformado,
 * YAML vacío, y contrato que viola reglas del ContractValidator.
 */
public class ContractLoaderTest extends TestCase {

    private ContractLoader loader;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        loader = new ContractLoader();
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

    // ═══════════════════════════════════════════════════════════════════
    //  HAPPY PATH — CARGA DEL FICHERO REAL DEL PROYECTO
    // ═══════════════════════════════════════════════════════════════════

    /** Carga el contract.yaml real del proyecto y verifica el parseo completo. */
    public void testLoadRealContractFile() throws Exception {
        Path contractPath = Path.of("configs/contract.yaml");
        MigrationContract contract = loader.load(contractPath);

        assertNotNull(contract);
        assertNotNull(contract.getMigration());
        assertEquals("migracion_relacional_demo", contract.getMigration().getName());
        assertEquals("0.1", contract.getMigration().getVersion());
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
        assertEquals("full", contract.getTables().get("nombre_tabla_1").getMigrationType());
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
}

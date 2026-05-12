package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.*;
import junit.framework.TestCase;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Suite exhaustiva de tests unitarios para {@link ContractValidator}.
 * Cubre todas las ramas de validación: contrato nulo, sección migration,
 * sección tables (source, target, migrationType, incrementalConfig),
 * y orden físico de secciones en el YAML.
 *
 * Convención JUnit 3/4: cada test es un método público que empieza por "test".
 */
public class ContractValidatorTest extends TestCase {

    /** Ruta a un fichero YAML temporal con orden de secciones correcto. */
    private Path validOrderFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Fichero temporal con el orden correcto: migration antes de tables
        validOrderFile = writeTempYaml(
                "migration:\n  name: test\n  version: '1'\ntables:\n  t1:\n    source:\n      table: s\n"
        );
    }

    @Override
    protected void tearDown() throws Exception {
        if (validOrderFile != null) {
            Files.deleteIfExists(validOrderFile);
        }
        super.tearDown();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS DE CONSTRUCCIÓN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Crea un fichero YAML temporal con el contenido proporcionado.
     */
    private Path writeTempYaml(String content) throws IOException {
        Path path = Files.createTempFile("contract-test-", ".yaml");
        try (FileWriter w = new FileWriter(path.toFile())) {
            w.write(content);
        }
        return path;
    }

    /**
     * Construye un MigrationInfo mínimo válido.
     */
    private MigrationInfo buildMigrationInfo(String name, String version) {
        MigrationInfo info = new MigrationInfo();
        info.setName(name);
        info.setVersion(version);
        return info;
    }

    /**
     * Construye una TableRef con schema y table.
     */
    private TableRef buildTableRef(String schema, String table) {
        TableRef ref = new TableRef();
        ref.setSchema(schema);
        ref.setTable(table);
        return ref;
    }

    /**
     * Construye una TableMigration mínima válida (full, sin transformaciones).
     */
    private TableMigration buildMinimalTable() {
        TableMigration t = new TableMigration();
        t.setSource(buildTableRef("public", "origen"));
        t.setTarget(buildTableRef("public", "destino"));
        t.setMigrationType("full");
        t.setEnabled(true);
        return t;
    }

    /** Construye una TableMigration incremental minima valida. */
    private TableMigration buildIncrementalTable() {
        TableMigration t = buildMinimalTable();
        t.setMigrationType("incremental");
        IncrementalConfig ic = new IncrementalConfig();
        ic.setColumn("fecha");
        ic.setStartValue("2026-01-01T00:00:00");
        t.setIncrementalConfig(ic);
        return t;
    }

    /**
     * Construye un MigrationContract mínimo completo y válido.
     */
    private MigrationContract buildValidContract() {
        MigrationContract c = new MigrationContract();
        c.setMigration(buildMigrationInfo("test_migration", "1.0"));
        Map<String, TableMigration> tables = new HashMap<>();
        tables.put("tabla_demo", buildMinimalTable());
        c.setTables(tables);
        return c;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  1. CONTRATO NULO
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un contrato nulo lanza InvalidContractException. */
    public void testNullContractThrows() {
        try {
            ContractValidator.validate(null, validOrderFile);
            fail("Se esperaba InvalidContractException al pasar un contrato nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("nulo"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  2. SECCIÓN 'migration'
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que la ausencia de la sección migration provoca excepción. */
    public void testNullMigrationSectionThrows() {
        MigrationContract c = buildValidContract();
        c.setMigration(null);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException al no tener sección 'migration'.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migration"));
        }
    }

    /** Prueba que un nombre de migración nulo lanza excepción. */
    public void testNullMigrationNameThrows() {
        MigrationContract c = buildValidContract();
        c.getMigration().setName(null);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por nombre nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migration.name"));
        }
    }

    /** Prueba que un nombre de migración vacío lanza excepción. */
    public void testEmptyMigrationNameThrows() {
        MigrationContract c = buildValidContract();
        c.getMigration().setName("");
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por nombre vacío.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migration.name"));
        }
    }

    /** Prueba que un nombre de migración compuesto solo por espacios lanza excepción. */
    public void testWhitespaceMigrationNameThrows() {
        MigrationContract c = buildValidContract();
        c.getMigration().setName("   ");
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por nombre solo de espacios.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migration.name"));
        }
    }

    /** Prueba que una versión nula lanza excepción. */
    public void testNullMigrationVersionThrows() {
        MigrationContract c = buildValidContract();
        c.getMigration().setVersion(null);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por versión nula.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migration.version"));
        }
    }

    /** Prueba que una versión vacía lanza excepción. */
    public void testEmptyMigrationVersionThrows() {
        MigrationContract c = buildValidContract();
        c.getMigration().setVersion("");
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por versión vacía.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migration.version"));
        }
    }

    /** Prueba que una versión compuesta solo por espacios lanza excepción. */
    public void testWhitespaceMigrationVersionThrows() {
        MigrationContract c = buildValidContract();
        c.getMigration().setVersion("   ");
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por versión de espacios.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migration.version"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  3. SECCIÓN 'tables' — PRESENCIA
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un mapa de tablas nulo lanza excepción. */
    public void testNullTablesMapThrows() {
        MigrationContract c = buildValidContract();
        c.setTables(null);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por tablas nulas.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("tabla"));
        }
    }

    /** Prueba que un mapa de tablas vacío lanza excepción. */
    public void testEmptyTablesMapThrows() {
        MigrationContract c = buildValidContract();
        c.setTables(new HashMap<>());
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por mapa de tablas vacío.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("tabla"));
        }
    }

    /** Prueba que una definición de tabla nula (valor del mapa) lanza excepción. */
    public void testNullTableDefinitionThrows() {
        MigrationContract c = buildValidContract();
        Map<String, TableMigration> tables = new HashMap<>();
        tables.put("tabla_rota", null);
        c.setTables(tables);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por definición de tabla nula.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("tabla_rota"));
            assertTrue(e.getMessage().contains("nula"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  4. SECCIÓN 'tables' — SOURCE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un source nulo lanza excepción. */
    public void testNullSourceThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setSource(null);
        c.getTables().put("tabla_sin_source", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por source nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("source.table"));
        }
    }

    /** Prueba que un source.table nulo lanza excepción. */
    public void testNullSourceTableThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setSource(buildTableRef("public", null));
        c.getTables().put("tabla_source_null", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por source.table nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("source.table"));
        }
    }

    /** Prueba que un source.table vacío lanza excepción. */
    public void testEmptySourceTableThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setSource(buildTableRef("public", ""));
        c.getTables().put("tabla_source_empty", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por source.table vacío.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("source.table"));
        }
    }

    /** Prueba que un source.table de solo espacios lanza excepción. */
    public void testWhitespaceSourceTableThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setSource(buildTableRef("public", "   "));
        c.getTables().put("tabla_source_ws", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por source.table de espacios.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("source.table"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5. SECCIÓN 'tables' — TARGET
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un target nulo lanza excepción. */
    public void testNullTargetThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setTarget(null);
        c.getTables().put("tabla_sin_target", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por target nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("target.table"));
        }
    }

    /** Prueba que un target.table nulo lanza excepción. */
    public void testNullTargetTableThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setTarget(buildTableRef("public", null));
        c.getTables().put("tabla_target_null", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por target.table nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("target.table"));
        }
    }

    /** Prueba que un target.table vacío lanza excepción. */
    public void testEmptyTargetTableThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setTarget(buildTableRef("public", ""));
        c.getTables().put("tabla_target_empty", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por target.table vacío.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("target.table"));
        }
    }

    /** Prueba que un target.table de solo espacios lanza excepción. */
    public void testWhitespaceTargetTableThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setTarget(buildTableRef("public", "   "));
        c.getTables().put("tabla_target_ws", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por target.table de espacios.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("target.table"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  6. SECCIÓN 'tables' — MIGRATION TYPE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un migrationType nulo lanza excepción. */
    public void testNullMigrationTypeThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType(null);
        c.getTables().put("tabla_tipo_null", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por migrationType nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migrationType"));
        }
    }

    /** Prueba que un migrationType vacío lanza excepción. */
    public void testEmptyMigrationTypeThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("");
        c.getTables().put("tabla_tipo_empty", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por migrationType vacío.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migrationType"));
        }
    }

    /** Prueba que un migrationType de solo espacios lanza excepción. */
    public void testWhitespaceMigrationTypeThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("   ");
        c.getTables().put("tabla_tipo_ws", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por migrationType de espacios.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("migrationType"));
        }
    }

    /** Prueba que un valor no permitido ("partial") lanza excepción. */
    public void testInvalidMigrationTypeValueThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("partial");
        c.getTables().put("tabla_tipo_invalid", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por migrationType inválido.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("Valor no permitido"));
            assertTrue(e.getMessage().contains("full"));
            assertTrue(e.getMessage().contains("incremental"));
        }
    }

    /** Prueba que "full" es aceptado correctamente (happy path). */
    public void testFullMigrationTypePasses() {
        MigrationContract c = buildValidContract();
        // La tabla por defecto ya tiene migrationType = "full"
        ContractValidator.validate(c, validOrderFile);
        // Llegamos aquí sin excepción = test superado
    }

    /** Prueba que "incremental" con incrementalConfig es aceptado. */
    public void testIncrementalWithConfigPasses() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("incremental");
        IncrementalConfig ic = new IncrementalConfig();
        ic.setColumn("fecha");
        ic.setType("timestamp");
        ic.setStartValue("2026-01-01T00:00:00");
        t.setIncrementalConfig(ic);
        c.getTables().put("tabla_inc", t);
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que "incremental" SIN incrementalConfig lanza excepción. */
    public void testIncrementalWithoutConfigThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("incremental");
        // No se setea incrementalConfig
        c.getTables().put("tabla_inc_sin_cfg", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por incremental sin config.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("incrementalConfig"));
        }
    }

    /** Prueba que incrementalConfig.column nulo lanza excepcion. */
    public void testIncrementalWithoutColumnThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildIncrementalTable();
        t.getIncrementalConfig().setColumn(null);
        c.getTables().put("tabla_inc_sin_columna", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por incrementalConfig.column nulo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("incrementalConfig.column"));
        }
    }

    /** Prueba que incrementalConfig.startValue vacio lanza excepcion. */
    public void testIncrementalWithEmptyStartValueThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildIncrementalTable();
        t.getIncrementalConfig().setStartValue("   ");
        c.getTables().put("tabla_inc_sin_inicio", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por incrementalConfig.startValue vacio.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("incrementalConfig.startValue"));
        }
    }

    /** Prueba que batchSize no positivo lanza excepcion. */
    public void testIncrementalWithNonPositiveBatchSizeThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildIncrementalTable();
        t.getIncrementalConfig().setBatchSize(0);
        c.getTables().put("tabla_inc_batch_cero", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por incrementalConfig.batchSize no positivo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("incrementalConfig.batchSize"));
        }
    }

    /** Prueba que loadStrategy no permitida lanza excepcion. */
    public void testIncrementalWithInvalidLoadStrategyThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildIncrementalTable();
        t.getIncrementalConfig().setLoadStrategy("merge");
        c.getTables().put("tabla_inc_strategy_invalida", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por incrementalConfig.loadStrategy invalida.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("incrementalConfig.loadStrategy"));
        }
    }

    /** Prueba que idempotencyKeyColumns vacio lanza excepcion si se declara. */
    public void testIncrementalWithEmptyIdempotencyKeyColumnsThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildIncrementalTable();
        t.getIncrementalConfig().setIdempotencyKeyColumns(Collections.emptyList());
        c.getTables().put("tabla_inc_keys_vacias", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por idempotencyKeyColumns vacio.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("idempotencyKeyColumns"));
        }
    }

    /** Prueba que idempotencyKeyColumns con columna vacia lanza excepcion. */
    public void testIncrementalWithBlankIdempotencyKeyColumnThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildIncrementalTable();
        t.getIncrementalConfig().setIdempotencyKeyColumns(Arrays.asList("id", " "));
        c.getTables().put("tabla_inc_key_blanca", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por columna vacia en idempotencyKeyColumns.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("idempotencyKeyColumns"));
        }
    }

    /** Prueba que "full" con incrementalConfig (innecesaria) NO lanza excepción. */
    public void testFullWithIncrementalConfigPasses() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("full");
        IncrementalConfig ic = new IncrementalConfig();
        ic.setColumn("id");
        ic.setStartValue("2026-01-01T00:00:00");
        t.setIncrementalConfig(ic);
        c.getTables().put("tabla_full_con_inc", t);
        // No debería fallar: se ignora el bloque incremental si el tipo es full
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que el migrationType es case-insensitive: "FULL" debe aceptarse. */
    public void testMigrationTypeUppercaseFull() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("FULL");
        c.getTables().put("tabla_uppercase", t);
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que el migrationType es case-insensitive: "InCreMenTal" debe aceptarse. */
    public void testMigrationTypeMixedCaseIncremental() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("InCreMenTal");
        IncrementalConfig ic = new IncrementalConfig();
        ic.setColumn("id");
        ic.setStartValue("2026-01-01T00:00:00");
        t.setIncrementalConfig(ic);
        c.getTables().put("tabla_mixcase", t);
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que un migrationType con espacios laterales es normalizado correctamente. */
    public void testMigrationTypeWithLeadingTrailingSpaces() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("  full  ");
        c.getTables().put("tabla_spaces", t);
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una tabla inactiva malformada no bloquea la validacion del contrato. */
    public void testInactiveMalformedTableIsIgnoredByTableLevelValidation() {
        MigrationContract c = buildValidContract();
        TableMigration inactive = new TableMigration();
        inactive.setEnabled(false);
        inactive.setSource(null);
        inactive.setTarget(null);
        inactive.setMigrationType("unsupported");

        c.getTables().put("tabla_inactiva_rota", inactive);

        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que un contrato sin tablas activas falla con mensaje explicito. */
    public void testAllInactiveTablesThrowNoActiveTables() {
        MigrationContract c = buildValidContract();
        for (TableMigration table : c.getTables().values()) {
            table.setEnabled(false);
        }

        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException al no haber tablas activas.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("tablas activas"));
        }
    }

    /** Prueba que omitir enabled equivale a tabla inactiva por el boolean primitivo. */
    public void testOmittedEnabledDefaultsToInactiveAndThrowsWhenNoActiveTables() {
        MigrationContract c = buildValidContract();
        TableMigration omittedEnabled = new TableMigration();
        omittedEnabled.setSource(buildTableRef("public", "origen"));
        omittedEnabled.setTarget(buildTableRef("public", "destino"));
        omittedEnabled.setMigrationType("full");

        Map<String, TableMigration> tables = new HashMap<>();
        tables.put("tabla_sin_enabled", omittedEnabled);
        c.setTables(tables);

        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException porque enabled omitido/default false no es activo.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("tablas activas"));
        }
    }

    /** Prueba que el contrato filtrado para downstream contiene solo tablas activas. */
    public void testRetainEnabledTablesExcludesInactiveTablesForDownstreamFlow() {
        MigrationContract c = buildValidContract();
        TableMigration inactive = buildMinimalTable();
        inactive.setEnabled(false);
        c.getTables().put("tabla_inactiva", inactive);

        MigrationContract filtered = es.upm.tfg.topomigrator.util.MigrationContractUtils.retainEnabledTables(c);

        assertTrue(filtered.getTables().containsKey("tabla_demo"));
        assertFalse(filtered.getTables().containsKey("tabla_inactiva"));
        assertEquals(1, filtered.getTables().size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  12. HAPPY PATH COMPLETO — INTEGRACIÓN LIGERA CON FICHERO REAL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Prueba de integración ligera: carga el fichero configs/contract.yaml real
     * y valida que pasa todas las comprobaciones del ContractValidator.
     */
    public void testRealContractFilePasses() {
        try {
            Path contractPath = Path.of("configs/contract.yaml");
            Path datasourcePath = writeTempYaml("source:\n" +
                    "  driver: org.postgresql.Driver\n" +
                    "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                    "  databaseType: PostgreSQL\n" +
                    "  jdbcUrl: jdbc:postgresql://localhost:5432/source_test\n" +
                    "  username: source_user\n" +
                    "  password: source_password\n" +
                    "target:\n" +
                    "  driver: org.postgresql.Driver\n" +
                    "  driverLocation: /opt/nifi/drivers/postgresql-42.7.10.jar\n" +
                    "  databaseType: PostgreSQL\n" +
                    "  jdbcUrl: jdbc:postgresql://localhost:5432/target_test\n" +
                    "  username: target_user\n" +
                    "  password: target_password\n");
            es.upm.tfg.topomigrator.config.ContractLoader loader = new es.upm.tfg.topomigrator.config.ContractLoader(datasourcePath);
            MigrationContract contract = loader.load(contractPath);

            assertNotNull("El contrato parseado no debería ser nulo", contract);
            assertNotNull("La sección 'migration' debe existir", contract.getMigration());
            assertNotNull("Deben haberse cargado las tablas", contract.getTables());
            assertFalse("Debe haber al menos una tabla", contract.getTables().isEmpty());

        } catch (Exception e) {
            fail("La validación del contract.yaml real falló: " + e.getMessage());
        }
    }
}

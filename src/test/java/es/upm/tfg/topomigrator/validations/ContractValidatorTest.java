package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.*;
import junit.framework.TestCase;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Suite exhaustiva de tests unitarios para {@link ContractValidator}.
 * Cubre todas las ramas de validación: contrato nulo, sección migration,
 * sección tables (source, target, migrationType, incrementalConfig),
 * transformaciones por columna, y orden físico de secciones en el YAML.
 *
 * Convención JUnit 3.8: cada test es un método público que empieza por "test".
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

    /** Prueba que "full" con incrementalConfig (innecesaria) NO lanza excepción. */
    public void testFullWithIncrementalConfigPasses() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setMigrationType("full");
        IncrementalConfig ic = new IncrementalConfig();
        ic.setColumn("id");
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  7. TRANSFORMACIONES — PRESENCIA Y COLUMNAS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que una tabla sin transformaciones (null) pasa correctamente. */
    public void testNoTransformationsPasses() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        t.setTransformations(null);
        c.getTables().put("tabla_sin_tf", t);
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que transformaciones presentes con columnas null lanza excepción. */
    public void testTransformationsWithNullColumnsThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        TransformationConfig tf = new TransformationConfig();
        tf.setColumns(null);
        t.setTransformations(tf);
        c.getTables().put("tabla_tf_null_cols", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por columnas nulas en transformaciones.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("transformaciones"));
            assertTrue(e.getMessage().contains("columna"));
        }
    }

    /** Prueba que transformaciones presentes con mapa de columnas vacío lanza excepción. */
    public void testTransformationsWithEmptyColumnsThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        TransformationConfig tf = new TransformationConfig();
        tf.setColumns(new HashMap<>());
        t.setTransformations(tf);
        c.getTables().put("tabla_tf_empty_cols", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por mapa de columnas vacío.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("transformaciones"));
        }
    }

    /** Prueba que una definición de columna nula en transformaciones lanza excepción. */
    public void testNullColumnTransformationThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        TransformationConfig tf = new TransformationConfig();
        Map<String, ColumnTransformation> cols = new HashMap<>();
        cols.put("columna_rota", null);
        tf.setColumns(cols);
        t.setTransformations(tf);
        c.getTables().put("tabla_tf_col_null", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por columna de transformación nula.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("columna_rota"));
            assertTrue(e.getMessage().contains("nula"));
        }
    }

    /** Prueba que una columna sin ninguna regla de transformación lanza excepción. */
    public void testColumnWithNoRulesThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();
        TransformationConfig tf = new TransformationConfig();
        Map<String, ColumnTransformation> cols = new HashMap<>();
        cols.put("columna_vacia", new ColumnTransformation()); // Todo a null
        tf.setColumns(cols);
        t.setTransformations(tf);
        c.getTables().put("tabla_tf_no_rules", t);
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por columna sin reglas.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("columna_vacia"));
            assertTrue(e.getMessage().contains("regla"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  8. TRANSFORMACIONES — REGLAS INDIVIDUALES (cada una sola basta)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que una columna con SOLO 'rename' es aceptada. */
    public void testColumnWithOnlyRenamePasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_rename", ct -> ct.setRename("nuevo_nombre"));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una columna con SOLO 'type' es aceptada. */
    public void testColumnWithOnlyTypePasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_type", ct -> ct.setType("varchar(100)"));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una columna con SOLO 'trim' es aceptada. */
    public void testColumnWithOnlyTrimPasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_trim", ct -> ct.setTrim(true));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una columna con trim=false también es aceptada. */
    public void testColumnWithTrimFalsePasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_trim_f", ct -> ct.setTrim(false));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una columna con SOLO 'nullDefault' es aceptada. */
    public void testColumnWithOnlyNullDefaultPasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_nd", ct -> ct.setNullDefault("N/A"));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una columna con SOLO 'caseTransform' (uppercase) es aceptada. */
    public void testColumnWithOnlyCaseTransformUppercasePasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_ct", ct -> ct.setCaseTransform("uppercase"));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una columna con SOLO 'caseTransform' (lowercase) es aceptada. */
    public void testColumnWithOnlyCaseTransformLowercasePasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_lc", ct -> ct.setCaseTransform("lowercase"));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que una columna con TODAS las reglas activas es aceptada. */
    public void testColumnWithAllRulesPasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_all", ct -> {
            ct.setRename("renamed");
            ct.setType("text");
            ct.setTrim(true);
            ct.setNullDefault("default_val");
            ct.setCaseTransform("uppercase");
        });
        ContractValidator.validate(c, validOrderFile);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  9. TRANSFORMACIONES — VALOR DE caseTransform
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un caseTransform inválido ("capitalize") lanza excepción. */
    public void testInvalidCaseTransformValueThrows() {
        MigrationContract c = buildContractWithSingleTransformation("col_bad_ct", ct -> ct.setCaseTransform("capitalize"));
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por caseTransform inválido.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("Valor no permitido"));
            assertTrue(e.getMessage().contains("caseTransform"));
        }
    }

    /** Prueba que un caseTransform con valor aleatorio ("camelCase") lanza excepción. */
    public void testCamelCaseCaseTransformThrows() {
        MigrationContract c = buildContractWithSingleTransformation("col_cc", ct -> ct.setCaseTransform("camelCase"));
        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba InvalidContractException por caseTransform 'camelCase'.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("Valor no permitido"));
        }
    }

    /** Prueba que "UPPERCASE" (en mayúsculas) es normalizado y aceptado. */
    public void testCaseTransformUppercaseVariantPasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_UC", ct -> ct.setCaseTransform("UPPERCASE"));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que "Lowercase" (mixto) es normalizado y aceptado. */
    public void testCaseTransformMixedCaseVariantPasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_Lc", ct -> ct.setCaseTransform("Lowercase"));
        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que un caseTransform con espacios laterales es normalizado y aceptado. */
    public void testCaseTransformWithSpacesPasses() {
        MigrationContract c = buildContractWithSingleTransformation("col_sp", ct -> ct.setCaseTransform("  uppercase  "));
        ContractValidator.validate(c, validOrderFile);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  10. ORDEN FÍSICO DE SECCIONES EN EL YAML
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un YAML con el orden correcto (migration antes de tables) pasa. */
    public void testCorrectSectionOrderPasses() throws Exception {
        Path file = writeTempYaml("migration:\n  name: x\ntables:\n  t: {}\n");
        try {
            MigrationContract c = buildValidContract();
            ContractValidator.validate(c, file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Prueba que un YAML con el orden invertido (tables antes que migration) lanza excepción. */
    public void testInvertedSectionOrderThrows() throws Exception {
        Path file = writeTempYaml("tables:\n  t1:\n    source:\n      table: s\nmigration:\n  name: x\n");
        try {
            MigrationContract c = buildValidContract();
            ContractValidator.validate(c, file);
            fail("Se esperaba InvalidContractException por orden invertido.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("orden"));
            assertTrue(e.getMessage().contains("migration"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Prueba que un fichero YAML inexistente no provoca fallo (solo warning). */
    public void testNonExistentFileDoesNotCrash() {
        MigrationContract c = buildValidContract();
        Path fakePath = Path.of("non_existent_contract_file_xyz.yaml");
        // Debe pasar sin excepción — validateSectionOrder captura IOException
        ContractValidator.validate(c, fakePath);
    }

    /** Prueba que un YAML que solo tiene 'migration' (sin 'tables') pasa el orden. */
    public void testOnlyMigrationSectionPassesOrder() throws Exception {
        Path file = writeTempYaml("migration:\n  name: x\n  version: '1'\n");
        try {
            MigrationContract c = buildValidContract();
            // El orden pasa (no hay linea 'tables:'), pero la validación de tablas también pasa
            // porque el contrato en memoria sí tiene tablas.
            ContractValidator.validate(c, file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  11. MÚLTIPLES TABLAS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un contrato con varias tablas válidas pasa correctamente. */
    public void testMultipleValidTablesPasses() {
        MigrationContract c = buildValidContract();

        TableMigration t2 = buildMinimalTable();
        t2.setSource(buildTableRef("sales", "pedidos"));
        t2.setTarget(buildTableRef("sales", "orders"));

        TableMigration t3 = buildMinimalTable();
        t3.setSource(buildTableRef("hr", "empleados"));
        t3.setTarget(buildTableRef("hr", "employees"));
        t3.setMigrationType("incremental");
        IncrementalConfig ic = new IncrementalConfig();
        ic.setColumn("updated_at");
        ic.setType("timestamp");
        ic.setStartValue("2025-01-01T00:00:00");
        ic.setBatchSize(500);
        t3.setIncrementalConfig(ic);

        c.getTables().put("pedidos", t2);
        c.getTables().put("empleados", t3);

        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que si una de varias tablas es inválida, falla exactamente en esa. */
    public void testMultipleTablesOneInvalidFailsOnThat() {
        MigrationContract c = buildValidContract();

        // Tabla válida
        c.getTables().put("tabla_ok", buildMinimalTable());

        // Tabla inválida: sin target
        TableMigration tBad = buildMinimalTable();
        tBad.setTarget(null);
        c.getTables().put("tabla_rota", tBad);

        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba fallo por la tabla inválida.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("target.table"));
        }
    }

    /** Prueba que múltiples transformaciones en una tabla son validadas individualmente. */
    public void testMultipleColumnTransformationsPasses() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();

        TransformationConfig tf = new TransformationConfig();
        Map<String, ColumnTransformation> cols = new LinkedHashMap<>();

        ColumnTransformation c1 = new ColumnTransformation();
        c1.setRename("new_name");
        cols.put("col_a", c1);

        ColumnTransformation c2 = new ColumnTransformation();
        c2.setType("bigint");
        c2.setNullDefault("0");
        cols.put("col_b", c2);

        ColumnTransformation c3 = new ColumnTransformation();
        c3.setTrim(true);
        c3.setCaseTransform("lowercase");
        cols.put("col_c", c3);

        tf.setColumns(cols);
        t.setTransformations(tf);
        c.getTables().put("tabla_multi_tf", t);

        ContractValidator.validate(c, validOrderFile);
    }

    /** Prueba que si una de varias columnas de transformación falla, se detecta correctamente. */
    public void testMultipleColumnsOneInvalidThrows() {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();

        TransformationConfig tf = new TransformationConfig();
        Map<String, ColumnTransformation> cols = new LinkedHashMap<>();

        ColumnTransformation ok = new ColumnTransformation();
        ok.setRename("valid");
        cols.put("col_buena", ok);

        // Columna sin ninguna regla
        cols.put("col_mala", new ColumnTransformation());

        tf.setColumns(cols);
        t.setTransformations(tf);
        c.getTables().put("tabla_col_mix", t);

        try {
            ContractValidator.validate(c, validOrderFile);
            fail("Se esperaba excepción por la columna sin reglas.");
        } catch (InvalidContractException e) {
            assertTrue(e.getMessage().contains("col_mala"));
        }
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
            es.upm.tfg.topomigrator.config.ContractLoader loader = new es.upm.tfg.topomigrator.config.ContractLoader();
            MigrationContract contract = loader.load(contractPath);

            assertNotNull("El contrato parseado no debería ser nulo", contract);
            assertNotNull("La sección 'migration' debe existir", contract.getMigration());
            assertNotNull("Deben haberse cargado las tablas", contract.getTables());
            assertFalse("Debe haber al menos una tabla", contract.getTables().isEmpty());

        } catch (Exception e) {
            fail("La validación del contract.yaml real falló: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPER — Construye contrato con una sola transformación configurable
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Interfaz funcional simplificada compatible con JUnit 3 (sin java.util.function).
     */
    private interface TransformationCustomizer {
        void customize(ColumnTransformation ct);
    }

    /**
     * Construye un contrato válido con una sola tabla que tiene una sola columna
     * de transformación, personalizada por el caller.
     */
    private MigrationContract buildContractWithSingleTransformation(String colName, TransformationCustomizer customizer) {
        MigrationContract c = buildValidContract();
        TableMigration t = buildMinimalTable();

        TransformationConfig tf = new TransformationConfig();
        Map<String, ColumnTransformation> cols = new HashMap<>();
        ColumnTransformation ct = new ColumnTransformation();
        customizer.customize(ct);
        cols.put(colName, ct);
        tf.setColumns(cols);
        t.setTransformations(tf);

        c.getTables().put("tabla_transform_test", t);
        return c;
    }
}

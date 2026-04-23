package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.*;
import junit.framework.TestCase;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Suite exhaustiva de tests unitarios para {@link TargetChangelogValidator}.
 * Cubre todas las ramas: contrato nulo, directorio inexistente, archivo
 * de changelog ausente, formato YAML inválido, raíz 'databaseChangeLog'
 * ausente, convención de nombre con prefijo, y happy path.
 *
 * Los tests crean y eliminan archivos temporales en el directorio
 * changelogs/tables/ del proyecto para simular los distintos escenarios.
 */
public class TargetChangelogValidatorTest extends TestCase {

    /** Directorio real donde TargetChangelogValidator busca changelogs (por defecto). */
    private static final Path CHANGELOGS_DIR = Paths.get("changelogs", "tables");

    /** Archivos temporales creados durante los tests, para limpieza en tearDown. */
    private Path tempFileCreated;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempFileCreated = null;
        // Aseguramos que el directorio exista
        if (!Files.exists(CHANGELOGS_DIR)) {
            Files.createDirectories(CHANGELOGS_DIR);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        // Limpiar archivos temporales creados por cada test
        if (tempFileCreated != null && Files.exists(tempFileCreated)) {
            Files.deleteIfExists(tempFileCreated);
        }
        super.tearDown();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Construye un contrato mínimo con una sola tabla cuyo target.table es el nombre dado.
     */
    private MigrationContract buildContractWithTargetTable(String targetTableName) {
        MigrationContract c = new MigrationContract();
        MigrationInfo mi = new MigrationInfo();
        mi.setName("test");
        mi.setVersion("1.0");
        c.setMigration(mi);

        TableMigration t = new TableMigration();
        TableRef source = new TableRef();
        source.setSchema("public");
        source.setTable("source_table");
        t.setSource(source);

        TableRef target = new TableRef();
        target.setSchema("public");
        target.setTable(targetTableName);
        t.setTarget(target);

        t.setMigrationType("full");
        t.setEnabled(true);

        Map<String, TableMigration> tables = new HashMap<>();
        tables.put(targetTableName, t);
        c.setTables(tables);

        return c;
    }

    /**
     * Escribe un archivo temporal en el directorio de changelogs con el contenido especificado.
     */
    private Path writeChangelogFile(String fileName, String content) throws IOException {
        Path filePath = CHANGELOGS_DIR.resolve(fileName);
        try (FileWriter w = new FileWriter(filePath.toFile())) {
            w.write(content);
        }
        tempFileCreated = filePath;
        return filePath;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  1. CONTRATO NULO / TABLAS NULAS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un contrato nulo lanza InvalidChangelogException. */
    public void testNullContractThrows() {
        try {
            TargetChangelogValidator.validate(null);
            fail("Se esperaba InvalidChangelogException por contrato nulo.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("nulo"));
        }
    }

    /** Prueba que un contrato sin tablas lanza InvalidChangelogException. */
    public void testContractWithNullTablesThrows() {
        MigrationContract c = new MigrationContract();
        c.setTables(null);
        try {
            TargetChangelogValidator.validate(c);
            fail("Se esperaba InvalidChangelogException por tablas nulas.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("nulo") || e.getMessage().contains("tablas"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  2. ARCHIVO DE CHANGELOG AUSENTE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que si no existe el changelog para una tabla destino, lanza excepción. */
    public void testMissingChangelogFileThrows() {
        // Usamos un nombre de tabla para el que seguro no existe changelog
        MigrationContract c = buildContractWithTargetTable("tabla_inexistente_xyzzy");
        try {
            TargetChangelogValidator.validate(c);
            fail("Se esperaba InvalidChangelogException por changelog ausente.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("tabla_inexistente_xyzzy"));
            assertTrue(e.getMessage().contains("No se ha encontrado"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  3. HAPPY PATH — CONVENCIÓN DIRECTA (tabla.yaml)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un changelog válido con convención directa (tabla.yaml) pasa correctamente. */
    public void testValidChangelogDirectConventionPasses() throws Exception {
        String tableName = "test_direct_conv";
        writeChangelogFile(tableName + ".yaml",
                "databaseChangeLog:\n  - changeSet:\n      id: 1\n      author: test\n      changes:\n        - createTable:\n            tableName: " + tableName + "\n"
        );

        MigrationContract c = buildContractWithTargetTable(tableName);
        TargetChangelogValidator.validate(c);
        // Si llega aquí sin excepción = test superado
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  4. HAPPY PATH — CONVENCIÓN CON PREFIJO (changelog-tabla.yaml)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un changelog válido con convención prefijada (changelog-tabla.yaml) pasa. */
    public void testValidChangelogPrefixConventionPasses() throws Exception {
        String tableName = "test_prefix_conv";
        writeChangelogFile("changelog-" + tableName + ".yaml",
                "databaseChangeLog:\n  - changeSet:\n      id: 1\n      author: test\n      changes:\n        - createTable:\n            tableName: " + tableName + "\n"
        );

        MigrationContract c = buildContractWithTargetTable(tableName);
        TargetChangelogValidator.validate(c);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5. YAML INVÁLIDO (no se puede parsear)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un archivo que no es YAML válido lanza excepción. */
    public void testInvalidYamlContentThrows() throws Exception {
        String tableName = "test_bad_yaml";
        writeChangelogFile(tableName + ".yaml",
                "esto: no: es: yaml: valido:\n  [[invalido\n"
        );

        MigrationContract c = buildContractWithTargetTable(tableName);
        try {
            TargetChangelogValidator.validate(c);
            fail("Se esperaba InvalidChangelogException por YAML inválido.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("parsear") || e.getMessage().contains("Error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  6. YAML VÁLIDO PERO SIN RAÍZ 'databaseChangeLog'
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un YAML sin la clave raíz 'databaseChangeLog' lanza excepción. */
    public void testYamlWithoutDatabaseChangeLogRootThrows() throws Exception {
        String tableName = "test_no_root";
        writeChangelogFile(tableName + ".yaml",
                "someOtherKey:\n  - id: 1\n    author: test\n"
        );

        MigrationContract c = buildContractWithTargetTable(tableName);
        try {
            TargetChangelogValidator.validate(c);
            fail("Se esperaba InvalidChangelogException por falta de 'databaseChangeLog'.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("databaseChangeLog"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  7. YAML CON CONTENIDO ESCALAR (no es un Map)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un YAML que es solo un string (no un Map) lanza excepción. */
    public void testYamlScalarContentThrows() throws Exception {
        String tableName = "test_scalar";
        writeChangelogFile(tableName + ".yaml",
                "esto es solo una cadena de texto plano sin estructura YAML de mapa\n"
        );

        MigrationContract c = buildContractWithTargetTable(tableName);
        try {
            TargetChangelogValidator.validate(c);
            fail("Se esperaba InvalidChangelogException por contenido escalar.");
        } catch (InvalidChangelogException e) {
            assertTrue(e.getMessage().contains("estructura YAML válida") || e.getMessage().contains("Error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  8. ARCHIVO VACÍO
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un archivo changelog vacío lanza excepción. */
    public void testEmptyChangelogFileThrows() throws Exception {
        String tableName = "test_empty_file";
        writeChangelogFile(tableName + ".yaml", "");

        MigrationContract c = buildContractWithTargetTable(tableName);
        try {
            TargetChangelogValidator.validate(c);
            fail("Se esperaba InvalidChangelogException por archivo vacío.");
        } catch (InvalidChangelogException e) {
            // Un YAML vacío se parsea como null, lo cual no es instanceof Map
            assertTrue(e.getMessage().contains("estructura YAML válida") || e.getMessage().contains("Error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  9. MÚLTIPLES TABLAS — TODAS CON CHANGELOG VÁLIDO
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que un contrato con varias tablas, todas con su changelog, pasa correctamente. */
    public void testMultipleTablesAllChangelogsPresentPasses() throws Exception {
        // Limpiaremos manualmente estos ficheros
        Path file1 = null, file2 = null;
        try {
            String table1 = "test_multi_a";
            String table2 = "test_multi_b";

            file1 = writeChangelogFile(table1 + ".yaml",
                    "databaseChangeLog:\n  - changeSet:\n      id: 1\n      author: t\n      changes: []\n");
            // Reseteamos tempFileCreated para poder crear un segundo
            tempFileCreated = null;
            file2 = writeChangelogFile(table2 + ".yaml",
                    "databaseChangeLog:\n  - changeSet:\n      id: 1\n      author: t\n      changes: []\n");

            MigrationContract c = buildContractWithTargetTable(table1);

            // Añadimos segunda tabla
            TableMigration t2 = new TableMigration();
            TableRef src2 = new TableRef();
            src2.setSchema("public");
            src2.setTable("src_b");
            t2.setSource(src2);
            TableRef tgt2 = new TableRef();
            tgt2.setSchema("public");
            tgt2.setTable(table2);
            t2.setTarget(tgt2);
            t2.setMigrationType("full");
            c.getTables().put(table2, t2);

            TargetChangelogValidator.validate(c);

        } finally {
            if (file1 != null) Files.deleteIfExists(file1);
            if (file2 != null) Files.deleteIfExists(file2);
            tempFileCreated = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  10. MÚLTIPLES TABLAS — UNA SIN CHANGELOG
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prueba que si una de varias tablas no tiene changelog, falla exactamente en esa. */
    public void testMultipleTablesOneMissingChangelogThrows() throws Exception {
        Path file1 = null;
        try {
            String tableOk = "test_multi_ok";
            String tableBad = "test_multi_missing";

            file1 = writeChangelogFile(tableOk + ".yaml",
                    "databaseChangeLog:\n  - changeSet:\n      id: 1\n      author: t\n      changes: []\n");

            MigrationContract c = buildContractWithTargetTable(tableOk);

            // Añadimos tabla sin changelog
            TableMigration t2 = new TableMigration();
            TableRef src2 = new TableRef();
            src2.setSchema("public");
            src2.setTable("src_missing");
            t2.setSource(src2);
            TableRef tgt2 = new TableRef();
            tgt2.setSchema("public");
            tgt2.setTable(tableBad);
            t2.setTarget(tgt2);
            t2.setMigrationType("full");
            c.getTables().put(tableBad, t2);

            try {
                TargetChangelogValidator.validate(c);
                fail("Se esperaba InvalidChangelogException por tabla sin changelog.");
            } catch (InvalidChangelogException e) {
                assertTrue(e.getMessage().contains(tableBad));
            }

        } finally {
            if (file1 != null) Files.deleteIfExists(file1);
            tempFileCreated = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  11. HAPPY PATH — CHANGELOG REAL DEL PROYECTO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Prueba de integración ligera: valida que el changelog real 'nombre_tabla.yaml'
     * y el contract.yaml del proyecto pasan correctamente TargetChangelogValidator.
     */
    public void testRealProjectChangelogPasses() {
        // El contrato real del proyecto referencia target.table = "nombre_tabla"
        // y existe changelogs/tables/nombre_tabla.yaml
        MigrationContract c = buildContractWithTargetTable("nombre_tabla");
        TargetChangelogValidator.validate(c);
    }
}

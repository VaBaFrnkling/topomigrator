package es.upm.tfg.topomigrator.audit;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tests exhaustivos para {@link ExecutionIdGenerator}.
 * Cubre: formato del ID (exec-NNN), auto-incremento secuencial,
 * generación de tableExecutionId (exec-NNN-tM), persistencia
 * en fichero, arranque desde cero, y lectura tras reinicio.
 */
public class ExecutionIdGeneratorTest extends TestCase {

    private static final Path LAST_ID_FILE = Paths.get("outputs", "traces", ".last_execution_id");

    /** Guardamos el contenido original del fichero para restaurarlo tras los tests. */
    private String originalContent;
    private boolean originalFileExisted;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Guardar estado original para restaurar en tearDown
        originalFileExisted = Files.exists(LAST_ID_FILE);
        if (originalFileExisted) {
            originalContent = Files.readString(LAST_ID_FILE);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        // Restaurar el estado original del fichero
        if (originalFileExisted) {
            Files.writeString(LAST_ID_FILE, originalContent);
        } else {
            Files.deleteIfExists(LAST_ID_FILE);
        }
        super.tearDown();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FORMATO DEL ID DE EJECUCIÓN
    // ═══════════════════════════════════════════════════════════════════

    /** El ID de ejecución tiene el formato 'exec-NNN' con padding de 3 dígitos. */
    public void testExecutionIdFormat() {
        resetSequence(0);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertTrue("Debe empezar por 'exec-'", gen.getExecutionId().startsWith("exec-"));
        assertEquals("exec-001", gen.getExecutionId());
    }

    /** Cuando la secuencia llega a 10, se formatea como 'exec-010'. */
    public void testExecutionIdPaddingDoubleDigit() {
        resetSequence(9);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-010", gen.getExecutionId());
    }

    /** Cuando la secuencia llega a 100, se formatea como 'exec-100'. */
    public void testExecutionIdPaddingTripleDigit() {
        resetSequence(99);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-100", gen.getExecutionId());
    }

    /** Cuando la secuencia supera 999, se extiende naturalmente (exec-1000). */
    public void testExecutionIdBeyondThreeDigits() {
        resetSequence(999);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-1000", gen.getExecutionId());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  AUTO-INCREMENTO SECUENCIAL
    // ═══════════════════════════════════════════════════════════════════

    /** Si no existe el fichero de secuencia, arranca desde exec-001. */
    public void testFirstExecutionStartsAtOne() {
        Files.exists(LAST_ID_FILE);
        try { Files.deleteIfExists(LAST_ID_FILE); } catch (IOException ignored) {}

        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-001", gen.getExecutionId());
    }

    /** La secuencia se incrementa entre instancias sucesivas. */
    public void testSequentialIncrement() {
        resetSequence(0);

        ExecutionIdGenerator gen1 = new ExecutionIdGenerator();
        assertEquals("exec-001", gen1.getExecutionId());

        ExecutionIdGenerator gen2 = new ExecutionIdGenerator();
        assertEquals("exec-002", gen2.getExecutionId());

        ExecutionIdGenerator gen3 = new ExecutionIdGenerator();
        assertEquals("exec-003", gen3.getExecutionId());
    }

    /** Continúa la secuencia desde un valor previo arbitrario. */
    public void testContinuesFromPersistedValue() {
        resetSequence(42);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-043", gen.getExecutionId());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TABLE EXECUTION ID
    // ═══════════════════════════════════════════════════════════════════

    /** El tableExecutionId para la tabla 1 es 'exec-NNN-t1'. */
    public void testTableExecutionIdFirstTable() {
        resetSequence(0);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-001-t1", gen.getTableExecutionId(1));
    }

    /** El tableExecutionId se incrementa con cada tabla. */
    public void testTableExecutionIdSequential() {
        resetSequence(4);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-005-t1", gen.getTableExecutionId(1));
        assertEquals("exec-005-t2", gen.getTableExecutionId(2));
        assertEquals("exec-005-t3", gen.getTableExecutionId(3));
    }

    /** El tableExecutionId funciona con números grandes de tabla. */
    public void testTableExecutionIdLargeOrder() {
        resetSequence(0);
        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-001-t50", gen.getTableExecutionId(50));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PERSISTENCIA
    // ═══════════════════════════════════════════════════════════════════

    /** Tras crear un generador, el fichero de persistencia contiene el número actual. */
    public void testPersistsCurrentNumber() throws Exception {
        resetSequence(0);
        new ExecutionIdGenerator();

        assertTrue("El fichero de persistencia debe existir", Files.exists(LAST_ID_FILE));
        assertEquals("1", Files.readString(LAST_ID_FILE).trim());
    }

    /** El fichero de persistencia se actualiza correctamente con cada ejecución. */
    public void testPersistenceUpdatesOnEachExecution() throws Exception {
        resetSequence(0);
        new ExecutionIdGenerator(); // exec-001
        assertEquals("1", Files.readString(LAST_ID_FILE).trim());

        new ExecutionIdGenerator(); // exec-002
        assertEquals("2", Files.readString(LAST_ID_FILE).trim());

        new ExecutionIdGenerator(); // exec-003
        assertEquals("3", Files.readString(LAST_ID_FILE).trim());
    }

    /** Si el fichero contiene un valor no numérico, reinicia desde exec-001. */
    public void testCorruptedFileResetsSequence() throws Exception {
        Files.createDirectories(LAST_ID_FILE.getParent());
        Files.writeString(LAST_ID_FILE, "corrupted_data");

        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-001", gen.getExecutionId());
    }

    /** Si el fichero está vacío, reinicia desde exec-001. */
    public void testEmptyFileResetsSequence() throws Exception {
        Files.createDirectories(LAST_ID_FILE.getParent());
        Files.writeString(LAST_ID_FILE, "");

        ExecutionIdGenerator gen = new ExecutionIdGenerator();
        assertEquals("exec-001", gen.getExecutionId());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HELPER
    // ═══════════════════════════════════════════════════════════════════

    /** Resetea la secuencia al valor indicado escribiendo el fichero manualmente. */
    private void resetSequence(int value) {
        try {
            Files.createDirectories(LAST_ID_FILE.getParent());
            Files.writeString(LAST_ID_FILE, String.valueOf(value));
        } catch (IOException e) {
            fail("No se pudo resetear la secuencia: " + e.getMessage());
        }
    }
}

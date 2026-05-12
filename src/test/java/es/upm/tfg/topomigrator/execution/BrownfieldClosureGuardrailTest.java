package es.upm.tfg.topomigrator.execution;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class BrownfieldClosureGuardrailTest extends TestCase {

    public void testProductionCodeDoesNotReferenceLegacySourceQueryOrIncrementalStores() throws Exception {
        List<Path> productionFiles = Files.walk(Path.of("src/main/java"))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .toList();

        for (Path file : productionFiles) {
            String normalized = file.toString().replace('\\', '/');
            assertFalse("SourceQueryBuilder debe estar eliminado del codigo productivo: " + file,
                    normalized.endsWith("execution/SourceQueryBuilder.java"));

            String source = Files.readString(file);
            assertFalse("No debe quedar referencia productiva a SourceQueryBuilder en " + file,
                    source.contains("SourceQueryBuilder"));
            assertFalse("No debe quedar referencia productiva a IncrementalStateStore en " + file,
                    source.contains("IncrementalStateStore"));
            assertFalse("No se debe crear una tercera via de persistencia incremental en " + file,
                    source.contains("IncrementalStateRepository"));
        }
    }

    public void testApacheHttpClientDependencyIsNotPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertFalse("pom.xml no debe conservar httpclient5 si NiFiClient usa java.net.http.HttpClient.",
                pom.contains("httpclient5"));
        assertFalse("pom.xml no debe declarar Apache HttpClient en el MVP.",
                pom.contains("org.apache.httpcomponents"));

        List<Path> productionFiles = Files.walk(Path.of("src/main/java"))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .toList();

        for (Path file : productionFiles) {
            String source = Files.readString(file);
            assertFalse("No debe haber imports productivos de Apache HttpClient en " + file,
                    source.contains("org.apache.hc"));
        }
    }

    public void testBrownfieldClosureDocumentListsAllJavaNifiTokens() throws Exception {
        String closure = Files.readString(Path.of("_bmad-output/implementation-artifacts/mvp-brownfield-closure.md"));
        Set<String> javaTokens = FlowVariableBuilder.generatedTokens();

        for (String token : javaTokens) {
            assertTrue("El cierre documental debe listar el token Java-NiFi " + token,
                    closure.contains(token));
        }
        assertTrue(closure.contains("PostgreSQL"));
        assertTrue(closure.contains("CDC"));
        assertTrue(closure.contains("exactly-once"));
        assertTrue(closure.contains("multi-motor garantizado"));
    }
}

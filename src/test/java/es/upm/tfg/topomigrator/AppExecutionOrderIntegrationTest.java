package es.upm.tfg.topomigrator;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guardrails de integracion para el contrato App -> DependencyResolver -> ExecutionEngine.
 */
public class AppExecutionOrderIntegrationTest extends TestCase {

    public void testAppPassesResolvedExecutionOrderToExecutionEngine() throws Exception {
        String appSource = Files.readString(Path.of("src/main/java/es/upm/tfg/topomigrator/App.java"));

        int resolverCall = appSource.indexOf("resolver.resolveExecutionOrder(contract.getTables().keySet(), dependencies)");
        int engineCall = appSource.indexOf("engine.executeMigration(executionOrder, contract, dependencies)");

        assertTrue("App debe resolver el orden topologico antes de ejecutar el motor.", resolverCall >= 0);
        assertTrue("App debe pasar exactamente executionOrder al motor de ejecucion.", engineCall >= 0);
        assertTrue("La ejecucion debe ocurrir despues de resolver el orden topologico.", resolverCall < engineCall);
    }

    public void testExecutionEngineConsumesExecutionOrderWithoutRecalculatingDependencies() throws Exception {
        String engineSource = Files.readString(Path.of("src/main/java/es/upm/tfg/topomigrator/execution/ExecutionEngine.java"));

        assertFalse("ExecutionEngine no debe importar ni instanciar DependencyResolver.",
                engineSource.contains("DependencyResolver"));
        assertTrue("ExecutionEngine debe iterar secuencialmente sobre executionOrder.",
                engineSource.contains("for (TableNode tableNode : executionOrder)"));
        assertTrue("El resumen debe conservar el orden recibido.",
                engineSource.contains("summary.executionOrder = executionOrder.stream().map(TableNode::getName).collect(Collectors.toList())"));
    }
}

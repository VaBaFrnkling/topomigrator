package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Motor de ejecución que coordina la instanciación y ejecución
 * de los flujos de Apache NiFi para la migración de datos.
 */
public class ExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngine.class);
    
    private final NiFiClient nifiClient;

    public ExecutionEngine() {
        this.nifiClient = new NiFiClient();
    }

    /**
     * Ejecuta el proceso de migración para las tablas en el orden especificado.
     * 
     * @param executionOrder Lista con el orden de las tablas a migrar (DAG resuelto)
     * @param contract El contrato de configuración con detalles origen/destino
     */
    public void executeMigration(List<TableNode> executionOrder, MigrationContract contract) {
        logger.info("Iniciando Motor de Ejecución de Apache NiFi...");
        
        try {
            // Autenticarnos contra la API de NiFi
            nifiClient.authenticate();
            logger.info("Autenticación con Apache NiFi completada con éxito.");

            // Obtener PID del Root y verificar el template
            String rootId = nifiClient.getRootProcessGroupId();
            logger.info("ID del Process Group raíz obtenido: {}", rootId);

            java.nio.file.Path flowPath = java.nio.file.Paths.get("flows", "MainMigration.json");
            if (!java.nio.file.Files.exists(flowPath)) {
                logger.warn("No se encontró el fichero de flujo en {}, asegúrate de haberlo exportado.", flowPath);
            }

            int yOffset = 0;
            // Iterar sobre las tablas resueltas y ejecutar/instanciar su flujo
            for (TableNode table : executionOrder) {
                logger.info(">> Solicitando despliegue en NiFi para la tabla: {}", table.getName());
                
                String groupName = "Migracion_" + table.getName();
                if (java.nio.file.Files.exists(flowPath)) {
                    nifiClient.uploadFlowDefinition(rootId, groupName, yOffset, flowPath);
                    yOffset += 300; // Desplazamiento en el canvas para que no se superpongan visualmente
                } else {
                    logger.error("Se simula la creación pero no se ejecutó subida porque falta MainMigration.json");
                }
            }

            logger.info("Despliegue de flujos completado en NiFi.");
        } catch (Exception e) {
            logger.error("Error crítico durante la ejecución de los flujos en NiFi: {}", e.getMessage(), e);
            throw new RuntimeException("Fallo en el motor de ejecución.", e);
        }
    }
}

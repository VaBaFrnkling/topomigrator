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

            // Iterar sobre las tablas resueltas y ejecutar/instanciar su flujo
            for (TableNode table : executionOrder) {
                logger.info(">> Solicitando a NiFi flujo para la tabla: {}", table.getName());
                
                // TODO: En el futuro instanciar templates de NiFi o configurar Parameter Contexts
                
                // Por ahora simulamos la llamada a una API genérica para notificar inicio
                nifiClient.createProcessGroupParaTabla(table.getName());
            }

            logger.info("Despliegue de flujos completado en NiFi.");
        } catch (Exception e) {
            logger.error("Error crítico durante la ejecución de los flujos en NiFi: {}", e.getMessage(), e);
            throw new RuntimeException("Fallo en el motor de ejecución.", e);
        }
    }
}

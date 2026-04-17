package es.upm.tfg.topomigrator;

import es.upm.tfg.topomigrator.config.ContractLoader;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.orchestration.dependency.DependencyResolver;
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.MetadataDependencyExtractor;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import es.upm.tfg.topomigrator.execution.ExecutionEngine;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("Iniciando orquestador TopoMigrator...");
        
        try {
            // 1. Cargar el contrato
            String configPathEnv = System.getenv("MIGRATION_CONFIG_PATH");
            if (configPathEnv == null || configPathEnv.isEmpty()) {
                configPathEnv = "configs/migration-demo.yaml"; // Fallback por defecto
            }
            Path configPath = Paths.get(configPathEnv);
            
            ContractLoader loader = new ContractLoader();
            MigrationContract contract = loader.load(configPath);

            // 2. Conectar a la BBDD de origen para extraer metadatos
            List<ForeignKeyDependency> dependencies;
            try (Connection sourceConnection = DatabaseConnectionManager.getConnection(contract.getDatabase().getSourceConnection())) {
                MetadataDependencyExtractor extractor = new MetadataDependencyExtractor();
                // Usamos schema null por defecto a menos que lo especifiques
                dependencies = extractor.extractDependencies(sourceConnection, null, contract.getTables().keySet());
            }

            // 3. Resolver el Grafo de Dependencias (DAG + Algoritmo de Kahn)
            DependencyResolver resolver = new DependencyResolver();
            List<TableNode> executionOrder = resolver.resolveExecutionOrder(contract.getTables().keySet(), dependencies);

            // 4. Motor de Ejecución: Despliegue hacia Apache NiFi
            ExecutionEngine engine = new ExecutionEngine();
            engine.executeMigration(executionOrder, contract);
            
            logger.info("Migración orquestada y desplegada correctamente.");

        } catch (Exception e) {
            logger.error("Error crítico durante la orquestación de la migración: ", e);
            System.exit(1);
        }
    }
}

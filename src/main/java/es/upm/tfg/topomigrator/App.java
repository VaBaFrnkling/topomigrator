package es.upm.tfg.topomigrator;

import es.upm.tfg.topomigrator.config.ContractLoader;
import es.upm.tfg.topomigrator.execution.ExecutionEngine;
import es.upm.tfg.topomigrator.execution.LiquibaseSchemaExecutor;
import es.upm.tfg.topomigrator.model.DatabaseConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.orchestration.dependency.DependencyResolver;
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.MetadataDependencyExtractor;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import es.upm.tfg.topomigrator.util.MigrationContractUtils;
import es.upm.tfg.topomigrator.util.OutputCleaner;
import es.upm.tfg.topomigrator.util.OutputDirectoryInitializer;
import es.upm.tfg.topomigrator.util.TableIdentityUtils;
import es.upm.tfg.topomigrator.validations.SchemaCompatibilityValidator;
import es.upm.tfg.topomigrator.validations.TargetChangelogValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class App {
    private static DatabaseConfig requireDatabaseConfig(MigrationContract contract) {
        if (contract == null || contract.getDatabase() == null) {
            throw new IllegalStateException("El contrato no contiene configuracion database para validar conexiones JDBC.");
        }
        return contract.getDatabase();
    }

    public static void main(String[] args) {
        OutputCleaner.cleanOutputs();
        OutputDirectoryInitializer.ensureOutputDirectories();
        Logger logger = LoggerFactory.getLogger(App.class);
        logger.info("Iniciando orquestador TopoMigrator...");

        try {
            String configPathEnv = System.getenv("MIGRATION_CONFIG_PATH");
            if (configPathEnv == null || configPathEnv.isEmpty()) {
                configPathEnv = "configs/contract.yaml";
            }
            Path configPath = Paths.get(configPathEnv);

            ContractLoader loader = new ContractLoader();
            MigrationContract loadedContract = loader.load(configPath);

            MigrationContract contract = MigrationContractUtils.retainEnabledTables(loadedContract);
            if (contract.getTables() == null || contract.getTables().isEmpty()) {
                throw new IllegalStateException("No hay tablas activas en el contrato de migración.");
            }
            logger.info("Tablas activas a procesar: {}", contract.getTables().keySet());

            DatabaseConfig database = requireDatabaseConfig(contract);
            DatabaseConnectionManager.testConnection(database.getSourceConnection(), "Base de Datos Origen");
            DatabaseConnectionManager.testConnection(database.getTargetConnection(), "Base de Datos Destino");

            SchemaCompatibilityValidator.validateSourceSchemas(contract);

            TargetChangelogValidator.validate(contract);

            LiquibaseSchemaExecutor.applyTargetSchemas(contract);

            SchemaCompatibilityValidator.validateTargetAndMapping(contract);

            Map<String, String> physicalToContractKey = new LinkedHashMap<>();
            for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                String physicalId = TableIdentityUtils.toSourcePhysicalId(entry.getValue());
                physicalToContractKey.put(physicalId, entry.getKey());
            }

            List<ForeignKeyDependency> rawDependencies;
            try (Connection sourceConnection = DatabaseConnectionManager.getConnection(contract.getDatabase().getSourceConnection())) {
                MetadataDependencyExtractor extractor = new MetadataDependencyExtractor();
                rawDependencies = extractor.extractDependencies(sourceConnection, physicalToContractKey.keySet());
            }

            List<ForeignKeyDependency> dependencies = new ArrayList<>();
            for (ForeignKeyDependency dep : rawDependencies) {
                String logicalParent = physicalToContractKey.get(dep.getParentTable());
                String logicalDependent = physicalToContractKey.get(dep.getDependentTable());
                if (logicalParent != null && logicalDependent != null) {
                    dependencies.add(new ForeignKeyDependency(logicalParent, logicalDependent));
                }
            }

            DependencyResolver resolver = new DependencyResolver();
            List<TableNode> executionOrder = resolver.resolveExecutionOrder(contract.getTables().keySet(), dependencies);

            ExecutionEngine engine = new ExecutionEngine();
            engine.executeMigration(executionOrder, contract, dependencies);

            logger.info("Migración orquestada y desplegada correctamente.");

        } catch (Exception e) {
            logger.error("Error crítico durante la orquestación de la migración: ", e);
            System.exit(1);
        }
    }
}

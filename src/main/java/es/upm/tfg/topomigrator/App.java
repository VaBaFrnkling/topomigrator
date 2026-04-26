package es.upm.tfg.topomigrator;

import es.upm.tfg.topomigrator.config.ContractLoader;
import es.upm.tfg.topomigrator.execution.ExecutionEngine;
import es.upm.tfg.topomigrator.execution.LiquibaseSchemaExecutor;
import es.upm.tfg.topomigrator.execution.MigrationExecutionResult;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.orchestration.dependency.DependencyResolver;
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.MetadataDependencyExtractor;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import es.upm.tfg.topomigrator.util.DatabaseConnectionManager;
import es.upm.tfg.topomigrator.util.MigrationContractUtils;
import es.upm.tfg.topomigrator.util.OutputCleaner;
import es.upm.tfg.topomigrator.util.TableIdentityUtils;
import es.upm.tfg.topomigrator.validations.SchemaCompatibilityValidator;
import es.upm.tfg.topomigrator.validations.TargetChangelogValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("Iniciando orquestador TopoMigrator...");
        OutputCleaner.cleanOutputs();

        try {
            String configPathEnv = System.getenv("MIGRATION_CONFIG_PATH");
            if (configPathEnv == null || configPathEnv.isEmpty()) {
                configPathEnv = "configs/contract.yaml";
            }
            Path configPath = Paths.get(configPathEnv);

            ContractLoader loader = new ContractLoader();
            MigrationContract loadedContract = loader.load(configPath);
            MigrationContract contract = MigrationContractUtils.retainEnabledTables(loadedContract);
            logger.info("Tablas activas a procesar: {}", contract.getTables().keySet());

            DatabaseConnectionManager.testConnection(contract.getDatabase().getSourceConnection(), "Base de Datos Origen");
            DatabaseConnectionManager.testConnection(contract.getDatabase().getTargetConnection(), "Base de Datos Destino");

            SchemaCompatibilityValidator.validateSourceSchemas(contract);
            TargetChangelogValidator.validate(contract);
            LiquibaseSchemaExecutor.applyTargetSchemas(contract);
            SchemaCompatibilityValidator.validateTargetAndMapping(contract);

            Set<String> includedSourceTables = contract.getTables().values().stream()
                    .map(TableIdentityUtils::toSourcePhysicalId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            logger.info("Tablas físicas incluidas en el DAG: {}", includedSourceTables);

            List<ForeignKeyDependency> dependencies;
            try (Connection sourceConnection = DatabaseConnectionManager.getConnection(contract.getDatabase().getSourceConnection())) {
                MetadataDependencyExtractor extractor = new MetadataDependencyExtractor();
                dependencies = extractor.extractDependencies(sourceConnection, includedSourceTables);
            }

            DependencyResolver resolver = new DependencyResolver();
            List<TableNode> executionOrder = resolver.resolveExecutionOrder(includedSourceTables, dependencies);

            ExecutionEngine engine = new ExecutionEngine();
            MigrationExecutionResult executionResult = engine.executeMigration(executionOrder, contract, dependencies);

            if (executionResult.isSuccessful()) {
                logger.info("Migración orquestada y desplegada correctamente. ExecutionId={}", executionResult.getExecutionId());
            } else {
                logger.error(
                        "La migración terminó con incidencias. ExecutionId={}, fallidas={}, bloqueadas={}",
                        executionResult.getExecutionId(),
                        executionResult.getFailedTables(),
                        executionResult.getBlockedTables()
                );
                System.exit(1);
            }

        } catch (Exception e) {
            logger.error("Error crítico durante la orquestación de la migración: ", e);
            System.exit(1);
        }
    }
}

package es.upm.tfg.topomigrator;

import es.upm.tfg.topomigrator.audit.ErrorArtifactWriter;
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
        ErrorArtifactWriter errorArtifactWriter = new ErrorArtifactWriter();
        Map<String, Object> errorContext = new LinkedHashMap<>();
        logger.info("Iniciando orquestador TopoMigrator...");

        try {
            String configPathEnv = System.getenv("MIGRATION_CONFIG_PATH");
            if (configPathEnv == null || configPathEnv.isEmpty()) {
                configPathEnv = "configs/contract.yaml";
            }
            Path configPath = Paths.get(configPathEnv);
            errorContext.put("configPath", configPath.toString());

            ContractLoader loader = new ContractLoader();
            MigrationContract loadedContract = runPhase("CONTRACT_LOAD", errorArtifactWriter, errorContext,
                    () -> loader.load(configPath));

            MigrationContract contract = runPhase("CONTRACT_FILTERING", errorArtifactWriter, errorContext, () -> {
                MigrationContract activeContract = MigrationContractUtils.retainEnabledTables(loadedContract);
                if (activeContract.getTables() == null || activeContract.getTables().isEmpty()) {
                    throw new IllegalStateException("No hay tablas activas en el contrato de migracion.");
                }
                return activeContract;
            });
            errorContext.put("activeTables", new ArrayList<>(contract.getTables().keySet()));
            logger.info("Tablas activas a procesar: {}", contract.getTables().keySet());

            DatabaseConfig database = runPhase("DATABASE_CONFIG_VALIDATION", errorArtifactWriter, errorContext,
                    () -> requireDatabaseConfig(contract));
            runPhase("DATASOURCE_CONNECTION_TEST", errorArtifactWriter, errorContext, () -> {
                DatabaseConnectionManager.testConnection(database.getSourceConnection(), "Base de Datos Origen");
                DatabaseConnectionManager.testConnection(database.getTargetConnection(), "Base de Datos Destino");
            });

            runPhase("SOURCE_SCHEMA_VALIDATION", errorArtifactWriter, errorContext,
                    () -> SchemaCompatibilityValidator.validateSourceSchemas(contract));

            runPhase("CHANGELOG_VALIDATION", errorArtifactWriter, errorContext,
                    () -> TargetChangelogValidator.validate(contract));

            runPhase("LIQUIBASE_EXECUTION", errorArtifactWriter, errorContext,
                    () -> LiquibaseSchemaExecutor.applyTargetSchemas(contract));

            runPhase("SCHEMA_COMPATIBILITY_VALIDATION", errorArtifactWriter, errorContext,
                    () -> SchemaCompatibilityValidator.validateTargetAndMapping(contract));

            Map<String, String> physicalToContractKey = runPhase("DEPENDENCY_INDEXING", errorArtifactWriter, errorContext, () -> {
                Map<String, String> index = new LinkedHashMap<>();
                for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                    String physicalId = TableIdentityUtils.toSourcePhysicalId(entry.getValue());
                    index.put(physicalId, entry.getKey());
                }
                return index;
            });

            List<ForeignKeyDependency> rawDependencies = runPhase("DEPENDENCY_EXTRACTION", errorArtifactWriter, errorContext, () -> {
                try (Connection sourceConnection = DatabaseConnectionManager.getConnection(contract.getDatabase().getSourceConnection())) {
                    MetadataDependencyExtractor extractor = new MetadataDependencyExtractor();
                    return extractor.extractDependencies(sourceConnection, physicalToContractKey.keySet());
                }
            });

            List<ForeignKeyDependency> dependencies = runPhase("DEPENDENCY_MAPPING", errorArtifactWriter, errorContext, () -> {
                List<ForeignKeyDependency> mappedDependencies = new ArrayList<>();
                for (ForeignKeyDependency dep : rawDependencies) {
                    String logicalParent = physicalToContractKey.get(dep.getParentTable());
                    String logicalDependent = physicalToContractKey.get(dep.getDependentTable());
                    if (logicalParent != null && logicalDependent != null) {
                        mappedDependencies.add(new ForeignKeyDependency(logicalParent, logicalDependent));
                    }
                }
                return mappedDependencies;
            });

            DependencyResolver resolver = new DependencyResolver();
            List<TableNode> executionOrder = runPhase("DEPENDENCY_RESOLUTION", errorArtifactWriter, errorContext,
                    () -> resolver.resolveExecutionOrder(contract.getTables().keySet(), dependencies));

            ExecutionEngine engine = runPhase("NIFI_CLIENT_INITIALIZATION", errorArtifactWriter, errorContext,
                    () -> new ExecutionEngine(errorArtifactWriter));
            engine.executeMigration(executionOrder, contract, dependencies);

            logger.info("Migracion orquestada y desplegada correctamente.");

        } catch (Exception e) {
            logger.error("Error critico durante la orquestacion de la migracion: ", e);
            System.exit(1);
        }
    }

    private static <T> T runPhase(String phase,
                                  ErrorArtifactWriter errorArtifactWriter,
                                  Map<String, Object> errorContext,
                                  PhaseSupplier<T> action) throws Exception {
        try {
            return action.run();
        } catch (Exception e) {
            errorArtifactWriter.writeOrchestrationError(phase, e, new LinkedHashMap<>(errorContext));
            throw e;
        }
    }

    private static void runPhase(String phase,
                                 ErrorArtifactWriter errorArtifactWriter,
                                 Map<String, Object> errorContext,
                                 PhaseAction action) throws Exception {
        runPhase(phase, errorArtifactWriter, errorContext, () -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface PhaseSupplier<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface PhaseAction {
        void run() throws Exception;
    }
}

package es.upm.tfg.topomigrator;

import es.upm.tfg.topomigrator.audit.ErrorArtifactWriter;
import es.upm.tfg.topomigrator.config.ContractLoader;
import es.upm.tfg.topomigrator.execution.ExecutionEngine;
import es.upm.tfg.topomigrator.execution.LiquibaseSchemaExecutor;
import es.upm.tfg.topomigrator.model.ConnectionConfig;
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

            DependencyPlan dependencyPlan = runPhase("DEPENDENCY_RESOLUTION", errorArtifactWriter, errorContext,
                    () -> resolveDependencyPlan(contract));

            MigrationContract executableContract = filterContractForExecutableTables(contract, dependencyPlan.getExecutionOrder());
            if (executableContract.getTables() == null || executableContract.getTables().isEmpty()) {
                logger.warn("No hay tablas ejecutables tras resolver dependencias. Se generaran trazas para tablas no ejecutables.");
            } else {
                runPhase("CHANGELOG_VALIDATION", errorArtifactWriter, errorContext,
                        () -> TargetChangelogValidator.validate(executableContract));

                runPhase("LIQUIBASE_EXECUTION", errorArtifactWriter, errorContext,
                        () -> LiquibaseSchemaExecutor.applyTargetSchemas(executableContract, dependencyPlan.getExecutionOrder()));

                runPhase("SCHEMA_COMPATIBILITY_VALIDATION", errorArtifactWriter, errorContext,
                        () -> SchemaCompatibilityValidator.validateTargetAndMapping(executableContract));
            }

            ExecutionEngine engine = runPhase("NIFI_CLIENT_INITIALIZATION", errorArtifactWriter, errorContext,
                    () -> new ExecutionEngine(errorArtifactWriter));
            engine.executeMigration(
                    dependencyPlan.getExecutionOrder(),
                    contract,
                    dependencyPlan.getDependencies(),
                    dependencyPlan.getBlockedTablesByCycle(),
                    dependencyPlan.getCyclicTables()
            );

            logger.info("Migracion orquestada y desplegada correctamente.");

        } catch (Exception e) {
            logger.error("Error critico durante la orquestacion de la migracion: ", e);
            System.exit(1);
        }
    }

    static DependencyPlan resolveDependencyPlan(MigrationContract contract) throws Exception {
        return resolveDependencyPlan(contract, sourceConnectionConfig ->
                DatabaseConnectionManager.getConnection(sourceConnectionConfig));
    }

    static DependencyPlan resolveDependencyPlan(MigrationContract contract,
                                                SourceConnectionFactory sourceConnectionFactory) throws Exception {
        Map<String, String> physicalToContractKey = new LinkedHashMap<>();
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String physicalId = TableIdentityUtils.toSourcePhysicalId(entry.getValue());
            physicalToContractKey.put(physicalId, entry.getKey());
        }

        List<ForeignKeyDependency> rawDependencies;
        try (Connection sourceConnection = sourceConnectionFactory.getConnection(contract.getDatabase().getSourceConnection())) {
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
        DependencyResolver.BestEffortPlan bestEffortPlan = resolver.resolveBestEffortPlan(contract.getTables().keySet(), dependencies);
        return new DependencyPlan(
                dependencies,
                bestEffortPlan.getExecutableOrder(),
                bestEffortPlan.getBlockedTables(),
                bestEffortPlan.getCyclicTables()
        );
    }

    static MigrationContract filterContractForExecutableTables(MigrationContract contract, List<TableNode> executionOrder) {
        MigrationContract executableContract = new MigrationContract();
        executableContract.setMigration(contract.getMigration());
        executableContract.setDatabase(contract.getDatabase());

        Map<String, TableMigration> executableTables = new LinkedHashMap<>();
        if (executionOrder != null) {
            for (TableNode tableNode : executionOrder) {
                String tableId = resolveOriginalTableKey(contract, tableNode.getName());
                TableMigration table = contract.getTables().get(tableId);
                if (table != null) {
                    executableTables.put(tableId, table);
                }
            }
        }
        executableContract.setTables(executableTables);
        return executableContract;
    }

    private static String resolveOriginalTableKey(MigrationContract contract, String tableName) {
        for (String key : contract.getTables().keySet()) {
            if (key.equalsIgnoreCase(tableName)) {
                return key;
            }
        }
        return tableName;
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

    @FunctionalInterface
    interface SourceConnectionFactory {
        Connection getConnection(ConnectionConfig config) throws Exception;
    }

    static final class DependencyPlan {
        private final List<ForeignKeyDependency> dependencies;
        private final List<TableNode> executionOrder;
        private final List<TableNode> blockedTablesByCycle;
        private final List<TableNode> cyclicTables;

        DependencyPlan(List<ForeignKeyDependency> dependencies,
                       List<TableNode> executionOrder,
                       List<TableNode> blockedTablesByCycle,
                       List<TableNode> cyclicTables) {
            this.dependencies = dependencies;
            this.executionOrder = executionOrder;
            this.blockedTablesByCycle = blockedTablesByCycle;
            this.cyclicTables = cyclicTables;
        }

        List<ForeignKeyDependency> getDependencies() {
            return dependencies;
        }

        List<TableNode> getExecutionOrder() {
            return executionOrder;
        }

        List<TableNode> getBlockedTablesByCycle() {
            return blockedTablesByCycle;
        }

        List<TableNode> getCyclicTables() {
            return cyclicTables;
        }
    }
}

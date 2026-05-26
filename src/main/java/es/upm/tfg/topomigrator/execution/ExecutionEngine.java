package es.upm.tfg.topomigrator.execution;

import com.google.gson.JsonObject;
import es.upm.tfg.topomigrator.audit.ExecutionIdGenerator;
import es.upm.tfg.topomigrator.audit.SummaryTrace;
import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.audit.Timing;
import es.upm.tfg.topomigrator.audit.TraceabilityManager;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Motor de ejecución que coordina la instanciación de flujos de NiFi,
 * supervisa su estado en vivo, propaga fallos a tablas dependientes y genera la auditoría JSON final.
 */
public class ExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngine.class);
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_BLOCKED = "BLOCKED";
    static final String STATUS_PENDING = "PENDING";

    private static final String CONSISTENCY_MATCH = "MATCH";
    private static final String CONSISTENCY_MISMATCH = "MISMATCH";
    private static final String CONSISTENCY_SOURCE_ONLY = "SOURCE_ONLY";
    private static final String CONSISTENCY_TARGET_DELTA_ONLY = "TARGET_DELTA_ONLY";
    private static final String CONSISTENCY_UNAVAILABLE = "UNAVAILABLE";

    private final NiFiClient nifiClient;
    private final TraceabilityManager traceManager;
    private final TableMetricsService metricsService;
    private final IncrementalStateService incrementalStateService;
    private final FlowVariableBuilder flowVariableBuilder;
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final long nifiInitialWaitMs;
    private final long nifiPollWaitMs;
    private final int nifiMaxMonitorChecks;

    public ExecutionEngine() {
        this(new NiFiClient(), new TraceabilityManager(), new TableMetricsService(), new IncrementalStateService(), 5000L, 3000L, 1200);
    }

    ExecutionEngine(NiFiClient nifiClient,
                    TraceabilityManager traceManager,
                    TableMetricsService metricsService,
                    IncrementalStateService incrementalStateService,
                    long nifiInitialWaitMs,
                    long nifiPollWaitMs,
                    int nifiMaxMonitorChecks) {
        if (nifiInitialWaitMs < 0 || nifiPollWaitMs < 0) {
            throw new IllegalArgumentException("Los tiempos de espera de NiFi no pueden ser negativos.");
        }
        if (nifiMaxMonitorChecks <= 0) {
            throw new IllegalArgumentException("El numero maximo de comprobaciones de NiFi debe ser positivo.");
        }
        this.nifiClient = nifiClient;
        this.traceManager = traceManager;
        this.metricsService = metricsService;
        this.incrementalStateService = incrementalStateService;
        this.flowVariableBuilder = new FlowVariableBuilder(metricsService);
        this.nifiInitialWaitMs = nifiInitialWaitMs;
        this.nifiPollWaitMs = nifiPollWaitMs;
        this.nifiMaxMonitorChecks = nifiMaxMonitorChecks;
    }

    static boolean isCanonicalFinalStatus(String status) {
        return STATUS_SUCCESS.equals(status) || STATUS_FAILED.equals(status) || STATUS_BLOCKED.equals(status);
    }

    static boolean isInternalInitialStatus(String status) {
        return STATUS_PENDING.equals(status);
    }

    static boolean isInformationalConsistencyStatus(String status) {
        return CONSISTENCY_MATCH.equals(status)
                || CONSISTENCY_MISMATCH.equals(status)
                || CONSISTENCY_SOURCE_ONLY.equals(status)
                || CONSISTENCY_TARGET_DELTA_ONLY.equals(status)
                || CONSISTENCY_UNAVAILABLE.equals(status);
    }

    private void ensureCanonicalFinalStatus(TableTrace tableTrace) {
        if (tableTrace == null || isCanonicalFinalStatus(tableTrace.status)) {
            return;
        }
        throw new IllegalStateException("Estado final de tabla no canonico: " + tableTrace.status);
    }

    public void executeMigration(List<TableNode> executionOrder, MigrationContract contract) {
        executeMigration(executionOrder, contract, List.of());
    }

    public void executeMigration(List<TableNode> executionOrder, MigrationContract contract, List<ForeignKeyDependency> dependencies) {
        logger.info("Iniciando Motor de Ejecución de Apache NiFi y recolección de Trazas...");

        SummaryTrace summary = new SummaryTrace();
        ExecutionIdGenerator idGenerator = new ExecutionIdGenerator();
        String executionId = idGenerator.getExecutionId();
        summary.executionId = executionId;

        summary.migration = new SummaryTrace.MigrationInfo();
        summary.migration.name = contract.getMigration().getName();
        summary.migration.description = contract.getMigration().getDescription();
        summary.migration.version = contract.getMigration().getVersion() + "";
        summary.migration.executedBy = contract.getMigration().getAuthor();

        summary.timing = new Timing();
        summary.timing.startTime = LocalDateTime.now().format(formatter);
        long globalStartMs = System.currentTimeMillis();

        summary.executionOrder = executionOrder.stream().map(TableNode::getName).collect(Collectors.toList());
        summary.tables = new SummaryTrace.TablesSummary();
        summary.tables.total = executionOrder.size();

        summary.tableExecutionDetails = new ArrayList<>();
        summary.failedTables = new ArrayList<>();
        summary.blockedTables = new ArrayList<>();

        Map<String, String> tableIdentityIndex = buildTableIdentityIndex(contract);
        Map<String, List<String>> parentsByTable = buildParentsByTable(dependencies, tableIdentityIndex);
        summary.dependencyAnalysis = buildDependencyAnalysis(executionOrder, parentsByTable, tableIdentityIndex);
        Set<String> failedOrBlockedTables = new HashSet<>();
        RuntimeException criticalFailure = null;

        try {
            Path flowPath = Paths.get("flows", "MainMigration.json");
            if (!Files.exists(flowPath)) {
                logger.error("ERROR FATAL: Plantilla base {} no encontrada. Abortando motor NiFi.", flowPath.toAbsolutePath());
                throw new IllegalStateException("Plantilla MainMigration.json no existe en el disco.");
            }

            nifiClient.authenticate();
            String rootId = nifiClient.getRootProcessGroupId();

            int yOffset = 0;
            int orderCounter = 1;

            for (TableNode tableNode : executionOrder) {
                String tableName = tableNode.getName();
                TableMigration tableConfig = resolveTableConfig(contract, tableName);
                if (tableConfig != null) {
                    tableName = resolveOriginalTableKey(contract, tableName);
                }

                logger.info(">> [{}/{}] Preparando migración para la tabla: {}", orderCounter, executionOrder.size(), tableName);

                TableTrace tableTrace = createBaseTableTrace(executionId, idGenerator, orderCounter, tableName, tableConfig);
                long tStartMs = System.currentTimeMillis();

                try {
                    if (tableConfig == null) {
                        throw new IllegalStateException("No se encontró configuración de migración para la tabla " + tableName);
                    }

                    List<String> blockingParents = getBlockingParents(tableName, parentsByTable, failedOrBlockedTables, tableIdentityIndex);
                    if (!blockingParents.isEmpty()) {
                        markTableAsBlocked(summary, tableTrace, tableName, blockingParents);
                        failedOrBlockedTables.add(canonicalTableKey(tableName, tableIdentityIndex));
                    } else {
                        executeSingleTable(rootId, flowPath, yOffset, executionId, tableName, contract, tableConfig, tableTrace);
                        summary.tables.successful++;
                    }
                } catch (Exception e) {
                    logger.error("Error migrando tabla {} ({}): {}", tableName, e.getClass().getSimpleName(), sanitizeDiagnostic(e.getMessage()));
                    markTableAsFailed(summary, tableTrace, tableName, e);
                    failedOrBlockedTables.add(canonicalTableKey(tableName, tableIdentityIndex));
                } finally {
                    tableTrace.timing.endTime = LocalDateTime.now().format(formatter);
                    tableTrace.timing.durationMs = System.currentTimeMillis() - tStartMs;
                    try {
                        ensureCanonicalFinalStatus(tableTrace);
                    } catch (IllegalStateException invariantEx) {
                        logger.error("Invariante de estado roto para tabla {}: {}. Se fuerza FAILED.", tableName, invariantEx.getMessage());
                        tableTrace.status = STATUS_FAILED;
                        if (tableTrace.errors == null) {
                            tableTrace.errors = new ArrayList<>();
                        }
                        tableTrace.errors.add("Estado final no canonico corregido automaticamente a FAILED.");
                    }
                    summary.tableExecutionDetails.add(toSummary(tableName, tableTrace, orderCounter));
                    traceManager.writeTableTrace(tableTrace);

                    yOffset += 300;
                    orderCounter++;
                }
            }

        } catch (Exception e) {
            logger.error("Fallo crítico general en el motor de ejecución y tracking: {}", sanitizeDiagnostic(e.getMessage()), e);
            criticalFailure = new IllegalStateException("Fallo crítico general en el motor de ejecución.", e);
        } finally {
            summary.timing.endTime = LocalDateTime.now().format(formatter);
            summary.timing.durationMs = System.currentTimeMillis() - globalStartMs;
            reconcileSummaryTableCounts(summary);
            traceManager.writeSummary(summary);
            logger.info("Migración y Auditoría JSON completada.");

            if (criticalFailure != null) {
                throw criticalFailure;
            }
            if (summary.tables.failed > 0 || summary.tables.blocked > 0) {
                throw new IllegalStateException("La migración terminó con " + summary.tables.failed
                        + " tabla(s) fallida(s) y " + summary.tables.blocked + " tabla(s) bloqueada(s). Consultar outputs/traces/summary.json y logs.");
            }
        }
    }

    private void executeSingleTable(String rootId,
                                    Path flowPath,
                                    int yOffset,
                                    String executionId,
                                    String tableName,
                                    MigrationContract contract,
                                    TableMigration tableConfig,
                                    TableTrace tableTrace) throws Exception {

        String effectiveIncrementalStartValue = resolveEffectiveIncrementalStartValue(tableName, tableConfig, tableTrace);

        Long sourceSelectedRows = safelyCountSourceRows(contract, tableConfig, tableTrace, effectiveIncrementalStartValue);
        Long targetRowsBefore = safelyCountTargetRows(contract, tableConfig, tableTrace);

        tableTrace.auditMetrics.sourceSelectedRecords = sourceSelectedRows;
        tableTrace.auditMetrics.targetRowsBefore = targetRowsBefore;

        nifiClient.authenticate();

        Map<String, String> flowConfigVariables = buildFlowVariables(executionId, contract, tableConfig, tableTrace, effectiveIncrementalStartValue);
        String groupName = "Migracion_" + tableName;
        String pgId = null;

        try {
            pgId = nifiClient.uploadFlowDefinition(rootId, groupName, yOffset, flowPath, flowConfigVariables);
            nifiClient.enableControllerServicesRecursively(pgId);
            nifiClient.changeProcessGroupState(pgId, "RUNNING");

            monitorFlowUntilCompletion(pgId, tableName);

            nifiClient.changeProcessGroupState(pgId, "STOPPED");

            Long targetRowsAfter = safelyCountTargetRows(contract, tableConfig, tableTrace);
            tableTrace.auditMetrics.targetRowsAfter = targetRowsAfter;
            tableTrace.auditMetrics.targetNetDelta = computeNetDelta(targetRowsBefore, targetRowsAfter);
            tableTrace.recordsProcessed = resolveAuditedProcessedRecords(sourceSelectedRows, tableTrace.auditMetrics.targetNetDelta, tableTrace);

            updateIncrementalStateIfNeeded(contract, tableName, tableConfig, effectiveIncrementalStartValue, tableTrace);
            tableTrace.status = STATUS_SUCCESS;
            logger.info("Tabla {} migrada correctamente. Registros auditados: {}", tableName, tableTrace.recordsProcessed);
        } finally {
            if (pgId != null) {
                try {
                    nifiClient.cleanupProcessGroup(pgId);
                } catch (Exception cleanupEx) {
                    logger.warn("No se pudo limpiar el Process Group {} tras la migración de {}: {}",
                            pgId, tableName, cleanupEx.getMessage());
                }
            }
        }
    }

    /**
     * Monitoriza el Process Group leyendo su status de NiFi.
     * No considera éxito únicamente activeThreads=0 y queued=0: antes de devolver SUCCESS
     * revisa si algún procesador de fallo del flujo recibió FlowFiles.
     */
    private void monitorFlowUntilCompletion(String pgId, String tableName) throws Exception {
        logger.info("Esperando inicialización de NiFi para {}...", tableName);
        Thread.sleep(nifiInitialWaitMs);

        int checks = 0;
        int maxChecks = nifiMaxMonitorChecks;

        while (checks < maxChecks) {
            JsonObject statusData;
            try {
                statusData = nifiClient.getProcessGroupStatus(pgId);
            } catch (Exception e) {
                logger.warn("Fallo temporal consultando NiFi en paso {} para {}. Reintentando...", checks, tableName);
                Thread.sleep(nifiPollWaitMs);
                checks++;
                continue;
            }

            JsonObject snap = statusData.getAsJsonObject("processGroupStatus").getAsJsonObject("aggregateSnapshot");

            int activeThreads = snap.get("activeThreadCount").getAsInt();
            String queuedRaw = snap.get("queuedCount").getAsString();
            int queuedCount = parseQueuedCount(queuedRaw);
            String bytesRead = snap.get("bytesRead").getAsString();

            List<String> failureDiagnostics = nifiClient.collectFailureDiagnostics(pgId);
            if (!failureDiagnostics.isEmpty()) {
                String message = "NiFi detectó fallos internos en la tabla " + tableName + ": " + String.join(" | ", failureDiagnostics);
                logger.error(message);
                throw new NiFiFlowFailureException(message);
            }

            if (checks % 10 == 0) {
                logger.info("⏳ NiFi Monitor [{}]: ActiveThreads={}, Queued={}, Read={}", tableName, activeThreads, queuedCount, bytesRead);
            }

            if (activeThreads == 0 && queuedCount == 0 && checks > 2) {
                logger.info("NiFi terminó sin colas pendientes ni procesadores de fallo activados para {}.", tableName);
                return;
            }

            Thread.sleep(nifiPollWaitMs);
            checks++;
        }

        String message = "Timeout de NiFi alcanzado para " + tableName + " tras 1 hora. La tabla se marca como FAILED.";
        logger.error(message);
        throw new NiFiFlowFailureException(message);
    }

    private Map<String, String> buildFlowVariables(String executionId,
                                                   MigrationContract contract,
                                                   TableMigration tableConfig,
                                                   TableTrace tableTrace,
                                                   String effectiveIncrementalStartValue) {
        Map<String, String> flowConfigVariables = flowVariableBuilder.build(
                executionId,
                contract,
                tableConfig,
                tableTrace,
                effectiveIncrementalStartValue
        );
        logger.info("Consulta SQL enviada a NiFi para {}.{}: {}",
                tableConfig.getSource().getSchema(), tableConfig.getSource().getTable(), flowConfigVariables.get("##QUERY_SQL##"));

        return flowConfigVariables;
    }


    private String resolveEffectiveIncrementalStartValue(String tableName, TableMigration tableConfig, TableTrace tableTrace) {
        if (!"incremental".equalsIgnoreCase(tableConfig.getMigrationType()) || tableConfig.getIncrementalConfig() == null) {
            return null;
        }

        String configuredStartValue = tableConfig.getIncrementalConfig().getStartValue();
        String stateKey = incrementalStateService.buildStateKey(tableConfig);
        IncrementalStateService.IncrementalState persistedState = incrementalStateService.getState(stateKey);
        String stateValue = persistedState != null ? persistedState.lastProcessedValue : null;
        String effectiveStart = stateValue != null ? stateValue : configuredStartValue;

        tableTrace.incrementalInfo = new TableTrace.IncrementalInfo();
        tableTrace.incrementalInfo.column = tableConfig.getIncrementalConfig().getColumn();
        tableTrace.incrementalInfo.previousValue = effectiveStart;
        tableTrace.incrementalInfo.lastProcessedValue = effectiveStart;
        tableTrace.incrementalInfo.stateUpdated = false;

        logger.info("Migración incremental de {} usando {} como valor inicial efectivo para la columna {}.",
                tableName, effectiveStart, tableConfig.getIncrementalConfig().getColumn());
        return effectiveStart;
    }

    private void updateIncrementalStateIfNeeded(MigrationContract contract,
                                                String tableName,
                                                TableMigration tableConfig,
                                                String effectiveIncrementalStartValue,
                                                TableTrace tableTrace) throws Exception {
        if (!"incremental".equalsIgnoreCase(tableConfig.getMigrationType()) || tableConfig.getIncrementalConfig() == null) {
            return;
        }

        String lastValue = metricsService.findLastSelectedIncrementalValue(contract, tableConfig, effectiveIncrementalStartValue);
        if (lastValue != null && !lastValue.isBlank()) {
            String stateKey = incrementalStateService.buildStateKey(tableConfig);
            incrementalStateService.saveSuccessfulBatch(
                    stateKey,
                    tableConfig,
                    effectiveIncrementalStartValue,
                    lastValue,
                    tableTrace.executionId,
                    tableTrace.tableExecutionId,
                    tableTrace.recordsProcessed
            );
            tableTrace.incrementalInfo.lastProcessedValue = lastValue;
            tableTrace.incrementalInfo.stateUpdated = true;
        } else {
            tableTrace.auditMetrics.warnings.add("No se actualizó el estado incremental porque la consulta no seleccionó nuevos registros.");
        }
    }

    private Long safelyCountSourceRows(MigrationContract contract, TableMigration tableConfig, TableTrace tableTrace, String effectiveIncrementalStartValue) {
        try {
            return metricsService.countSourceSelectedRows(contract, tableConfig, effectiveIncrementalStartValue);
        } catch (Exception e) {
            String safeMsg = sanitizeDiagnostic(e.getMessage());
            logger.warn("No se pudo contar la selección de origen para auditoría en {}.{}: {}",
                    tableConfig.getSource().getSchema(),
                    tableConfig.getSource().getTable(),
                    safeMsg);
            tableTrace.auditMetrics.warnings.add("No se pudo calcular sourceSelectedRecords: " + safeMsg);
            return null;
        }
    }

    private Long safelyCountTargetRows(MigrationContract contract, TableMigration tableConfig, TableTrace tableTrace) {
        if (tableConfig == null) {
            return null;
        }
        try {
            return metricsService.countTargetRows(contract, tableConfig);
        } catch (Exception e) {
            String safeMsg = sanitizeDiagnostic(e.getMessage());
            logger.warn("No se pudo contar filas en destino para auditoría en {}.{}: {}",
                    tableConfig.getTarget().getSchema(),
                    tableConfig.getTarget().getTable(),
                    safeMsg);
            tableTrace.auditMetrics.warnings.add("No se pudo calcular targetRows: " + safeMsg);
            return null;
        }
    }

    private Map<String, List<String>> buildParentsByTable(List<ForeignKeyDependency> dependencies, Map<String, String> tableIdentityIndex) {
        Map<String, List<String>> parentsByTable = new LinkedHashMap<>();
        if (dependencies == null) {
            return parentsByTable;
        }
        for (ForeignKeyDependency dependency : dependencies) {
            parentsByTable.computeIfAbsent(canonicalTableKey(dependency.getDependentTable(), tableIdentityIndex), ignored -> new ArrayList<>())
                    .add(canonicalTableKey(dependency.getParentTable(), tableIdentityIndex));
        }
        return parentsByTable;
    }

    private SummaryTrace.DependencyAnalysis buildDependencyAnalysis(List<TableNode> executionOrder,
                                                                    Map<String, List<String>> parentsByTable,
                                                                    Map<String, String> tableIdentityIndex) {
        SummaryTrace.DependencyAnalysis analysis = new SummaryTrace.DependencyAnalysis();
        analysis.independentTables = new ArrayList<>();
        analysis.dependentTables = new LinkedHashMap<>();

        if (executionOrder == null) {
            return analysis;
        }

        for (TableNode tableNode : executionOrder) {
            if (tableNode == null) {
                continue;
            }
            String tableName = tableNode.getName();
            String canonicalKey = canonicalTableKey(tableName, tableIdentityIndex);
            List<String> parents = parentsByTable.getOrDefault(canonicalKey, List.of());
            if (parents.isEmpty()) {
                analysis.independentTables.add(tableName);
            } else {
                analysis.dependentTables.put(tableName, new ArrayList<>(parents));
            }
        }

        return analysis;
    }

    private List<String> getBlockingParents(String tableName,
                                            Map<String, List<String>> parentsByTable,
                                            Set<String> failedOrBlockedTables,
                                            Map<String, String> tableIdentityIndex) {
        List<String> parents = parentsByTable.getOrDefault(canonicalTableKey(tableName, tableIdentityIndex), List.of());
        return parents.stream()
                .filter(failedOrBlockedTables::contains)
                .collect(Collectors.toList());
    }

    private Map<String, String> buildTableIdentityIndex(MigrationContract contract) {
        Map<String, String> identityIndex = new LinkedHashMap<>();
        if (contract == null || contract.getTables() == null) {
            return identityIndex;
        }
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            String canonicalKey = normalize(entry.getKey());
            registerTableAlias(identityIndex, canonicalKey, entry.getKey());
            TableMigration table = entry.getValue();
            if (table != null) {
                if (table.getSource() != null) {
                    registerTableAlias(identityIndex, canonicalKey, table.getSource().getTable());
                    registerTableAlias(identityIndex, canonicalKey, qualifiedName(table.getSource().getSchema(), table.getSource().getTable()));
                }
                if (table.getTarget() != null) {
                    registerTableAlias(identityIndex, canonicalKey, table.getTarget().getTable());
                    registerTableAlias(identityIndex, canonicalKey, qualifiedName(table.getTarget().getSchema(), table.getTarget().getTable()));
                }
            }
        }
        return identityIndex;
    }

    private void registerTableAlias(Map<String, String> identityIndex, String canonicalKey, String alias) {
        String normalizedAlias = normalize(alias);
        if (!normalizedAlias.isEmpty()) {
            String existing = identityIndex.putIfAbsent(normalizedAlias, canonicalKey);
            if (existing != null && !existing.equals(canonicalKey)) {
                logger.warn("Alias de tabla '{}' ya registrado para la clave canonica '{}'; se ignora el registro duplicado desde '{}'.",
                        normalizedAlias, existing, canonicalKey);
            }
        }
    }

    private String canonicalTableKey(String tableName, Map<String, String> tableIdentityIndex) {
        String normalized = normalize(tableName);
        return tableIdentityIndex.getOrDefault(normalized, normalized);
    }

    private String qualifiedName(String schema, String table) {
        if (table == null || table.isBlank()) {
            return "";
        }
        if (schema == null || schema.isBlank()) {
            return table;
        }
        return schema + "." + table;
    }

    private void markTableAsBlocked(SummaryTrace summary, TableTrace tableTrace, String tableName, List<String> blockingParents) {
        logger.warn("Tabla {} bloqueada porque una o más tablas padre no terminaron correctamente: {}", tableName, blockingParents);
        tableTrace.status = STATUS_BLOCKED;
        tableTrace.recordsProcessed = 0L;
        tableTrace.auditMetrics.consistencyStatus = STATUS_BLOCKED;
        tableTrace.auditMetrics.warnings.add("Tabla no ejecutada por fallo previo en dependencias padre: " + blockingParents);
        summary.blockedTables.add(tableName);
        summary.tables.blocked++;
    }

    private void markTableAsFailed(SummaryTrace summary, TableTrace tableTrace, String tableName, Exception e) {
        String sanitizedMessage = sanitizeDiagnostic(e != null ? e.getMessage() : null);
        tableTrace.status = STATUS_FAILED;
        tableTrace.recordsProcessed = 0L;
        if (tableTrace.errors == null) {
            tableTrace.errors = new ArrayList<>();
        }
        tableTrace.errors.add(sanitizedMessage);
        if (tableTrace.auditMetrics != null) {
            tableTrace.auditMetrics.consistencyStatus = STATUS_FAILED;
            tableTrace.auditMetrics.warnings.add("La tabla falló durante la ejecución: " + sanitizedMessage);
        }
        summary.tables.failed++;
        SummaryTrace.FailedTable ft = new SummaryTrace.FailedTable();
        ft.table = tableName;
        ft.reason = sanitizedMessage;
        summary.failedTables.add(ft);
    }

    private String sanitizeDiagnostic(String message) {
        if (message == null || message.isBlank()) {
            return "Fallo de ejecucion sin diagnostico disponible.";
        }
        String sanitized = message;
        sanitized = sanitized.replaceAll("(?i)(password|pwd|token|jwt|secret|api_key|apikey)(\\s*[=:]\\s*)[^\\s,;}&]+", "$1$2[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)(\"(?:password|pwd|token|jwt|secret|api_key|apikey)\"\\s*:\\s*\")[^\"]*(\")", "$1[REDACTED]$2");
        sanitized = sanitized.replaceAll("(?i)(https?://[^\\s/@:]+:)[^\\s/@]+(@)", "$1[REDACTED]$2");
        sanitized = sanitized.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer [REDACTED]");
        sanitized = sanitized.replaceAll("(?i)(Authorization:\\s*Bearer\\s+)[A-Za-z0-9._\\-]+", "$1[REDACTED]");
        // Redacta credenciales en parámetros de query de URLs JDBC o HTTP
        sanitized = sanitized.replaceAll("(?i)([?&](?:password|pwd|token|jwt|secret|api_key|apikey)=)[^&\\s]*", "$1[REDACTED]");
        return sanitized;
    }

    private TableTrace createBaseTableTrace(String executionId,
                                            ExecutionIdGenerator idGenerator,
                                            int orderCounter,
                                            String tableName,
                                            TableMigration tableConfig) {
        TableTrace tableTrace = new TableTrace();
        tableTrace.executionId = executionId;
        tableTrace.tableExecutionId = idGenerator.getTableExecutionId(orderCounter);
        tableTrace.timing = new Timing();
        tableTrace.timing.startTime = LocalDateTime.now().format(formatter);
        tableTrace.auditMetrics = new TableTrace.AuditMetrics();
        tableTrace.auditMetrics.strategy = "SOURCE_QUERY_COUNT_WITH_TARGET_DELTA_VALIDATION";
        tableTrace.auditMetrics.warnings = new ArrayList<>();
        tableTrace.errors = new ArrayList<>();
        tableTrace.status = STATUS_PENDING;

        if (tableConfig != null) {
            tableTrace.migrationType = tableConfig.getMigrationType();
            tableTrace.table = new TableTrace.TableMapping();
            tableTrace.table.source = new TableTrace.SchemaTable();
            tableTrace.table.source.schema = tableConfig.getSource().getSchema();
            tableTrace.table.source.name = tableConfig.getSource().getTable();

            tableTrace.table.target = new TableTrace.SchemaTable();
            tableTrace.table.target.schema = tableConfig.getTarget().getSchema();
            tableTrace.table.target.name = tableConfig.getTarget().getTable();
        } else {
            tableTrace.table = new TableTrace.TableMapping();
            tableTrace.table.source = new TableTrace.SchemaTable();
            tableTrace.table.source.name = tableName;
        }

        return tableTrace;
    }

    private SummaryTrace.TableExecutionSummary toSummary(String tableName, TableTrace tableTrace, int executionOrder) {
        SummaryTrace.TableExecutionSummary s = new SummaryTrace.TableExecutionSummary();
        s.table = tableName;
        s.executionId = tableTrace.tableExecutionId;
        s.order = executionOrder;
        s.status = tableTrace.status;
        s.records = tableTrace.recordsProcessed;
        s.durationMs = tableTrace.timing.durationMs;
        s.sourceSelectedRecords = tableTrace.auditMetrics != null ? tableTrace.auditMetrics.sourceSelectedRecords : null;
        s.targetNetDeltaRecords = tableTrace.auditMetrics != null ? tableTrace.auditMetrics.targetNetDelta : null;
        s.auditConsistencyStatus = tableTrace.auditMetrics != null ? tableTrace.auditMetrics.consistencyStatus : null;
        return s;
    }

    private void reconcileSummaryTableCounts(SummaryTrace summary) {
        if (summary == null) {
            return;
        }
        if (summary.tables == null) {
            summary.tables = new SummaryTrace.TablesSummary();
        }
        if (summary.tableExecutionDetails == null) {
            summary.tableExecutionDetails = new ArrayList<>();
        }

        summary.tables.total = summary.tableExecutionDetails.size();
        summary.tables.successful = 0;
        summary.tables.failed = 0;
        summary.tables.blocked = 0;

        for (SummaryTrace.TableExecutionSummary detail : summary.tableExecutionDetails) {
            if (detail == null || detail.status == null) {
                continue;
            }
            switch (detail.status) {
                case STATUS_SUCCESS:
                    summary.tables.successful++;
                    break;
                case STATUS_FAILED:
                    summary.tables.failed++;
                    break;
                case STATUS_BLOCKED:
                    summary.tables.blocked++;
                    break;
                default:
                    logger.warn("Estado de tabla no canonico en el resumen: {}", detail.status);
                    break;
            }
        }
    }

    private Long computeNetDelta(Long before, Long after) {
        if (before == null || after == null) {
            return null;
        }
        return after - before;
    }

    private long resolveAuditedProcessedRecords(Long sourceSelectedRows, Long targetNetDelta, TableTrace tableTrace) {
        finalizeAuditConsistency(tableTrace, sourceSelectedRows, targetNetDelta);

        if (sourceSelectedRows != null) {
            return sourceSelectedRows;
        }
        if (targetNetDelta != null && targetNetDelta >= 0) {
            return targetNetDelta;
        }
        return 0L;
    }

    private void finalizeAuditConsistency(TableTrace tableTrace, Long sourceSelectedRows, Long targetNetDelta) {
        if (tableTrace.auditMetrics == null) {
            return;
        }

        if (sourceSelectedRows != null && targetNetDelta != null) {
            if (sourceSelectedRows.equals(targetNetDelta)) {
                tableTrace.auditMetrics.consistencyStatus = CONSISTENCY_MATCH;
            } else {
                tableTrace.auditMetrics.consistencyStatus = CONSISTENCY_MISMATCH;
                tableTrace.auditMetrics.warnings.add(
                        "La selección en origen (" + sourceSelectedRows + ") no coincide con el delta neto en destino (" + targetNetDelta + "). " +
                                "Se usa sourceSelectedRecords como métrica principal y targetNetDelta solo como validación auxiliar."
                );
            }
            return;
        }

        if (sourceSelectedRows != null) {
            tableTrace.auditMetrics.consistencyStatus = CONSISTENCY_SOURCE_ONLY;
            return;
        }

        if (targetNetDelta != null) {
            tableTrace.auditMetrics.consistencyStatus = CONSISTENCY_TARGET_DELTA_ONLY;
            return;
        }

        tableTrace.auditMetrics.consistencyStatus = CONSISTENCY_UNAVAILABLE;
    }

    private TableMigration resolveTableConfig(MigrationContract contract, String tableName) {
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(tableName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String resolveOriginalTableKey(MigrationContract contract, String tableName) {
        for (String key : contract.getTables().keySet()) {
            if (key.equalsIgnoreCase(tableName)) {
                return key;
            }
        }
        return tableName;
    }

    private int parseQueuedCount(String queuedRaw) {
        if (queuedRaw == null || queuedRaw.isBlank()) {
            return 0;
        }
        String firstNumber = queuedRaw.trim().split(" ")[0].replaceAll("[^0-9]", "");
        return firstNumber.isBlank() ? 0 : Integer.parseInt(firstNumber);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

}

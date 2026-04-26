package es.upm.tfg.topomigrator.execution;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import es.upm.tfg.topomigrator.audit.ExecutionIdGenerator;
import es.upm.tfg.topomigrator.audit.SummaryTrace;
import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.audit.Timing;
import es.upm.tfg.topomigrator.audit.TraceabilityManager;
import es.upm.tfg.topomigrator.model.FilterConfig;
import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.orchestration.dependency.ForeignKeyDependency;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import es.upm.tfg.topomigrator.util.TableIdentityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Motor de ejecución que coordina la instanciación de flujos de NiFi,
 * supervisa su estado y genera la auditoría final JSON.
 */
public class ExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngine.class);
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final NiFiClient nifiClient;
    private final TraceabilityManager traceManager;
    private final TableMetricsService tableMetricsService;
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final Gson gson = new Gson();

    public ExecutionEngine() {
        this.nifiClient = new NiFiClient();
        this.traceManager = new TraceabilityManager();
        this.tableMetricsService = new TableMetricsService();
    }

    public MigrationExecutionResult executeMigration(List<TableNode> executionOrder,
                                                     MigrationContract contract,
                                                     List<ForeignKeyDependency> dependencies) {
        logger.info("Iniciando Motor de Ejecución de Apache NiFi y recolección de Trazas...");

        SummaryTrace summary = new SummaryTrace();
        ExecutionIdGenerator idGenerator = new ExecutionIdGenerator();
        String executionId = idGenerator.getExecutionId();
        MigrationExecutionResult result = new MigrationExecutionResult(executionId);
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

        Map<String, Set<String>> descendantsByParent = buildDescendantsIndex(executionOrder, dependencies);
        Map<String, String> blockedReasons = new LinkedHashMap<>();

        try {
            Path flowPath = Paths.get("flows", "MainMigration.json");
            if (!Files.exists(flowPath)) {
                throw new IllegalStateException("Plantilla MainMigration.json no existe en el disco: " + flowPath.toAbsolutePath());
            }

            nifiClient.authenticate();
            String rootId = nifiClient.getRootProcessGroupId();

            int yOffset = 0;
            int orderCounter = 1;

            for (TableNode tableNode : executionOrder) {
                String sourcePhysicalId = normalizeName(tableNode.getName());
                TableMigration tableConfig = findTableConfig(contract, sourcePhysicalId);
                if (tableConfig == null) {
                    throw new IllegalStateException("No se encontró configuración para la tabla física del orden topológico: " + sourcePhysicalId);
                }

                TableTrace tableTrace = initializeTableTrace(executionId, idGenerator, orderCounter, tableConfig);
                long tStartMs = System.currentTimeMillis();
                String displayTableName = sourcePhysicalId;

                try {
                    if (blockedReasons.containsKey(sourcePhysicalId)) {
                        String reason = blockedReasons.get(sourcePhysicalId);
                        logger.warn("Tabla {} bloqueada por dependencia fallida. Motivo: {}", displayTableName, reason);
                        tableTrace.status = "BLOCKED";
                        tableTrace.recordsProcessed = 0;
                        tableTrace.errors = Collections.singletonList(reason);
                        summary.blockedTables.add(displayTableName);
                        result.addBlockedTable(displayTableName);
                    } else {
                        logger.info(">> [{}/{}] Invocando despliegue y Start para la tabla: {}",
                                orderCounter, executionOrder.size(), displayTableName);

                        long rowsBefore = tableMetricsService.countRows(
                                contract.getDatabase().getTargetConnection(),
                                tableTrace.table.target.schema,
                                tableTrace.table.target.name
                        );

                        nifiClient.authenticate();
                        Map<String, String> flowConfigVariables = buildFlowConfigVariables(contract, tableConfig, tableTrace);

                        String groupName = "Migracion_" + sanitizeGroupName(displayTableName);
                        String pgId = null;
                        try {
                            pgId = nifiClient.uploadFlowDefinition(rootId, groupName, yOffset, flowPath, flowConfigVariables);
                            nifiClient.changeProcessGroupState(pgId, "RUNNING");
                            monitorFlowUntilCompletion(pgId, displayTableName);
                        } finally {
                            if (pgId != null) {
                                try {
                                    nifiClient.changeProcessGroupState(pgId, "STOPPED");
                                } catch (Exception stopError) {
                                    logger.warn("No se pudo detener el Process Group {} para {}: {}", pgId, displayTableName, stopError.getMessage());
                                }
                            }
                        }

                        long rowsAfter = tableMetricsService.countRows(
                                contract.getDatabase().getTargetConnection(),
                                tableTrace.table.target.schema,
                                tableTrace.table.target.name
                        );

                        tableTrace.status = "SUCCESS";
                        tableTrace.recordsProcessed = Math.max(0, rowsAfter - rowsBefore);
                        summary.tables.successful++;
                        result.addSuccessfulTable(displayTableName);
                    }
                } catch (Exception e) {
                    logger.error("Error migrando tabla {}: {}", displayTableName, e.getMessage(), e);
                    tableTrace.status = "FAILED";
                    tableTrace.recordsProcessed = 0;
                    tableTrace.errors = Collections.singletonList(e.getMessage());
                    summary.tables.failed++;
                    result.addFailedTable(displayTableName);

                    SummaryTrace.FailedTable ft = new SummaryTrace.FailedTable();
                    ft.table = displayTableName;
                    ft.reason = e.getMessage();
                    summary.failedTables.add(ft);

                    blockDescendants(displayTableName, descendantsByParent, blockedReasons);
                }

                tableTrace.timing.endTime = LocalDateTime.now().format(formatter);
                tableTrace.timing.durationMs = System.currentTimeMillis() - tStartMs;
                summary.tableExecutionDetails.add(buildSummaryRow(tableTrace, displayTableName));
                traceManager.writeTableTrace(tableTrace);

                yOffset += 300;
                orderCounter++;
            }
        } catch (Exception e) {
            logger.error("Fallo crítico general en el motor de ejecución y tracking: ", e);
            throw new RuntimeException("Fallo crítico general en el motor de ejecución", e);
        } finally {
            summary.timing.endTime = LocalDateTime.now().format(formatter);
            summary.timing.durationMs = System.currentTimeMillis() - globalStartMs;
            traceManager.writeSummary(summary);
            logger.info("Migración y Auditoría JSON completadas. ExecutionId={}", executionId);
        }

        return result;
    }

    private SummaryTrace.TableExecutionSummary buildSummaryRow(TableTrace tableTrace, String displayTableName) {
        SummaryTrace.TableExecutionSummary row = new SummaryTrace.TableExecutionSummary();
        row.table = displayTableName;
        row.executionId = tableTrace.tableExecutionId;
        row.order = tableTrace.executionOrder;
        row.status = tableTrace.status;
        row.records = tableTrace.recordsProcessed;
        row.durationMs = tableTrace.timing.durationMs;
        return row;
    }

    private TableTrace initializeTableTrace(String executionId,
                                            ExecutionIdGenerator idGenerator,
                                            int orderCounter,
                                            TableMigration tableConfig) {
        TableTrace tableTrace = new TableTrace();
        tableTrace.executionId = executionId;
        tableTrace.tableExecutionId = idGenerator.getTableExecutionId(orderCounter);
        tableTrace.executionOrder = orderCounter;
        tableTrace.timing = new Timing();
        tableTrace.timing.startTime = LocalDateTime.now().format(formatter);
        populateTableTrace(tableTrace, tableConfig);
        return tableTrace;
    }

    private void populateTableTrace(TableTrace tableTrace, TableMigration tableConfig) {
        tableTrace.migrationType = tableConfig.getMigrationType();
        tableTrace.table = new TableTrace.TableMapping();
        tableTrace.table.source = new TableTrace.SchemaTable();
        tableTrace.table.source.schema = defaultSchema(tableConfig.getSource().getSchema());
        tableTrace.table.source.name = tableConfig.getSource().getTable();

        tableTrace.table.target = new TableTrace.SchemaTable();
        tableTrace.table.target.schema = defaultSchema(tableConfig.getTarget().getSchema());
        tableTrace.table.target.name = tableConfig.getTarget().getTable();

        if (tableConfig.getFilters() != null && tableConfig.getFilters().getWhere() != null) {
            tableTrace.filters = Map.of("where", tableConfig.getFilters().getWhere());
        }

        if (tableConfig.getTransformations() != null) {
            tableTrace.transformations = gson.fromJson(gson.toJson(tableConfig.getTransformations()), Map.class);
        }

        if ("incremental".equalsIgnoreCase(tableConfig.getMigrationType()) && tableConfig.getIncrementalConfig() != null) {
            tableTrace.incrementalInfo = new TableTrace.IncrementalInfo();
            tableTrace.incrementalInfo.column = tableConfig.getIncrementalConfig().getColumn();
            tableTrace.incrementalInfo.lastProcessedValue = tableConfig.getIncrementalConfig().getStartValue();
        }
    }

    private Map<String, String> buildFlowConfigVariables(MigrationContract contract,
                                                         TableMigration tableConfig,
                                                         TableTrace tableTrace) {
        Map<String, String> flowConfigVariables = new HashMap<>();
        flowConfigVariables.put("##TABLA_ORIGEN##", requireSqlIdentifier(tableTrace.table.source.name, "source.table"));
        flowConfigVariables.put("##ESQUEMA_ORIGEN##", requireSqlIdentifier(tableTrace.table.source.schema, "source.schema"));
        flowConfigVariables.put("##TABLA_DESTINO##", requireSqlIdentifier(tableTrace.table.target.name, "target.table"));
        flowConfigVariables.put("##ESQUEMA_DESTINO##", requireSqlIdentifier(tableTrace.table.target.schema, "target.schema"));
        flowConfigVariables.put("##QUERY_SQL##", buildSqlQuery(tableConfig));
        flowConfigVariables.put("##TRANSFORMATIONS_JSON##", serializeTransformations(tableConfig));
        flowConfigVariables.put("##EXECUTION_ID##", tableTrace.tableExecutionId);

        flowConfigVariables.put("##SOURCE_DB_URL##", contract.getDatabase().getSourceConnection().getJdbcUrl());
        flowConfigVariables.put("##SOURCE_DB_USER##", contract.getDatabase().getSourceConnection().getUsername());
        flowConfigVariables.put("##SOURCE_DB_PASSWORD##", contract.getDatabase().getSourceConnection().getPassword());
        flowConfigVariables.put("##TARGET_DB_URL##", contract.getDatabase().getTargetConnection().getJdbcUrl());
        flowConfigVariables.put("##TARGET_DB_USER##", contract.getDatabase().getTargetConnection().getUsername());
        flowConfigVariables.put("##TARGET_DB_PASSWORD##", contract.getDatabase().getTargetConnection().getPassword());
        return flowConfigVariables;
    }

    private String buildSqlQuery(TableMigration tableConfig) {
        String sourceSchema = requireSqlIdentifier(defaultSchema(tableConfig.getSource().getSchema()), "source.schema");
        String sourceTable = requireSqlIdentifier(tableConfig.getSource().getTable(), "source.table");
        String whereClause = cleanWhereClause(tableConfig.getFilters());

        String migrationType = tableConfig.getMigrationType() != null
                ? tableConfig.getMigrationType().trim().toLowerCase(Locale.ROOT)
                : "full";

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT *
");
        sql.append("FROM ").append(sourceSchema).append('.').append(sourceTable);

        if ("incremental".equals(migrationType)) {
            IncrementalConfig incrementalConfig = tableConfig.getIncrementalConfig();
            if (incrementalConfig == null) {
                throw new IllegalStateException("La tabla " + sourceTable + " está marcada como incremental pero no tiene incrementalConfig.");
            }

            String incrementalColumn = requireSqlIdentifier(incrementalConfig.getColumn(), "incrementalConfig.column");
            String startValueLiteral = formatIncrementalStartValue(incrementalConfig);
            Integer batchSize = incrementalConfig.getBatchSize() != null ? incrementalConfig.getBatchSize() : 1000;

            sql.append("
WHERE ")
                    .append(incrementalColumn)
                    .append(" > ")
                    .append(startValueLiteral);

            if (!whereClause.isEmpty()) {
                sql.append("
AND ").append(whereClause);
            }

            sql.append("
ORDER BY ").append(incrementalColumn).append(" ASC");
            sql.append("
LIMIT ").append(batchSize);
        } else if (!whereClause.isEmpty()) {
            sql.append("
WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    private String serializeTransformations(TableMigration tableConfig) {
        if (tableConfig.getTransformations() == null) {
            return "";
        }
        return gson.toJson(tableConfig.getTransformations());
    }

    private String cleanWhereClause(FilterConfig filters) {
        if (filters == null || filters.getWhere() == null) {
            return "";
        }
        return filters.getWhere().trim();
    }

    private String formatIncrementalStartValue(IncrementalConfig incrementalConfig) {
        String rawValue = incrementalConfig.getStartValue();
        if (rawValue == null || rawValue.trim().isEmpty()) {
            throw new IllegalStateException("incrementalConfig.startValue no puede estar vacío.");
        }

        String type = incrementalConfig.getType() != null
                ? incrementalConfig.getType().trim().toLowerCase(Locale.ROOT)
                : "string";

        String normalizedValue = rawValue.trim();
        if (isNumericType(type)) {
            return normalizedValue;
        }
        return "'" + normalizedValue.replace("'", "''") + "'";
    }

    private boolean isNumericType(String type) {
        return type.equals("int")
                || type.equals("integer")
                || type.equals("long")
                || type.equals("bigint")
                || type.equals("smallint")
                || type.equals("double")
                || type.equals("float")
                || type.equals("decimal")
                || type.equals("numeric");
    }

    private String defaultSchema(String schema) {
        return (schema == null || schema.trim().isEmpty()) ? "public" : schema.trim();
    }

    private String requireSqlIdentifier(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("El identificador SQL " + fieldName + " está vacío.");
        }
        String trimmed = value.trim();
        if (!SQL_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalStateException("El identificador SQL " + fieldName + " contiene caracteres no permitidos: " + trimmed);
        }
        return trimmed;
    }

    private TableMigration findTableConfig(MigrationContract contract, String sourcePhysicalId) {
        String normalizedPhysicalId = normalizeName(sourcePhysicalId);
        for (Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
            if (TableIdentityUtils.toSourcePhysicalId(entry.getValue()).equals(normalizedPhysicalId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Map<String, Set<String>> buildDescendantsIndex(List<TableNode> executionOrder,
                                                           List<ForeignKeyDependency> dependencies) {
        Set<String> included = executionOrder.stream()
                .map(TableNode::getName)
                .map(this::normalizeName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Set<String>> directChildren = new LinkedHashMap<>();
        for (String table : included) {
            directChildren.put(table, new LinkedHashSet<>());
        }

        if (dependencies != null) {
            for (ForeignKeyDependency dependency : dependencies) {
                String parent = normalizeName(dependency.getParentTable());
                String child = normalizeName(dependency.getDependentTable());
                if (included.contains(parent) && included.contains(child)) {
                    directChildren.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(child);
                }
            }
        }

        Map<String, Set<String>> descendantsByParent = new LinkedHashMap<>();
        for (String parent : included) {
            descendantsByParent.put(parent, collectDescendants(parent, directChildren));
        }
        return descendantsByParent;
    }

    private Set<String> collectDescendants(String root, Map<String, Set<String>> directChildren) {
        Set<String> descendants = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(directChildren.getOrDefault(root, Collections.emptySet()));

        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (descendants.add(current)) {
                for (String child : directChildren.getOrDefault(current, Collections.emptySet())) {
                    pending.addLast(child);
                }
            }
        }

        return descendants;
    }

    private void blockDescendants(String failedTable,
                                  Map<String, Set<String>> descendantsByParent,
                                  Map<String, String> blockedReasons) {
        String normalizedFailedTable = normalizeName(failedTable);
        Set<String> descendants = descendantsByParent.getOrDefault(normalizedFailedTable, Collections.emptySet());
        for (String descendant : descendants) {
            blockedReasons.putIfAbsent(descendant,
                    "Bloqueada por dependencia transitiva de la tabla fallida: " + failedTable);
        }
    }

    private String sanitizeGroupName(String tableName) {
        return tableName == null ? "tabla" : tableName.replace('.', '_').replace(' ', '_');
    }

    private String normalizeName(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Monitoriza el Process Group cada 3 segundos leyendo su status de NiFi.
     * Solo determina finalización; la auditoría de registros se calcula con métricas reales de BD destino.
     */
    private void monitorFlowUntilCompletion(String pgId, String tableName) throws Exception {
        logger.info("Esperando inicialización de NiFi para {}...", tableName);
        Thread.sleep(5000);

        int checks = 0;
        while (checks < 1200) {
            JsonObject statusData;
            try {
                statusData = nifiClient.getProcessGroupStatus(pgId);
            } catch (Exception e) {
                logger.warn("Fallo temporal consultando NiFi en paso {}. Re-intentando...", checks);
                Thread.sleep(3000);
                checks++;
                continue;
            }

            JsonObject snap = statusData.getAsJsonObject("processGroupStatus").getAsJsonObject("aggregateSnapshot");
            int activeThreads = snap.get("activeThreadCount").getAsInt();
            int queuedCount = snap.get("queuedCount").getAsInt();
            String bytesRead = snap.get("bytesRead").getAsString();

            if (checks % 10 == 0) {
                logger.info("⏳ NiFi Monitor [{}]: Procesando... ActiveThreads={}, Queued={}, Read={}", tableName, activeThreads, queuedCount, bytesRead);
            }

            if (activeThreads == 0 && queuedCount == 0 && checks > 2) {
                return;
            }

            Thread.sleep(3000);
            checks++;
        }

        throw new RuntimeException("Timeout de NiFi (1 HORA) alcanzado para " + tableName + ".");
    }
}

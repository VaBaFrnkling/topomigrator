package es.upm.tfg.topomigrator.execution;

import es.upm.tfg.topomigrator.audit.ExecutionIdGenerator;
import es.upm.tfg.topomigrator.audit.SummaryTrace;
import es.upm.tfg.topomigrator.audit.TableTrace;
import es.upm.tfg.topomigrator.audit.Timing;
import es.upm.tfg.topomigrator.audit.TraceabilityManager;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.orchestration.dependency.TableNode;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Motor de ejecución que coordina la instanciación de flujos de NiFi,
 * supervisa su estado en vivo, y genera la auditoría final JSON.
 */
public class ExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngine.class);
    private final NiFiClient nifiClient;
    private final TraceabilityManager traceManager;
    private final TableMetricsService metricsService;
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public ExecutionEngine() {
        this.nifiClient = new NiFiClient();
        this.traceManager = new TraceabilityManager();
        this.metricsService = new TableMetricsService();
    }

    public void executeMigration(List<TableNode> executionOrder, MigrationContract contract) {
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

        try {
            java.nio.file.Path flowPath = java.nio.file.Paths.get("flows", "MainMigration.json");
            if (!java.nio.file.Files.exists(flowPath)) {
                logger.error("ERROR FATAL: Plantilla base {} no encontrada. Abortando motor NiFi.", flowPath.toAbsolutePath());
                throw new IllegalStateException("Plantilla MainMigration.json no existe en el disco.");
            }

            // Arrancamos cliente y sacamos el ID Raíz (el JWT aquí nos sirve solo para el Root)
            nifiClient.authenticate();
            String rootId = nifiClient.getRootProcessGroupId();

            int yOffset = 0;
            int orderCounter = 1;

            for (TableNode tableNode : executionOrder) {
                String tableName = tableNode.getName();
                logger.info(">> [{}/{}] Invocando despliegue y Start para la tabla: {}", orderCounter, executionOrder.size(), tableName);

                TableTrace tableTrace = new TableTrace();
                tableTrace.executionId = executionId;
                tableTrace.tableExecutionId = idGenerator.getTableExecutionId(orderCounter);
                tableTrace.executionOrder = orderCounter;
                tableTrace.timing = new Timing();
                tableTrace.timing.startTime = LocalDateTime.now().format(formatter);
                tableTrace.auditMetrics = new TableTrace.AuditMetrics();
                tableTrace.auditMetrics.strategy = "SOURCE_QUERY_COUNT_WITH_TARGET_DELTA_VALIDATION";
                tableTrace.auditMetrics.warnings = new ArrayList<>();
                long tStartMs = System.currentTimeMillis();

                // Búsqueda case-insensitive para evitar desajustes provocados por la normalización
                // estructural del resolutor de dependencias de Kahn.
                TableMigration tableConfig = null;
                for (java.util.Map.Entry<String, TableMigration> entry : contract.getTables().entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(tableName)) {
                        tableConfig = entry.getValue();
                        tableName = entry.getKey(); // Recuperar exactitud morfológica original
                        break;
                    }
                }

                if (tableConfig != null) {
                    tableTrace.migrationType = tableConfig.getMigrationType();
                    tableTrace.table = new TableTrace.TableMapping();
                    tableTrace.table.source = new TableTrace.SchemaTable();
                    tableTrace.table.source.schema = tableConfig.getSource().getSchema();
                    tableTrace.table.source.name = tableConfig.getSource().getTable();

                    tableTrace.table.target = new TableTrace.SchemaTable();
                    tableTrace.table.target.schema = tableConfig.getTarget().getSchema();
                    tableTrace.table.target.name = tableConfig.getTarget().getTable();

                    // Inyección de rastreo incremental en el doc. de Auditoría
                    if ("incremental".equalsIgnoreCase(tableConfig.getMigrationType()) && tableConfig.getIncrementalConfig() != null) {
                        tableTrace.incrementalInfo = new TableTrace.IncrementalInfo();
                        tableTrace.incrementalInfo.column = tableConfig.getIncrementalConfig().getColumn();
                        tableTrace.incrementalInfo.lastProcessedValue = tableConfig.getIncrementalConfig().getStartValue();
                    }
                }

                String groupName = "Migracion_" + tableName;
                Long targetRowsBefore = null;
                Long targetRowsAfter = null;
                Long sourceSelectedRows = null;

                try {
                    if (tableConfig == null) {
                        throw new IllegalStateException("No se encontró configuración de migración para la tabla " + tableName);
                    }

                    sourceSelectedRows = safelyCountSourceRows(contract, tableConfig, tableTrace);
                    targetRowsBefore = safelyCountTargetRows(contract, tableConfig, tableTrace);

                    tableTrace.auditMetrics.sourceSelectedRecords = sourceSelectedRows;
                    tableTrace.auditMetrics.targetRowsBefore = targetRowsBefore;

                    // Refrescar el token JWT por tabla: evita el '401 Unauthorized'
                    // en migraciones que duren largas horas.
                    nifiClient.authenticate();

                    // Mapa de variables dinámicas a inyectar y reemplazar en el JSON del flow para que NiFi sepa a quién apuntar
                    java.util.Map<String, String> flowConfigVariables = new HashMap<>();
                    flowConfigVariables.put("##TABLA_ORIGEN##", tableTrace.table.source.name);
                    flowConfigVariables.put("##ESQUEMA_ORIGEN##", tableTrace.table.source.schema != null ? tableTrace.table.source.schema : "public");
                    flowConfigVariables.put("##TABLA_DESTINO##", tableTrace.table.target.name);
                    flowConfigVariables.put("##ESQUEMA_DESTINO##", tableTrace.table.target.schema != null ? tableTrace.table.target.schema : "public");

                    // Inyección de parámetros de conexión JDBC desde datasources.yaml al Parameter Context de NiFi
                    flowConfigVariables.put("##SOURCE_DB_URL##", contract.getDatabase().getSourceConnection().getJdbcUrl());
                    flowConfigVariables.put("##SOURCE_DB_USER##", contract.getDatabase().getSourceConnection().getUsername());
                    flowConfigVariables.put("##SOURCE_DB_PASSWORD##", contract.getDatabase().getSourceConnection().getPassword());
                    flowConfigVariables.put("##TARGET_DB_URL##", contract.getDatabase().getTargetConnection().getJdbcUrl());
                    flowConfigVariables.put("##TARGET_DB_USER##", contract.getDatabase().getTargetConnection().getUsername());
                    flowConfigVariables.put("##TARGET_DB_PASSWORD##", contract.getDatabase().getTargetConnection().getPassword());

                    // Inyección paramétrica para lógicas de NiFi Condicionales (Ej: ExecuteSQL dinámico)
                    String migType = tableConfig.getMigrationType() != null ? tableConfig.getMigrationType().toLowerCase() : "full";
                    flowConfigVariables.put("##TIPO_MIGRACION##", migType);
                    if ("incremental".equals(migType) && tableConfig.getIncrementalConfig() != null) {
                        flowConfigVariables.put("##COLUMNA_INCREMENTAL##", tableConfig.getIncrementalConfig().getColumn());
                        flowConfigVariables.put("##VALOR_INICIAL##", tableConfig.getIncrementalConfig().getStartValue());
                    } else {
                        flowConfigVariables.put("##COLUMNA_INCREMENTAL##", "");
                        flowConfigVariables.put("##VALOR_INICIAL##", "");
                    }

                    String pgId = nifiClient.uploadFlowDefinition(rootId, groupName, yOffset, flowPath, flowConfigVariables);
                    nifiClient.changeProcessGroupState(pgId, "RUNNING");

                    // Polling para saber cuándo termina (activos = 0 y colas = 0)
                    monitorFlowUntilCompletion(pgId, tableName);

                    nifiClient.changeProcessGroupState(pgId, "STOPPED");

                    targetRowsAfter = safelyCountTargetRows(contract, tableConfig, tableTrace);
                    tableTrace.auditMetrics.targetRowsAfter = targetRowsAfter;
                    tableTrace.auditMetrics.targetNetDelta = computeNetDelta(targetRowsBefore, targetRowsAfter);

                    tableTrace.recordsProcessed = resolveAuditedProcessedRecords(sourceSelectedRows, tableTrace.auditMetrics.targetNetDelta, tableTrace);
                    tableTrace.status = "SUCCESS";
                    summary.tables.successful++;

                } catch (Exception e) {
                    logger.error("Error migrando tabla {}: {}", tableName, e.getMessage());
                    targetRowsAfter = safelyCountTargetRows(contract, tableConfig, tableTrace);
                    tableTrace.auditMetrics.targetRowsAfter = targetRowsAfter;
                    tableTrace.auditMetrics.targetNetDelta = computeNetDelta(targetRowsBefore, targetRowsAfter);
                    finalizeAuditConsistency(tableTrace, sourceSelectedRows, tableTrace.auditMetrics.targetNetDelta);

                    tableTrace.status = "FAILED";
                    summary.tables.failed++;
                    SummaryTrace.FailedTable ft = new SummaryTrace.FailedTable();
                    ft.table = tableName;
                    ft.reason = e.getMessage();
                    summary.failedTables.add(ft);
                }

                tableTrace.timing.endTime = LocalDateTime.now().format(formatter);
                tableTrace.timing.durationMs = System.currentTimeMillis() - tStartMs;

                SummaryTrace.TableExecutionSummary s = new SummaryTrace.TableExecutionSummary();
                s.table = tableName;
                s.executionId = tableTrace.tableExecutionId;
                s.order = orderCounter;
                s.status = tableTrace.status;
                s.records = tableTrace.recordsProcessed;
                s.durationMs = tableTrace.timing.durationMs;
                s.sourceSelectedRecords = tableTrace.auditMetrics != null ? tableTrace.auditMetrics.sourceSelectedRecords : null;
                s.targetNetDeltaRecords = tableTrace.auditMetrics != null ? tableTrace.auditMetrics.targetNetDelta : null;
                s.auditConsistencyStatus = tableTrace.auditMetrics != null ? tableTrace.auditMetrics.consistencyStatus : null;
                summary.tableExecutionDetails.add(s);

                traceManager.writeTableTrace(tableTrace);
                yOffset += 300;
                orderCounter++;
            }

        } catch (Exception e) {
            logger.error("Fallo crítico general en el motor de ejecución y tracking: ", e);
        } finally {
            summary.timing.endTime = LocalDateTime.now().format(formatter);
            summary.timing.durationMs = System.currentTimeMillis() - globalStartMs;
            traceManager.writeSummary(summary);
            logger.info("Migración y Auditoría JSON completada.");
        }
    }

    /**
     * Monitoriza el Process Group cada 3 segundos leyendo su status de NiFi.
     * Ya no intenta estimar registros usando bytes, solo espera la finalización.
     */
    private void monitorFlowUntilCompletion(String pgId, String tableName) throws Exception {
        logger.info("Esperando inicialización de NiFi para {}...", tableName);
        Thread.sleep(5000); // Dar margen inicial

        int checks = 0;

        // Timeout de producción de emergencia: 1200 checks * 3s = 3600 segundos (1 hora)
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

        logger.warn("Timeout de NiFi (1 HORA) alcanzado para {}. Forzando cierre del seguimiento del Thread.", tableName);
    }

    private Long safelyCountSourceRows(MigrationContract contract, TableMigration tableConfig, TableTrace tableTrace) {
        try {
            return metricsService.countSourceSelectedRows(contract, tableConfig);
        } catch (Exception e) {
            logger.warn("No se pudo contar la selección de origen para auditoría en {}.{}: {}",
                    tableConfig.getSource().getSchema(),
                    tableConfig.getSource().getTable(),
                    e.getMessage());
            tableTrace.auditMetrics.warnings.add("No se pudo calcular sourceSelectedRecords: " + e.getMessage());
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
            logger.warn("No se pudo contar filas en destino para auditoría en {}.{}: {}",
                    tableConfig.getTarget().getSchema(),
                    tableConfig.getTarget().getTable(),
                    e.getMessage());
            tableTrace.auditMetrics.warnings.add("No se pudo calcular targetRows: " + e.getMessage());
            return null;
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
                tableTrace.auditMetrics.consistencyStatus = "MATCH";
            } else {
                tableTrace.auditMetrics.consistencyStatus = "MISMATCH";
                tableTrace.auditMetrics.warnings.add(
                        "La selección en origen (" + sourceSelectedRows + ") no coincide con el delta neto en destino (" + targetNetDelta + "). " +
                                "Se usa sourceSelectedRecords como métrica principal y targetNetDelta solo como validación auxiliar."
                );
            }
            return;
        }

        if (sourceSelectedRows != null) {
            tableTrace.auditMetrics.consistencyStatus = "SOURCE_ONLY";
            return;
        }

        if (targetNetDelta != null) {
            tableTrace.auditMetrics.consistencyStatus = "TARGET_DELTA_ONLY";
            return;
        }

        tableTrace.auditMetrics.consistencyStatus = "UNAVAILABLE";
    }
}

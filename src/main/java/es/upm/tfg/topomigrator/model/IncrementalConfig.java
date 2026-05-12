package es.upm.tfg.topomigrator.model;

import java.util.List;

/**
 * Configuración para migraciones incrementales.
 *
 * column + startValue definen el cursor inicial. A partir de la primera
 * ejecución correcta, TopoMigrator usa el estado persistido para continuar
 * desde el último valor realmente procesado.
 */
public class IncrementalConfig {

    private String column;
    private String type;
    private String startValue;
    private Integer batchSize;
    private String loadStrategy;
    private List<String> idempotencyKeyColumns;

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStartValue() {
        return startValue;
    }

    public void setStartValue(String startValue) {
        this.startValue = startValue;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Estrategia de carga en destino para incremental: upsert, append o append_only.
     * Si no se indica, ExecutionEngine usa upsert por defecto e intenta inferir la PK de destino.
     * Si no hay PK ni idempotencyKeyColumns, la tabla incremental falla para evitar duplicados silenciosos.
     */
    public String getLoadStrategy() {
        return loadStrategy;
    }

    public void setLoadStrategy(String loadStrategy) {
        this.loadStrategy = loadStrategy;
    }

    /**
     * Columnas usadas por NiFi PutDatabaseRecord como Update Keys cuando la
     * estrategia incremental es upsert. Si se omiten, se intentan inferir de la
     * clave primaria de la tabla destino.
     */
    public List<String> getIdempotencyKeyColumns() {
        return idempotencyKeyColumns;
    }

    public void setIdempotencyKeyColumns(List<String> idempotencyKeyColumns) {
        this.idempotencyKeyColumns = idempotencyKeyColumns;
    }
}

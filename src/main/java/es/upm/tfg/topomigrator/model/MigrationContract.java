package es.upm.tfg.topomigrator.model;

import java.util.Map;

public class MigrationContract {

    private MigrationInfo migration;
    private DatabaseConfig database;

    private Map<String, TableMigration> tables;

    public MigrationInfo getMigration() {
        return migration;
    }

    public void setMigration(MigrationInfo migration) {
        this.migration = migration;
    }

    public DatabaseConfig getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }


    public Map<String, TableMigration> getTables() {
        return tables;
    }

    public void setTables(Map<String, TableMigration> tables) {
        this.tables = tables;
    }

    @Override
    public String toString() {
        return "MigrationContract{migration=" + migration + ", tables=" + (tables != null ? tables.keySet() : "[]")
                + "}";
    }
}

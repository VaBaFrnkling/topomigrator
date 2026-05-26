package es.upm.tfg.topomigrator.config;

import es.upm.tfg.topomigrator.model.MigrationContract;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContractLoaderQualityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadMergesContractWithDatasourceYamlAndEnvironment() throws Exception {
        Path contractPath = writeContractYaml();
        Path datasourcesPath = writeDatasourcesYaml();
        Map<String, String> env = Map.of(
                "SOURCE_JDBC_URL", "jdbc:postgresql://source-host:5432/source_db",
                "SOURCE_USER", "source_user",
                "SOURCE_PASSWORD", "source_password",
                "TARGET_JDBC_URL", "jdbc:postgresql://target-host:5432/target_db",
                "TARGET_USER", "target_user",
                "TARGET_PASSWORD", "target_password"
        );

        MigrationContract contract = new ContractLoader(datasourcesPath, env::get, () -> "os-user").load(contractPath);

        assertEquals("customer-migration", contract.getMigration().getName());
        assertEquals("os-user", contract.getMigration().getAuthor());
        assertEquals("jdbc:postgresql://source-host:5432/source_db", contract.getDatabase().getSourceConnection().getJdbcUrl());
        assertEquals("target_user", contract.getDatabase().getTargetConnection().getUsername());
        assertEquals("customers", contract.getTables().get("customers").getTarget().getTable());
    }

    @Test
    public void loadFailsWhenRequiredDatasourceVariableIsMissing() throws Exception {
        Path contractPath = writeContractYaml();
        Path datasourcesPath = writeDatasourcesYaml();
        Map<String, String> incompleteEnv = Map.of(
                "SOURCE_USER", "source_user",
                "SOURCE_PASSWORD", "source_password",
                "TARGET_JDBC_URL", "jdbc:postgresql://target-host:5432/target_db",
                "TARGET_USER", "target_user",
                "TARGET_PASSWORD", "target_password"
        );

        IOException error = assertThrows(IOException.class,
                () -> new ContractLoader(datasourcesPath, incompleteEnv::get, () -> "os-user").load(contractPath));

        assertTrue(error.getMessage().contains("No se pudo cargar el contrato"));
    }

    private Path writeContractYaml() throws IOException {
        Path path = temporaryFolder.newFile("contract.yaml").toPath();
        Files.writeString(path, ""
                + "migration:\n"
                + "  name: customer-migration\n"
                + "  version: '1.0'\n"
                + "  author: ${USER}\n"
                + "tables:\n"
                + "  customers:\n"
                + "    enabled: true\n"
                + "    source:\n"
                + "      schema: public\n"
                + "      table: customers\n"
                + "    target:\n"
                + "      schema: public\n"
                + "      table: customers\n"
                + "    migrationType: full\n");
        return path;
    }

    private Path writeDatasourcesYaml() throws IOException {
        Path path = temporaryFolder.newFile("datasources.yaml").toPath();
        Files.writeString(path, ""
                + "source:\n"
                + "  driver: org.postgresql.Driver\n"
                + "  driverLocation: /drivers/postgresql.jar\n"
                + "  databaseType: PostgreSQL\n"
                + "  jdbcUrl: ${SOURCE_JDBC_URL}\n"
                + "  username: ${SOURCE_USER}\n"
                + "  password: ${SOURCE_PASSWORD}\n"
                + "target:\n"
                + "  driver: org.postgresql.Driver\n"
                + "  driverLocation: /drivers/postgresql.jar\n"
                + "  databaseType: PostgreSQL\n"
                + "  jdbcUrl: ${TARGET_JDBC_URL}\n"
                + "  username: ${TARGET_USER}\n"
                + "  password: ${TARGET_PASSWORD}\n");
        return path;
    }
}

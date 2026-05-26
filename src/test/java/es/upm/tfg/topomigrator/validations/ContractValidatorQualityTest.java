package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidContractException;
import es.upm.tfg.topomigrator.model.IncrementalConfig;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.model.TableMigration;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertThrows;

public class ContractValidatorQualityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validateAcceptsACompleteContractWithActiveTables() throws Exception {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Path sourceYaml = writeSectionOrder("migration:\n  name: ok\n  version: '1.0'\ntables:\n");

        ContractValidator.validate(contract, sourceYaml);
    }

    @Test
    public void validateRejectsSqlIdentifierInjectionInTableNames() throws Exception {
        TableMigration table = QualityTestData.fullTable("customers");
        table.getSource().setTable("customers;drop_table");
        MigrationContract contract = QualityTestData.contractWith(table);
        Path sourceYaml = writeSectionOrder("migration:\n  name: bad\n  version: '1.0'\ntables:\n");

        assertThrows(InvalidContractException.class, () -> ContractValidator.validate(contract, sourceYaml));
    }

    @Test
    public void validateRejectsIncrementalTablesWithoutCursorConfiguration() throws Exception {
        TableMigration table = QualityTestData.incrementalTable("orders");
        table.setIncrementalConfig(new IncrementalConfig());
        MigrationContract contract = QualityTestData.contractWith(table);
        Path sourceYaml = writeSectionOrder("migration:\n  name: bad\n  version: '1.0'\ntables:\n");

        assertThrows(InvalidContractException.class, () -> ContractValidator.validate(contract, sourceYaml));
    }

    @Test
    public void validateRejectsContractsWithoutAnyActiveTable() throws Exception {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.inactiveTable("customers"));
        Path sourceYaml = writeSectionOrder("migration:\n  name: bad\n  version: '1.0'\ntables:\n");

        assertThrows(InvalidContractException.class, () -> ContractValidator.validate(contract, sourceYaml));
    }

    private Path writeSectionOrder(String content) throws Exception {
        Path file = temporaryFolder.newFile("contract-order.yaml").toPath();
        Files.writeString(file, content);
        return file;
    }
}

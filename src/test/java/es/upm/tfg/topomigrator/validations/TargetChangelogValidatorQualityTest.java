package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class TargetChangelogValidatorQualityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validateAcceptsChangelogForActiveTargetTable() throws Exception {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));
        Path changelogsDir = temporaryFolder.newFolder("changelogs").toPath();
        writeChangelog(changelogsDir, "public", "customers");

        TargetChangelogValidator.validate(contract, changelogsDir);
    }

    @Test
    public void validateFailsWhenActiveTargetTableHasNoChangelog() throws Exception {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("missing_table"));
        Path changelogsDir = temporaryFolder.newFolder("changelogs").toPath();
        writeChangelog(changelogsDir, "public", "other_table");

        assertThrows(InvalidChangelogException.class, () -> TargetChangelogValidator.validate(contract, changelogsDir));
    }

    @Test
    public void findChangelogForTargetRequiresExactSchemaTableFileName() throws Exception {
        Path changelogsDir = temporaryFolder.newFolder("changelogs").toPath();
        writeChangelog(changelogsDir, "public", "customers");

        assertEquals("public.customers.yaml",
                TargetChangelogValidator.buildExpectedFileName("public", "customers"));
        assertNotNull(TargetChangelogValidator.findChangelogForTarget(changelogsDir, "public", "customers"));
        assertNull(TargetChangelogValidator.findChangelogForTarget(changelogsDir, "other_schema", "customers"));
    }

    private void writeChangelog(Path directory, String schema, String table) throws Exception {
        Files.writeString(directory.resolve(schema + "." + table + ".yaml"), ""
                + "databaseChangeLog:\n"
                + "  - changeSet:\n"
                + "      id: 1\n"
                + "      author: quality-test\n"
                + "      changes:\n"
                + "        - createTable:\n"
                + "            schemaName: " + schema + "\n"
                + "            tableName: " + table + "\n"
                + "            columns:\n"
                + "              - column:\n"
                + "                  name: id\n"
                + "                  type: int\n");
    }
}

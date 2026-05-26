package es.upm.tfg.topomigrator.validations;

import es.upm.tfg.topomigrator.exceptions.InvalidChangelogException;
import es.upm.tfg.topomigrator.model.MigrationContract;
import es.upm.tfg.topomigrator.support.QualityTestData;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class TargetChangelogValidatorQualityTest {

    @Test
    public void validateAcceptsExistingProjectChangelogForActiveTargetTable() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("customers"));

        TargetChangelogValidator.validate(contract);
    }

    @Test
    public void validateFailsWhenActiveTargetTableHasNoChangelog() {
        MigrationContract contract = QualityTestData.contractWith(QualityTestData.fullTable("missing_table"));

        assertThrows(InvalidChangelogException.class, () -> TargetChangelogValidator.validate(contract));
    }

    @Test
    public void findChangelogForTargetRequiresExactSchemaTableFileName() {
        Path changelogsDir = Path.of("changelogs", "tables");

        assertEquals("public.customers.yaml",
                TargetChangelogValidator.buildExpectedFileName("public", "customers"));
        assertNotNull(TargetChangelogValidator.findChangelogForTarget(changelogsDir, "public", "customers"));
        assertNull(TargetChangelogValidator.findChangelogForTarget(changelogsDir, "other_schema", "customers"));
    }
}

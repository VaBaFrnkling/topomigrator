package es.upm.tfg.topomigrator.execution;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class NiFiClientQualityTest {

    @Test
    public void cleanupStaleTopomigratorProcessGroupsContinuesAfterResidualCleanupFailureAndQuarantinesGroup() throws Exception {
        ResilientCleanupNiFiClient client = new ResilientCleanupNiFiClient();

        client.cleanupStaleTopomigratorProcessGroups("root");

        assertEquals(List.of("stale-1", "stale-2"), client.strictCleanupAttempts);
        assertEquals(List.of("stale-1"), client.quarantinedGroupIds);
    }

    private static final class ResilientCleanupNiFiClient extends NiFiClient {
        private final List<String> strictCleanupAttempts = new ArrayList<>();
        private final List<String> quarantinedGroupIds = new ArrayList<>();

        private ResilientCleanupNiFiClient() {
            super("https://nifi.invalid/nifi-api", "user", "password", false);
        }

        @Override
        protected void ensureAuthenticated() {
        }

        @Override
        List<ProcessGroupRef> findTopomigratorResidualProcessGroups(String rootProcessGroupId) {
            return List.of(
                    new ProcessGroupRef("stale-1", "Migracion_customers"),
                    new ProcessGroupRef("stale-2", "tm-exec-001__Migracion_orders")
            );
        }

        @Override
        void cleanupProcessGroupStrict(String processGroupId) {
            strictCleanupAttempts.add(processGroupId);
            if ("stale-1".equals(processGroupId)) {
                throw new RuntimeException("timeout vaciando colas");
            }
        }

        @Override
        String quarantineResidualProcessGroupBestEffort(ProcessGroupRef group, Exception cleanupError) {
            quarantinedGroupIds.add(group.id);
            return "QUARANTINE_" + group.name;
        }

        @Override
        String describeResidualProcessGroup(ProcessGroupRef group) {
            return "groupId=" + group.id + ", queued=4, controllerServicesPending=1";
        }
    }
}

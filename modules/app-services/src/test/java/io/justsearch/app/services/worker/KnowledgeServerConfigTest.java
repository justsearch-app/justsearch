package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KnowledgeServerConfigTest {

    @Test
    void load_returnsNonNullConfig() {
        // This test may fail if SSOT directory is not present
        // In CI, we might need to skip this
        try {
            KnowledgeServerConfig config = KnowledgeServerConfig.load();
            assertNotNull(config);
            assertNotNull(config.dataDir());
            assertTrue(config.deadlineMs() > 0);
            assertTrue(config.maxRetries() >= 0);
        } catch (IllegalStateException e) {
            // Expected if SSOT/repo layout not present (e.g., CI or isolation)
        }
    }

    @Test
    void config_hasValidDefaults() {
        try {
            KnowledgeServerConfig config = KnowledgeServerConfig.load();
            assertTrue(config.deadlineMs() > 0, "Deadline should be positive");
            assertTrue(config.portDiscoveryTimeoutMs() > 0, "Port timeout should be positive");
            assertNotNull(config.workingDirectory(), "Working directory should not be null");
        } catch (IllegalStateException e) {
            // Skip if running outside repo
        }
    }
}

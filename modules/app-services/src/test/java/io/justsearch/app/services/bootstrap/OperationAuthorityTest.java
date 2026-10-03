/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.intent.DurableGrantStore;
import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationAuthorityTest {
  @Test
  void writableAuthorityLoadsTheConfiguredDirectoryAndRefusesCorruptGrants(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("watched_roots.json"), "[]");
    Path grantsFile = directory.resolve("ui").resolve("durable-grants.json");
    var persisted = new DurableGrantStore(Clock.systemUTC(), grantsFile);
    persisted.grantAllowAlways("core.from-disk", SourceTier.UNTRUSTED);
    var authority = OperationAuthority.load(directory, PersistenceMode.READ_WRITE);
    assertTrue(authority.grants().isAllowed("core.from-disk", RiskTier.MEDIUM, TestEngineContexts.agent()));
    Files.writeString(grantsFile, "{invalid-grants");
    assertThrows(CorruptDurableStoreException.class,
        () -> OperationAuthority.load(directory, PersistenceMode.READ_WRITE));
    assertEquals("{invalid-grants", Files.readString(grantsFile));
  }

  @Test
  void corruptRootsPreventAuthorityPublicationEvenWithMemoryOnlyGrants(@TempDir Path directory) throws Exception {
    Path rootsFile = directory.resolve("watched_roots.json");
    Files.writeString(rootsFile, "{invalid-roots");
    assertThrows(CorruptDurableStoreException.class,
        () -> OperationAuthority.load(directory, PersistenceMode.IN_MEMORY));
    assertEquals("{invalid-roots", Files.readString(rootsFile));
  }
}

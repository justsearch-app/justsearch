/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** The embedded fixture must never borrow the developer's profile. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
final class EngineTestHarnessIsolationTest {
  @TempDir Path directory;

  @Test
  void watchedRootsStayInsideHarnessDataDirectory() throws Exception {
    String previousHome = System.getProperty("user.home");
    String previousData = System.getProperty("justsearch.data.dir");
    var previousConfig = ConfigStore.globalOrNull();
    Path home = Files.createDirectories(directory.resolve("sandbox-home"));
    Path data = directory.resolve("data");
    // Model the Linux default explicitly, even if the runner has a data-dir environment override
    // or uses a platform whose default profile lives outside user.home.
    String sandboxDefault = home.resolve(".justsearch").toString();
    System.setProperty("user.home", home.toString());
    System.setProperty("justsearch.data.dir", sandboxDefault);
    try {
      try (var engine = EngineTestHarness.start(data)) {
        Path documents = Files.createDirectories(directory.resolve("documents"));
        Files.writeString(documents.resolve("marker.txt"), "isolated watched roots marker");
        engine.client().addWatchedPath(documents, TestEngineContexts.FOREGROUND);
        assertTrue(engine.awaitIndexed(1, 60_000));
        // Wait for the asynchronous walk to persist membership, wherever it resolved its store.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        Path legacy = home.resolve(".justsearch/watched_roots.json");
        Path expected = data.resolve("watched_roots.json");
        while (!Files.exists(legacy) && !Files.exists(expected) && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertFalse(Files.exists(legacy), "the fixture wrote watched_roots.json under user.home");
        assertTrue(Files.readString(expected).contains(documents.toString()),
            "watched membership belongs to this harness's data directory");
        assertEquals(data.toAbsolutePath().toString(), System.getProperty("justsearch.data.dir"));
        assertThrows(IllegalStateException.class,
            () -> EngineTestHarness.start(directory.resolve("overlapping-data")),
            "an overlapping harness cannot change the running harness's process properties");
        assertEquals(data.toAbsolutePath().toString(), System.getProperty("justsearch.data.dir"));
        engine.restart();
        assertEquals(data.toAbsolutePath().toString(), System.getProperty("justsearch.data.dir"));
      }
      assertEquals(home.toString(), System.getProperty("user.home"));
      assertEquals(sandboxDefault, System.getProperty("justsearch.data.dir"));
      assertFalse(Files.exists(home.resolve(".justsearch")), "no legacy profile is created or migrated");
    } finally {
      if (previousHome == null) System.clearProperty("user.home");
      else System.setProperty("user.home", previousHome);
      if (previousData == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previousData);
      var installed = ConfigStore.globalOrNull();
      if (installed != null) ConfigStore.restoreGlobal(installed, previousConfig);
    }
  }

  @Test
  void failedStartupRestoresProperties() throws Exception {
    String previousData = System.getProperty("justsearch.data.dir");
    String previousHome = System.getProperty("user.home");
    Path file = Files.writeString(directory.resolve("file"), "not a directory");
    assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> EngineTestHarness.start(file));
    assertEquals(previousData, System.getProperty("justsearch.data.dir"));
    assertEquals(previousHome, System.getProperty("user.home"));
  }
}

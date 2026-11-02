/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.SafeIndexPathOps;
import io.justsearch.configuration.FileOps;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexGenerationGcFailureTest {
  @TempDir Path temp;

  @Test
  void failedMarkingIsIncompleteAndRetainsCandidate() throws Exception {
    var manager = new IndexGenerationManager(temp);
    var active = manager.initializeOrLoad().activeGenerationPath();
    Path old = generation("g-20000101-000001");
    try (var marking = mockStatic(SafeIndexPathOps.class)) {
      marking.when(() -> SafeIndexPathOps.markForDeletion(old, temp.resolve("indices")))
          .thenReturn(new SafeIndexPathOps.MarkResult(old, false, false));
      assertThrows(RuntimeException.class, () -> manager.gcBestEffort(0, false));
      assertTrue(Files.exists(old.resolve("payload")));
      assertTrue(Files.isDirectory(active));
    }
    assertDoesNotThrow(() -> manager.gcBestEffort(0, false));
    assertFalse(Files.exists(old));
    assertTrue(Files.isDirectory(active));
  }

  @Test
  void failedPruningIsIncompleteAndKeepsSentinelForRetry() throws Exception {
    var manager = new IndexGenerationManager(temp);
    var active = manager.initializeOrLoad().activeGenerationPath();
    Path old = generation("g-20000101-000001.del-20260101-000000");
    try (var pruning = mockStatic(FileOps.class)) {
      pruning.when(() -> FileOps.deleteRecursivelyBestEffort(any(), any()))
          .thenReturn(new FileOps.DeleteResult(0, 1));
      assertThrows(RuntimeException.class, () -> manager.gcBestEffort(0, true));
      assertTrue(Files.exists(old.resolve(".justsearch-generation.sentinel")));
      assertTrue(Files.exists(old.resolve("payload")));
    }
    assertEquals(1, manager.gcBestEffort(0, true).prunedCount());
    assertFalse(Files.exists(old));
    assertTrue(Files.isDirectory(active));
  }

  @Test
  void pruneOnlyRetentionAndActiveBuildingProtectionRemainEffective() throws Exception {
    var manager = new IndexGenerationManager(temp);
    var layout = manager.initializeOrLoad();
    var migration = manager.startFreshMigration("gc protection");
    Path first = generation("g-20000101-000001");
    Path second = generation("g-20000101-000002");
    assertEquals(new IndexGenerationManager.GcResult(0, 0), manager.gcBestEffort(0, true));
    assertTrue(Files.exists(first));
    assertEquals(new IndexGenerationManager.GcResult(1, 1), manager.gcBestEffort(1, false));
    assertFalse(Files.exists(first));
    assertTrue(Files.exists(second));
    assertTrue(Files.exists(layout.activeGenerationPath()));
    assertTrue(Files.exists(temp.resolve("indices").resolve(migration.building_generation())));
  }

  private Path generation(String name) throws Exception {
    Path path = Files.createDirectory(temp.resolve("indices").resolve(name));
    Files.writeString(path.resolve(".justsearch-generation.sentinel"), "generation_id=" + name + "\n");
    Files.writeString(path.resolve("payload"), "retained on failure");
    return path;
  }
}

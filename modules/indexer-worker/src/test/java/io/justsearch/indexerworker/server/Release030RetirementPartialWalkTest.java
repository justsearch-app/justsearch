/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.services.CallContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** U6: a listable root with an unreadable child does not certify its buffered SYNC_ROOT. */
@Timeout(240)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementPartialWalkTest {
  @Test
  void partialWalkRetainsReceiptAndBuildUntilACompleteWalk(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("switching", temporary);
    var original = state(root);
    String syncKey;
    Path watched;
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      var receipt = queue.listSwitchBufferOpsStrict().stream().filter(op -> op.op().equals("SYNC_ROOT"))
          .findFirst().orElseThrow();
      watched = Path.of(SwitchBufferSyncRoot.decode(receipt.payload()).rootPath());
      syncKey = receipt.key();
    }
    Path denied = Files.createDirectory(watched.resolve("denied-subtree"));
    Path hidden = Files.writeString(denied.resolve("new.txt"), "partialwalkviolet");
    var permissions = Files.getPosixFilePermissions(denied);
    Files.setPosixFilePermissions(denied, java.util.Set.of());
    assertFalse(Files.isReadable(denied), "real denied subtree precondition");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state());
            activate(server);
            var response = server.appServices().ingestService().syncDirectory(io.justsearch.ipc.SyncDirectoryRequest
                .newBuilder().setRootPath(watched.toString()).build(), CallContext.none());
            assertTrue(response.getError().isBlank(), response.getError());
            assertTrue(response.getDeleteDetectionUnverified(), "a partial walk reports uncertainty on the real interface");
            await(() -> differencesComplete(root)
                && Release030RetirementSafetyTest.queue(server).jobStateCountsStrict().pendingReadyCount() == 0
                && Release030RetirementSafetyTest.queue(server).jobStateCountsStrict().processingCount() == 0,
                "other readable differences settle while the partial SYNC_ROOT stays outstanding", 90);
            assertEquals(original.active_generation(), state(root).active_generation());
            assertEquals(original.building_generation(), state(root).previous_generation());
            Release030RetirementSafetyTest.assertRetained(root, original.building_generation());
            assertTrue(Release030RetirementSafetyTest.queue(server).listSwitchBufferOpsStrict().stream()
                .anyMatch(op -> op.key().equals(syncKey) && op.op().equals("SYNC_ROOT")));
            assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
            assertTrue(search(server, "partialwalkviolet").isEmpty());
          }
        }
        Files.setPosixFilePermissions(denied, permissions);
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          await(() -> state(root).previous_generation() == null
              && search(server, "partialwalkviolet").contains(key(hidden)),
              "confirmed full walk indexes the unread source before retiring B", 100);
          assertEquals(original.active_generation(), state(root).active_generation());
          Release030RetirementSafetyTest.assertRetired(root, original.building_generation());
          assertTrue(Release030RetirementSafetyTest.queue(server).listSwitchBufferOpsStrict().isEmpty());
        }
      }
    } finally {
      Files.setPosixFilePermissions(denied, permissions);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void ordinaryPartialWalkReportsUnverifiedDeletionAcrossRestart(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    String active = state(root).active_generation();
    Path watched = root.resolve("roots/alpha");
    Path denied = Files.createDirectory(watched.resolve("denied-subtree"));
    Path hidden = Files.writeString(denied.resolve("new.txt"), "ordinarypartialviolet");
    var permissions = Files.getPosixFilePermissions(denied);
    Files.setPosixFilePermissions(denied, java.util.Set.of());
    assertFalse(Files.isReadable(denied));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            activate(server);
            var response = server.appServices().ingestService().syncDirectory(io.justsearch.ipc.SyncDirectoryRequest
                .newBuilder().setRootPath(watched.toString()).build(), CallContext.none());
            assertTrue(response.getError().isBlank(), response.getError());
            assertTrue(response.getDeleteDetectionUnverified(), "a partial ordinary walk cannot certify deletion");
            assertTrue(search(server, "nebula").contains(key(watched.resolve("file-01.txt"))));
            assertTrue(search(server, "ordinarypartialviolet").isEmpty());
            assertEquals(active, state(root).active_generation());
          }
        }
        Files.setPosixFilePermissions(denied, permissions);
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          var response = server.appServices().ingestService().syncDirectory(io.justsearch.ipc.SyncDirectoryRequest
              .newBuilder().setRootPath(watched.toString()).build(), CallContext.none());
          assertTrue(response.getError().isBlank(), response.getError());
          assertFalse(response.getDeleteDetectionUnverified(), "a complete walk certifies its own evidence");
          await(() -> search(server, "ordinarypartialviolet").contains(key(hidden)), "confirmed walk indexes the source");
          assertEquals(active, state(root).active_generation());
        }
      }
    } finally {
      Files.setPosixFilePermissions(denied, permissions);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static boolean differencesComplete(Path root) throws Exception {
    var checkpoint = new Properties();
    Path path = root.resolve("data/upgrade-030-recovery.properties");
    if (!Files.exists(path)) return false;
    try (var input = Files.newInputStream(path)) { checkpoint.load(input); }
    return "true".equals(checkpoint.getProperty("retire|differences"));
  }
}

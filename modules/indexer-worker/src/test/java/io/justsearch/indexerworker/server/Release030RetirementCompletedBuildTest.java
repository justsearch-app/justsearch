/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.CleanShutdownMarker;
import io.justsearch.adapters.lucene.runtime.IndexRecoveryMarker;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.indexerworker.services.CallContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Amendment 1: completed B effects without receipts are still obligations, including DONE files. */
@Timeout(360)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementCompletedBuildTest {
  @ParameterizedTest
  @CsvSource({"migrating,false", "switching,false", "migrating,true", "switching,true"})
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void completedBuildEffectsAreReconciledAndUncertaintyRetainsBuild(String fixture, boolean denied,
      @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack(fixture, temporary);
    var original = state(root);
    Path explicit = root.resolve("explicit.txt"), added = root.resolve("completed-b-only.txt");
    Path deleted = root.resolve("roots/beta/file-02.txt");
    Path sentinel = Release030RetirementSafetyTest.buildingSentinel(root);
    String owner = Files.readString(sentinel);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    var permissions = Files.getPosixFilePermissions(explicit);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        Files.writeString(sentinel, "uncertified B during historical fixture admission");
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          Files.writeString(sentinel, owner);
          Release030RetirementSafetyTest.stopMonitor(server);
          Release030ExactFileRecoveryTest.activateNative(server);
          assertTrue(server.appServices().ingestService().resumeMigration(
              io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(), CallContext.none()).getAccepted());
          Release030RetirementSafetyTest.stopMonitor(server);
          await(() -> Boolean.TRUE.equals(Release030ExtractionRecoveryTest.field(server, "migrationEnumeratorDone"))
              && Release030RetirementSafetyTest.queue(server).jobStateCountsStrict().pendingReadyCount() == 0
              && Release030RetirementSafetyTest.queue(server).jobStateCountsStrict().processingCount() == 0,
              "the real historical B enumeration completes before new admissions", 90);
          Files.writeString(explicit, "completedbuildexplicitviolet");
          Files.writeString(added, "completedbuildaddedviolet");
          assertEquals(2, server.appServices().ingestService().submitBatch(io.justsearch.ipc.BatchRequest
              .newBuilder().addFilePaths(explicit.toString()).addFilePaths(added.toString())
              .setRootAdded(true).setForceReindex(true).build(), CallContext.none()).getAcceptedCount());
          await(() -> count(root, "path IN ('" + key(explicit) + "','" + key(added) + "') AND state='DONE'") == 2,
              "real admitted changes finish on B", 60);
          var green = (RunningRuntime) Release030ExtractionRecoveryTest.field(server, "ingestLifecycle");
          green.commitOps().commitAndTrack(CommitReason.DRAIN);
          green.commitOps().maybeRefreshBlocking();
          assertEquals(Release030Fixture.sha256(Files.readAllBytes(added)),
              green.documentFieldOps().getDocumentFieldOrThrow(key(added), "source_sha256"));
          assertTrue(search(server, "completedbuildaddedviolet").isEmpty(), "A has never served B's new file");
          assertTrue(search(server, "copper").contains(key(explicit)), "A still serves the older explicit bytes");
          assertEquals(1, green.indexCountOps().countByIdAndChunksStrict(key(deleted)));
          var queue = Release030RetirementSafetyTest.queue(server);
          assertTrue(queue.putSwitchBufferForGeneration(original.building_generation(), "path:" + key(deleted),
              "DELETE", new SwitchBufferDelete(key(deleted), SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
          green.indexingCoordinator().deleteByIdAndChunks(key(deleted));
          queue.deleteByExactPath(key(deleted));
          green.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
          green.commitOps().maybeRefreshBlocking();
          assertEquals(0, green.indexCountOps().countByIdAndChunksStrict(key(deleted)));
          // Released MIGRATING admissions could finish on B without a retained switch receipt.
          // Remove only these exact versions, after proving the corresponding committed effects.
          var completed = queue.listSwitchBufferOpsStrict().stream()
              .filter(op -> original.building_generation().equals(op.generation())
                  && (op.key().equals("path:" + key(explicit)) || op.key().equals("path:" + key(added))
                      || op.key().equals("path:" + key(deleted)))).toList();
          assertEquals(3, completed.size());
          assertEquals(completed.size(), queue.removeReplayedSwitchBufferOps(completed));
          assertTrue(queue.listSwitchBufferOpsStrict().stream().noneMatch(op -> op.key().equals("path:" + key(added))));
        } finally { Files.writeString(sentinel, owner); }
        Path building = root.resolve("data/index/default/indices").resolve(original.building_generation());
        assertTrue(CleanShutdownMarker.wasClean(building), "the real historical B writer closed cleanly");
        IndexRecoveryMarker.write(building, "obsolete completed migration");
        Files.delete(deleted);
        if (denied) {
          Files.setPosixFilePermissions(explicit, java.util.Set.of());
          assertFalse(Files.isReadable(explicit), "real unreadable completed B source");
          for (int restart = 0; restart < 2; restart++) {
            try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
              assertEquals("IDLE", state(root).migration_state());
              activate(server);
              await(() -> search(server, "completedbuildaddedviolet").contains(key(added)),
                  "other completed B work reaches A while one source is unreadable", 90);
              assertEquals(original.building_generation(), state(root).previous_generation());
              Release030RetirementSafetyTest.assertRetained(root, original.building_generation());
              assertTrue(search(server, "copper").contains(key(explicit)), "unreadable B update retains serving A content");
              assertTrue(Files.exists(root.resolve("data/upgrade-030-recovery.properties")));
            }
          }
          Files.setPosixFilePermissions(explicit, permissions);
        }
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state());
            activate(server);
            await(() -> state(root).previous_generation() == null
                && search(server, "completedbuildexplicitviolet").contains(key(explicit))
                && search(server, "completedbuildaddedviolet").contains(key(added))
                && !search(server, "nebula").contains(key(deleted)),
                "unjournalled completed B effects become durable A results before retirement", 120);
            assertEquals(original.active_generation(), state(root).active_generation());
            assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
            Release030RetirementSafetyTest.assertRetired(root, original.building_generation());
            assertFalse(Files.exists(root.resolve("data/index/default/migration_progress.json")));
          }
        }
      }
    } finally {
      Files.setPosixFilePermissions(explicit, permissions);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }
}

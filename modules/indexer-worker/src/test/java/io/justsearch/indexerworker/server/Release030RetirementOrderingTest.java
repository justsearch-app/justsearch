/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** U6 accepted ordering: newer acknowledged mutations precede a paused old recovery attempt. */
@Timeout(240)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementOrderingTest {
  @ParameterizedTest
  @ValueSource(strings = {"update", "delete", "prefix"})
  void newerAcknowledgementWinsPausedReplayAndRestart(String mutation, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("switching", temporary);
    var original = state(root);
    Path file = Files.writeString(root.resolve("roots/alpha/pending.txt"), "oldreplaycobalt");
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      assertTrue(queue.enqueueAndBufferFileForGeneration(original.building_generation(),
          JobQueue.EnqueueEntry.stat(file).withinRoot(root.resolve("roots/alpha")), null));
    }
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    var reached = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var once = new AtomicBoolean();
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = bootWithBarrier(root, executors,
            transition -> {
              if (transition.point().equals("release030-before-replay") && once.compareAndSet(false, true)) {
                reached.countDown();
                assertTrue(resume.await(30, TimeUnit.SECONDS), "paused recovery is released");
              }
            })) {
          assertEquals("IDLE", state(root).migration_state());
          activate(server);
          assertTrue(reached.await(30, TimeUnit.SECONDS));
          try {
            var sync = server.appServices().ingestService().syncDirectory(io.justsearch.ipc.SyncDirectoryRequest
                .newBuilder().setRootPath(root.resolve("roots/alpha").toString()).setForce(true).build(), CallContext.none());
            assertTrue(sync.getError().isBlank(), sync.getError());
            assertTrue(Release030RetirementSafetyTest.queue(server).listSwitchBufferOpsStrict().stream()
                .anyMatch(op -> original.building_generation().equals(op.generation()) && op.op().equals("SYNC_ROOT")
                    && !op.key().startsWith("release030-root:")), "the accepted sync has its own ordered receipt");
            if (mutation.equals("update")) {
              Files.writeString(file, "newreplayviolet");
              assertEquals(1, server.appServices().ingestService().submitBatch(io.justsearch.ipc.BatchRequest
                  .newBuilder().addFilePaths(file.toString()).setRootAdded(true).setForceReindex(true).build(),
                  CallContext.none()).getAcceptedCount());
            } else if (mutation.equals("delete")) {
              assertTrue(server.appServices().ingestService().deleteById(io.justsearch.ipc.DeleteByIdRequest
                  .newBuilder().setDocId(key(file)).build(), CallContext.none()).getError().isBlank());
            } else {
              assertTrue(server.appServices().ingestService().deleteByPath(io.justsearch.ipc.DeleteByPathRequest
                  .newBuilder().setPath(root.resolve("roots/alpha").toString()).build(), CallContext.none())
                  .getError().isBlank());
            }
            Path unrelated = Files.writeString(root.resolve("roots/beta/unrelated.txt"), "unrelatedviolet");
            assertEquals(1, server.appServices().ingestService().submitBatch(io.justsearch.ipc.BatchRequest
                .newBuilder().addFilePaths(unrelated.toString()).setRootAdded(true).build(), CallContext.none())
                .getAcceptedCount(), "unrelated admission proceeds while old replay is paused");
          } finally { resume.countDown(); }
          await(() -> state(root).previous_generation() == null
              && !Files.exists(root.resolve("data/upgrade-030-recovery.properties"))
              && Release030RetirementSafetyTest.queue(server).jobStateCountsStrict().processingCount() == 0
              && Release030RetirementSafetyTest.queue(server).jobStateCountsStrict().pendingReadyCount() == 0,
              "all old recovery settles after the newer acknowledgement", 120);
          assertFinal(server, file, mutation);
          Release030RetirementSafetyTest.assertRetired(root, original.building_generation());
        } finally { resume.countDown(); }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          assertFinal(server, file, mutation);
          Release030RetirementSafetyTest.assertRetired(root, original.building_generation());
          assertEquals(original.active_generation(), state(root).active_generation());
          assertNull(state(root).previous_generation());
          assertTrue(Release030RetirementSafetyTest.queue(server).listSwitchBufferOpsStrict().isEmpty());
        }
      }
    } finally { resume.countDown(); ConfigStore.restoreGlobal(installed, previous); }
  }

  private static void assertFinal(KnowledgeServer server, Path file, String mutation) throws Exception {
    if (mutation.equals("update")) assertTrue(search(server, "newreplayviolet").contains(key(file)));
    else {
      assertFalse(search(server, "oldreplaycobalt").contains(key(file)), "older recovery cannot undo an acknowledged delete");
      assertEquals(0, server.lifecycleManagerForTests().indexCountOps().countByIdAndChunksStrict(key(file)));
    }
    assertTrue(search(server, "unrelatedviolet").stream().anyMatch(id -> id.endsWith("/unrelated.txt")));
    assertTrue(search(server, "nebula").contains(key(file.getParent().getParent().resolve("beta/file-02.txt"))));
  }

  static KnowledgeServer bootWithBarrier(Path root, TestEngineExecutors executors,
      MigrationTransitionBarrier.Hook hook) throws Exception {
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(root.resolve("data")), null));
    org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(List.of(
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"), null),
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"), null)));
    server.bindExclusionRoots(() -> List.of(root.resolve("roots/alpha"), root.resolve("roots/beta")));
    server.installMigrationBarrierForTests(hook);
    try { server.start(); } catch (Exception | Error failure) { server.close(); throw failure; }
    return server;
  }
}

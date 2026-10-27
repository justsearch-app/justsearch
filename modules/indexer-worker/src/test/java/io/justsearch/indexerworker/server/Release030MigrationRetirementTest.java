/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** U6: retire the release's interrupted build while keeping its compatible serving index. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030MigrationRetirementTest {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"migrating", "switching"})
  void replayRetiresObsoleteBuildAndKeepsServingIdentity(String fixture, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack(fixture, temporary);
    var before = state(root);
    Path updated = Files.writeString(root.resolve("roots/alpha/pending.txt"), "retirementcobalt");
    Path deleted = root.resolve("roots/beta/file-01.txt");
    Files.delete(deleted);
    // Real admission creates a receipt-bearing MIGRATING variant without inventing a schema.
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      assertTrue(queue.enqueueAndBufferFileForGeneration(before.building_generation(),
          JobQueue.EnqueueEntry.stat(updated).withinRoot(root.resolve("roots/alpha")), null));
    }
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state(), "obsolete migration is cancelled at boot");
            assertEquals(before.active_generation(), state(root).active_generation());
            activate(server);
            await(() -> search(server, "retirementcobalt").contains(key(updated))
                && !search(server, "nebula").contains(key(deleted))
                && state(root).previous_generation() == null,
                "replayed effects are durable before B is retired");
            assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
            assertTrue(search(server, "copper").contains(key(root.resolve("roots/alpha/file-00.txt"))));
            assertFalse(Files.exists(root.resolve("data/index/default/indices")
                .resolve(before.building_generation())));
            assertFalse(Files.exists(root.resolve("data/index/default/migration_progress.json")));
            assertEquals(0, ((SqliteJobQueue) Release030ExtractionRecoveryTest.field(server, "jobQueue"))
                .listSwitchBufferOpsStrict().size());
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void backoffNeverHoldsMutationAdmissionDuringForcedNativeCutover(boolean mixed,
      @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpackNativeMigration("migrating", temporary);
    var original = state(root);
    Path deleted = root.resolve("roots/beta/file-01.txt");
    Files.delete(deleted);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      var attempts = new java.util.concurrent.atomic.AtomicInteger();
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          assertEquals("MIGRATING", state(root).migration_state());
          Release030RetirementSafetyTest.stopMonitor(server);
          Release030ExactFileRecoveryTest.activateNative(server);
          Release030RetirementSafetyTest.stopMonitor(server);
          assertTrue(server.appServices().ingestService().resumeMigration(
              io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(),
              io.justsearch.indexerworker.services.CallContext.none()).getAccepted());
          await(() -> Boolean.TRUE.equals(Release030ExtractionRecoveryTest.field(server, "migrationEnumeratorDone"))
              && queue(server).jobStateCountsStrict().pendingReadyCount() == 0
              && queue(server).jobStateCountsStrict().processingCount() == 0,
              "real native enumeration finishes before admitting the work whose backoff is observed");
          assertNull(Release030ExtractionRecoveryTest.field(server, "migrationEnumeratorFailure"));
          var producer = (DefaultWorkerAppServices) server.appServices();
          assertTrue(producer.pauseProducerForCutover(10_000));
          try {
            Path waiting = Files.writeString(root.resolve("roots/beta/waiting-io.txt"), "notyetindexedwaitingcobalt");
            queue(server).enqueueEntries(java.util.List.of(JobQueue.EnqueueEntry.stat(waiting)
                .withinRoot(root.resolve("roots/beta"))), null);
            queue(server).markFailed(waiting, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.IO_FAILED,
                io.justsearch.indexerworker.ingest.IngestionReasonCodes.SOURCE_UNREADABLE,
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE, "scheduled I/O retry"));
            if (mixed) {
              Path ready = Files.writeString(root.resolve("roots/alpha/ready.txt"), "readycobalt");
              assertTrue(queue(server).enqueueAndBufferFileForGeneration(original.building_generation(),
                  JobQueue.EnqueueEntry.stat(ready).withinRoot(root.resolve("roots/alpha")), null));
              assertTrue(queue(server).jobStateCountsStrict().pendingReadyCount() > 0);
            }
            assertTrue(queue(server).jobStateCountsStrict().pendingBackoffCount() > 0);
          } finally { producer.resumeProducerAfterCutover(); }
          await(() -> queue(server).jobStateCountsStrict().pendingReadyCount() == 0
              && queue(server).jobStateCountsStrict().processingCount() == 0,
              "mixed ready work finishes, leaving only scheduled work");
          assertTrue(queue(server).jobStateCountsStrict().pendingBackoffCount() > 0);
          assertTrue(server.appServices().ingestService().requestCutover(
              io.justsearch.ipc.MigrationCutoverRequest.newBuilder().setForceSwitching(true).build(),
              io.justsearch.indexerworker.services.CallContext.none()).getAccepted());
          assertTrue(queue(server).jobStateCountsStrict().pendingBackoffCount() > 0,
              "relevant backoff work is still pending at monitor entry, not exempt independent debt");
          var manager = (io.justsearch.indexerworker.index.IndexGenerationManager)
              Release030ExtractionRecoveryTest.field(server, "indexGenerationManager");
          var runtime = (io.justsearch.adapters.lucene.runtime.LuceneRuntime)
              Release030ExtractionRecoveryTest.field(server, "ingestLifecycle");
          var ticks = new java.util.concurrent.atomic.AtomicInteger(2);
          // Observe entry to live promotion, before its final safety checks. The real server,
          // enumeration, queue and writer remain running; this avoids a timing-only fence probe.
          io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.runMigrationCutoverLoop(
              new io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.CutoverContext(
                  manager, queue(server), () -> ticks.getAndDecrement() > 0, () -> true, () -> null,
                  0, 60_000, 0, () -> runtime, () -> true,
                  () -> fail("scheduled work must not enter deadline/failure cleanup"),
                  org.slf4j.LoggerFactory.getLogger(getClass()),
                  observed -> fail("forceSwitching already entered the switching phase"),
                  () -> { attempts.incrementAndGet(); return null; }));
          Path unrelated = Files.writeString(root.resolve("roots/alpha/unrelated.txt"), "unrelatedcobalt");
          assertEquals(1, server.appServices().ingestService().submitBatch(io.justsearch.ipc.BatchRequest
              .newBuilder().addFilePaths(unrelated.toString()).setRootAdded(true).build(),
              io.justsearch.indexerworker.services.CallContext.none()).getAcceptedCount());
          assertEquals(original.active_generation(), state(root).active_generation());
          assertNotNull(state(root).building_generation());
          assertTrue(search(server, "copper").contains(key(root.resolve("roots/alpha/file-00.txt"))));
          assertTrue(search(server, "nebula").contains(key(deleted)), "A retains old results until cutover is complete");
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          Release030RetirementSafetyTest.stopMonitor(server);
          assertEquals(original.active_generation(), state(root).active_generation());
          assertEquals(original.building_generation(), state(root).building_generation());
          assertTrue(queue(server).jobStateCountsStrict().pendingBackoffCount() > 0);
          assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
          assertTrue(search(server, "copper").contains(key(root.resolve("roots/alpha/file-00.txt"))));
          assertTrue(search(server, "nebula").contains(key(deleted)));
        }
        assertEquals(0, attempts.get(), "U13: backoff-only work must not invoke live promotion");
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  private static SqliteJobQueue queue(KnowledgeServer server) throws Exception {
    return (SqliteJobQueue) Release030ExtractionRecoveryTest.field(server, "jobQueue");
  }

}

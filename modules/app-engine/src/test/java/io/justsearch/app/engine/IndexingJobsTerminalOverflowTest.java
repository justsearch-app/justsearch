/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.observability.ledger.ActionEventJournal;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.services.bootstrap.phases.IndexingJobsBridgeWiring;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.ingest.*;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class IndexingJobsTerminalOverflowTest {
  @Test void everyTerminalOutcomeSurvivesViewOverflowAndReconnectWithoutReplay(@TempDir Path temp) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db")); var owners = new TestEngineExecutors()) {
      queue.open();
      var paths = java.util.stream.IntStream.range(0, 400).mapToObj(i -> temp.resolve("file-" + i)).toList();
      queue.enqueueEntries(paths.stream().map(p -> new JobQueue.EnqueueEntry(p, 1)).toList(), "docs", "scan-42");
      var claims = queue.pollPending(400);
      assertEquals(400, claims.size());
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      var services = mock(WorkerAppServices.class);
      when(services.ingestService()).thenReturn(service);
      try (var client = new EngineKnowledgeClient(owners, () -> services, new ForegroundLoadGate(new ForegroundLoad()),
          5000, 100, IpcTelemetry.noop())) {
        var jobs = new IndexingJobsChangeRegistry(channel(owners, IndexingJobsChangeRegistry.STREAM_ID, "test.jobs"));
        var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.disabled(), channel(owners, ActionLedgerChangeRegistry.STREAM_ID, "test.ledger"));
        var wired = IndexingJobsBridgeWiring.wire(owners, () -> client, jobs, ledger);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reconnected = new CountDownLatch(1);
        var snapshots = new AtomicInteger();
        var blocker = wired.bridge().subscribe(delta -> {
          if (delta instanceof RemoteIndexingJobsBridge.Delta.SnapshotReplaced && snapshots.incrementAndGet() > 1) reconnected.countDown();
          if (delta instanceof RemoteIndexingJobsBridge.Delta.Update update && update.row().state().equals("DONE") && held.getCount() > 0) {
            held.countDown();
            assertDoesNotThrow(() -> assertTrue(release.await(10, TimeUnit.SECONDS)));
          }
        });
        try {
          wired.bridge().start().get(3, TimeUnit.SECONDS);
          // Hold a real delivery, then produce more terminal rows than the 256-frame bound.
          var success = IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
          queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claims.getFirst(), null)), success);
          assertTrue(held.await(2, TimeUnit.SECONDS));
          queue.markDoneTransitions(claims.subList(1, claims.size()).stream().map(c -> new JobQueue.IngestionLedgerTransition(c, null)).toList(), success);
          release.countDown();
          assertTrue(reconnected.await(3, TimeUnit.SECONDS), "the real bounded view must overflow and resubscribe");
          assertEquals(400, ledger.store().recent().size(), "every terminal event must reach activity despite view overflow");
          assertEquals(400, ledger.store().recent().stream().map(ActionEvent::id).distinct().count());
          assertEquals(400, ledger.currentSeq(), "exactly one activity publication per terminal outcome");
          var expectedPaths = new java.util.HashSet<String>();
          for (Path path : paths) expectedPaths.add(java.util.HexFormat.of().formatHex(
              java.security.MessageDigest.getInstance("SHA-256").digest(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
          assertEquals(expectedPaths, ledger.store().recent().stream().map(e -> ((ActionEvent.Index) e).pathHash()).collect(java.util.stream.Collectors.toSet()));
          assertTrue(ledger.store().recent().stream().allMatch(e -> ((ActionEvent.Index) e).scanId().equals("scan-42")));
          assertEquals(400, queue.ingestionOutcomeSummary(0).stream().mapToLong(JobQueue.IngestionOutcomeSummary::count).sum(), "persisted outcome rollups are independent of the head feed");
          assertTrue(wired.bridge().latestSnapshot().isEmpty(), "DONE rows are outside the reconnected window");
          // A later outcome after re-enqueue must still be accepted, never replayed from a snapshot.
          var path = paths.getFirst();
          queue.reenqueue(new JobQueue.EnqueueEntry(path, 1));
          queue.markFailed(path, IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "FAIL", IngestionRetryPolicy.NONE, "later failure"));
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
          while (ledger.store().recent().size() < 401 && System.nanoTime() < deadline) Thread.sleep(1);
          assertEquals(401, ledger.store().recent().size());
          assertEquals(401, ledger.currentSeq(), "reconnect never republishes old outcomes");
          assertEquals(401, queue.ingestionOutcomeSummary(0).stream().mapToLong(JobQueue.IngestionOutcomeSummary::count).sum());
          assertEquals(1, ledger.store().recent().stream().filter(e -> ((ActionEvent.Index) e).state().equals("FAILED")).count());
        } finally {
          release.countDown(); blocker.close(); wired.subscription().close(); wired.bridge().stop(); jobs.channel().close(); ledger.channel().close();
        }
      }
    }
  }

  private static SseStreamChannel channel(TestEngineExecutors owners, io.justsearch.app.api.stream.StreamId id, String name) {
    var owner = owners.register(EngineExecutorSpec.virtual(name, EngineExecutorSpec.Kind.BACKGROUND, 1));
    return new SseStreamChannel(id, owner.openVirtual(), owner::close);
  }
}

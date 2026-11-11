/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.observability.ledger.ActionEventJournal;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.ledger.ActionLedgerProjection;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.IndexingJobsSource;
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.IndexingJobView;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A committed outcome is being accepted by the head's outcome bridge when the Engine client
 * retires for a recovery. The outcome is delivered synchronously on the worker's thread, so the
 * retirement's completion must wait for that delivery: otherwise the bridge drops the outcome
 * half-way and the replacement subscription never replays it.
 */
@Timeout(30)
final class IndexingOutcomeRetirementDeliveryTest {
  private static final String PATH = "c".repeat(64);

  @Test
  void retirementDuringOutcomeDeliveryCompletesAfterTheOutcomeReachesTheLedger() throws Exception {
    var admission = new EngineAdmissionController(16, 16, 1);
    var sink = new AtomicReference<Consumer<IndexingJobsFrame>>();
    var services = mock(WorkerAppServices.class);
    var ingest = mock(WorkerIngestService.class);
    doAnswer(call -> {
      Consumer<IndexingJobsFrame> outcomes = call.getArgument(0);
      outcomes.accept(IndexingJobsFrame.newBuilder().setSeq(1)
          .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build());
      sink.set(outcomes);
      return null;
    }).when(ingest).subscribeIndexingJobOutcomes(any(), any());
    when(services.ingestService()).thenReturn(ingest);
    var order = new CopyOnWriteArrayList<String>();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.disabled(),
        new io.justsearch.app.observability.stream.SseStreamChannel(ActionLedgerChangeRegistry.STREAM_ID));
    EngineContext context = EngineProvenance.internal("indexing-job-outcomes",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
    try (var owners = new TestEngineExecutors();
        var registry = registry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
            () -> {}, admission, WatchedRootsState.inMemory(), () -> lease(services))) {
      IndexingJobsSource source = (onFrame, onError, onCompleted) ->
          client.subscribeIndexingJobOutcomes(onFrame, onError, () -> {
            order.add("completed");
            onCompleted.run();
          }, context);
      var outcomes = new RemoteIndexingJobsBridge(owners, () -> source, "test.outcomes");
      // The first listener holds delivery after the bridge accepted the outcome's state.
      var hold = outcomes.subscribe(delta -> {
        if (delta instanceof RemoteIndexingJobsBridge.Delta.Update) {
          order.add("accepting");
          entered.countDown();
          awaitRelease(release);
        }
      });
      // The second is the activity ledger, as IndexingJobsBridgeWiring subscribes it.
      var recorded = outcomes.subscribe(delta -> {
        if (delta instanceof RemoteIndexingJobsBridge.Delta.Update update) {
          order.add("recorded");
          ledger.broadcastActionEvent(ActionLedgerProjection.projectIndex(update.row().pathHash(),
              update.row().collection(), update.row().state(), update.row().attempts(),
              update.row().errorMessage(), Instant.ofEpochMilli(update.row().lastUpdatedMs()),
              update.row().scanId()));
        }
      });
      try {
        outcomes.start().get(5, TimeUnit.SECONDS);
        assertNotNull(sink.get(), "the outcome bridge subscribed");
        var worker = Thread.ofPlatform().start(() -> sink.get().accept(done()));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the outcome reached the bridge");

        client.close(); // Recovery retires the client while the outcome is being accepted.
        order.add("retired");
        release.countDown();
        worker.join(5_000);

        assertEquals(List.of("accepting", "retired", "recorded", "completed"), order,
            "completion follows the outcome's delivery");
        assertEquals(1, ledger.store().recent().size(), "the terminal outcome is recorded once");
        assertEquals(0, admission.activeWorkCount(), "the retired subscription released its work");
      } finally {
        release.countDown();
        hold.close();
        recorded.close();
        outcomes.stop();
      }
    }
  }

  private static IndexingJobsFrame done() {
    return IndexingJobsFrame.newBuilder().setSeq(2).setDelta(IndexingJobsDelta.newBuilder()
        .setUpdate(IndexingJobView.newBuilder().setPathHash(PATH).setState("DONE")
            .setLastUpdatedMs(2).setCollection("default"))).build();
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      assertTrue(release.await(10, TimeUnit.SECONDS), "held delivery was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static KnowledgeServer.ServingLease lease(WorkerAppServices services) {
    var lease = mock(KnowledgeServer.ServingLease.class);
    when(lease.services()).thenReturn(services);
    when(lease.fork()).thenAnswer(ignored -> lease(services));
    when(lease.onRetirement(any())).thenReturn(() -> {});
    return lease;
  }

  private static DefaultEngineExecutorRegistry registry() {
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(Map.of(
            "perContextLimit", 16, "aggregateLimit", 16, "retryAfterSeconds", 1,
            "foregroundThreads", 2, "foregroundQueue", 16,
            "backgroundThreads", 4, "backgroundQueue", 16,
            "timerRegistrations", 32, "directMemoryMiB", 1), new RetainedStateBudget()),
        Duration.ofMillis(100));
  }
}

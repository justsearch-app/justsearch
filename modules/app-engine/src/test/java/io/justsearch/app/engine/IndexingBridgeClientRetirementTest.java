/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.ledger.ActionEventJournal;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.services.bootstrap.phases.IndexingJobsBridgeWiring;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorSpec;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An index recovery retires the Engine's knowledge client and publishes a replacement. The head's
 * indexing bridges must then follow the replacement, so a window whose stream was connected before
 * the recovery still receives row changes and terminal activity produced after it.
 *
 * <p>Real {@link EngineKnowledgeClient}s (subscription ownership, serving views, admission and
 * retirement), the real bridge wiring and the real change registries the window's streams read;
 * the worker's ingest service is a mock that records the sinks it is given.
 */
@Timeout(30)
final class IndexingBridgeClientRetirementTest {
  private static final String ROW = "a".repeat(64);
  private static final String OUTCOME = "b".repeat(64);

  @Test
  void connectedWindowKeepsReceivingRowsAndOutcomesFromTheReplacementClient() throws Exception {
    var admission = new EngineAdmissionController(16, 16, 1);
    var old = new Worker();
    var replacement = new Worker();
    var current = new AtomicReference<KnowledgeClient>();
    var rows = new CopyOnWriteArrayList<SseEnvelope>();
    var activity = new CopyOnWriteArrayList<SseEnvelope>();
    try (var owners = new TestEngineExecutors();
        var oldRegistry = registry();
        var newRegistry = registry();
        var oldClient = old.client(oldRegistry, admission);
        var newClient = replacement.client(newRegistry, admission)) {
      var jobs = new IndexingJobsChangeRegistry(new SseStreamChannel(IndexingJobsChangeRegistry.STREAM_ID,
          owners.register(EngineExecutorSpec.virtual("test.jobs", EngineExecutorSpec.Kind.BACKGROUND, 1))
              .openVirtual()));
      var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.disabled(),
          new SseStreamChannel(ActionLedgerChangeRegistry.STREAM_ID,
              owners.register(EngineExecutorSpec.virtual("test.ledger", EngineExecutorSpec.Kind.BACKGROUND, 1))
                  .openVirtual()));
      current.set(oldClient);
      var wired = IndexingJobsBridgeWiring.wire(owners, current::get, jobs, ledger);
      try {
        wired.bridge().start().get(5, TimeUnit.SECONDS);
        assertNotNull(old.rows.get(), "the bridge subscribed to the first client's rows");
        assertNotNull(old.outcomes.get(), "and to its committed outcomes");
        // The window connected before the recovery: its streams read these channels.
        var rowWindow = jobs.channel().subscribe(rows::add);
        var activityWindow = ledger.channel().subscribe(activity::add);
        try {
          await(() -> admission.activeWorkCount() == 2, "two upstream subscriptions own work");
          assertEquals(2, old.leases.get(), "each subscription holds its serving view");

          // Recovery: the old client retires before the index owner closes (bootstrap close order).
          oldClient.close();
          await(() -> admission.activeWorkCount() == 0, "retired subscriptions released their work");
          assertEquals(0, admission.indexRetainingWorkCount());
          await(() -> old.leases.get() == 0, "retired subscriptions released their serving views");
          current.set(newClient);
          // Publication activates the bridge again, as HeadAssembly.activateKnowledgeServerBinding does.
          wired.bridge().start().get(5, TimeUnit.SECONDS);
          assertNotNull(replacement.rows.get(), "the bridge subscribed to the replacement's rows");
          assertNotNull(replacement.outcomes.get(), "and to its committed outcomes");
          await(() -> admission.activeWorkCount() == 2, "the replacement subscriptions own work");

          replacement.rows.get().accept(update(2, ROW, "PROCESSING"));
          replacement.outcomes.get().accept(update(2, OUTCOME, "DONE"));
          await(() -> rows.stream().anyMatch(frame -> String.valueOf(frame.payload()).contains(ROW)),
              "the connected window receives a row changed after the recovery");
          await(() -> activity.stream().anyMatch(frame -> String.valueOf(frame.payload()).contains(OUTCOME)),
              "the connected window receives a terminal outcome produced after the recovery");
        } finally {
          rowWindow.unsubscribe();
          activityWindow.unsubscribe();
        }
      } finally {
        wired.subscription().close();
        wired.bridge().stop();
      }
    }
  }

  private static IndexingJobsFrame update(long seq, String pathHash, String state) {
    return IndexingJobsFrame.newBuilder().setSeq(seq).setDelta(IndexingJobsDelta.newBuilder()
        .setUpdate(IndexingJobView.newBuilder().setPathHash(pathHash).setState(state)
            .setLastUpdatedMs(seq).setCollection("default"))).build();
  }

  private static IndexingJobsFrame snapshot() {
    return IndexingJobsFrame.newBuilder().setSeq(1)
        .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build();
  }

  /** One composed index half: its ingest sinks and its live serving views. */
  private static final class Worker {
    final AtomicReference<Consumer<IndexingJobsFrame>> rows = new AtomicReference<>();
    final AtomicReference<Consumer<IndexingJobsFrame>> outcomes = new AtomicReference<>();
    final AtomicInteger leases = new AtomicInteger();
    final WorkerAppServices services = mock(WorkerAppServices.class);

    Worker() {
      var ingest = mock(WorkerIngestService.class);
      doAnswer(call -> {
        Consumer<IndexingJobsFrame> sink = call.getArgument(1);
        sink.accept(snapshot());
        rows.set(sink);
        return null;
      }).when(ingest).subscribeIndexingJobs(any(), any(), any());
      doAnswer(call -> {
        Consumer<IndexingJobsFrame> sink = call.getArgument(0);
        sink.accept(snapshot());
        outcomes.set(sink);
        return null;
      }).when(ingest).subscribeIndexingJobOutcomes(any(), any());
      when(services.ingestService()).thenReturn(ingest);
    }

    EngineKnowledgeClient client(DefaultEngineExecutorRegistry registry,
        EngineAdmissionController admission) {
      return new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
          () -> {}, admission, WatchedRootsState.inMemory(), this::lease);
    }

    private KnowledgeServer.ServingLease lease() {
      var lease = mock(KnowledgeServer.ServingLease.class);
      leases.incrementAndGet();
      var closed = new java.util.concurrent.atomic.AtomicBoolean();
      when(lease.services()).thenReturn(services);
      when(lease.fork()).thenAnswer(ignored -> lease());
      when(lease.onRetirement(any())).thenReturn(() -> {});
      doAnswer(ignored -> {
        if (closed.compareAndSet(false, true)) leases.decrementAndGet();
        return null;
      }).when(lease).close();
      return lease;
    }
  }

  private static void await(BooleanSupplier condition, String message) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), message);
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

package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.CancelIndexingJobHandler;
import io.justsearch.app.services.registry.operations.handlers.ResolvePathHashHandler;
import io.justsearch.app.services.registry.operations.handlers.RetryIndexingJobHandler;
import io.justsearch.app.services.worker.IndexingJobsSource;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 445 integration test — exercises the full substrate chain end-to-end
 * with no live backend:
 *
 * <pre>
 * StubIndexingJobsSource          (stand-in for the index half's frame producer)
 *      ↓ frames
 * RemoteIndexingJobsBridge        (head-side translator)
 *      ↓ Delta events
 * IndexingJobsChangeRegistry      (per-Resource fan-out via SseStreamChannel)
 *      ↓ envelopes
 * IndexingJobsStreamController    (SSE endpoint; writes via SseEnvelopeWriter)
 *      ↓ "frame" events
 * mock SseClient                  (captures envelope JSON for assertions)
 * </pre>
 *
 * <p>Closes verification gap #2 from the slice 445 §B.A.H discussion: previously
 * verified only via manual {@code runHeadlessEval} smoke. This test makes the
 * chain regression-safe under CI.
 *
 * <p>The Operations leg is covered by separately dispatching
 * {@code core.cancel-indexing-job}, {@code core.retry-indexing-job},
 * {@code core.resolve-path-hash} through a real {@link OperationDispatcher}
 * with a stub {@link IndexingService}, mirroring the production
 * {@code OperationsController} dispatch path.
 *
 * <p><b>Why the top of the chain is no longer an in-process gRPC server.</b> Lane F stage A item
 * A14 deleted the {@code IngestService} service block from
 * {@code modules/ipc-common/src/main/proto/indexing.proto}, so {@code IngestServiceGrpc} is not
 * generated any more and that server cannot be stood up. Nothing below the top of the chain
 * changes: {@link RemoteIndexingJobsBridge} takes a {@code Supplier<IndexingJobsSource>}
 * (RemoteIndexingJobsBridge.java:107) and asks it for frames (IndexingJobsSource.java:26), so the
 * queued frames — still real {@code io.justsearch.ipc} protos, whose messages survive at
 * {@code indexing.proto:1425/1412/1417} — are handed to {@code onFrame} directly instead of being
 * round-tripped through a channel. The registry, controller and SSE legs are untouched.
 */
@DisplayName("Slice 445 substrate integration")
final class IndexingJobsSubstrateIntegrationTest {

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path operationDirectory;
  private io.justsearch.app.observability.operations.SqliteOperationStore operationStore;
  private io.justsearch.app.api.operations.OperationAttemptRunner attempts;

  @BeforeEach
  void openOperationRunner() throws Exception {
    operationStore = new io.justsearch.app.observability.operations.SqliteOperationStore(
        operationDirectory.resolve("operations.db"));
    attempts = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(
        operationStore, Clock.systemUTC(), java.util.Set.of());
  }

  @AfterEach
  void closeOperationRunner() throws Exception {
    if (operationStore != null) operationStore.close();
  }


  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private StubIndexingJobsSource stubService;
  private RemoteIndexingJobsBridge bridge;
  private IndexingJobsChangeRegistry changeRegistry;
  private IndexingJobsStreamController controller;
  private Telemetry telemetry;

  @BeforeEach
  void setUp() throws Exception {
    stubService = new StubIndexingJobsSource();
    // Lane F item A6 moved the bridge onto an IndexingJobsSource; item A14 deleted the gRPC
    // service that used to be one. The fake IS the source, so the substrate below it is exercised
    // end to end exactly as before.
    bridge = new RemoteIndexingJobsBridge(processExecutors, () -> stubService);
    changeRegistry = new IndexingJobsChangeRegistry();

    // Wire bridge → registry forwarding (mirrors HeadAssembly line-for-line).
    bridge.subscribe(
        delta -> {
          switch (delta) {
            case RemoteIndexingJobsBridge.Delta.SnapshotReplaced sr ->
                changeRegistry.broadcast(
                    new IndexingJobsChangeRegistry.Delta.SnapshotReplaced(sr.items()));
            case RemoteIndexingJobsBridge.Delta.Insert ins ->
                changeRegistry.broadcast(new IndexingJobsChangeRegistry.Delta.Insert(ins.row()));
            case RemoteIndexingJobsBridge.Delta.Update upd ->
                changeRegistry.broadcast(new IndexingJobsChangeRegistry.Delta.Update(upd.row()));
            case RemoteIndexingJobsBridge.Delta.Delete del ->
                changeRegistry.broadcast(
                    new IndexingJobsChangeRegistry.Delta.Delete(del.pathHash()));
          }
        });

    telemetry = mock(Telemetry.class);
    controller =
        new IndexingJobsStreamController(
            processExecutors,
              changeRegistry, bridge, telemetry, Clock.systemUTC());
  }

  @AfterEach
  void tearDown() {
    if (controller != null) controller.shutdown();
    if (bridge != null) bridge.stop();
    processExecutors.close();
  }

  @Test
  @DisplayName("end-to-end: stub frames → bridge → registry → SSE controller → SseClient")
  void fullChainDeliversFrames() throws Exception {
    stubService.queueSnapshot(
        7L,
        List.of(
            stubView("hash-a", "PENDING", 0, 1000L),
            stubView("hash-b", "PROCESSING", 1, 2000L)));
    bridge.start().get(2, TimeUnit.SECONDS);

    SseClient client = mockSseClient();
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    controller.handle(client);

    // Initial connect + snapshot lifecycle frames.
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "expected connected lifecycle: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "expected snapshot lifecycle carrying items: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"streamId\":\"surface:indexing-jobs\"")),
        "snapshot frame carries the stable streamId: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"pathHash\":\"hash-a\"")),
        "snapshot extras include items from the bridge cache: " + sent);

    // Drive a delta on the gRPC side; expect an UPDATE frame at the SSE level.
    int beforeDelta = sent.size();
    stubService.queueDelta(
        8L,
        IndexingJobsDelta.newBuilder()
            .setInsert(stubView("hash-c", "PENDING", 0, 3000L))
            .build());
    awaitFrame(sent, beforeDelta);

    String updateFrame =
        sent.stream()
            .skip(beforeDelta)
            .filter(s -> s.contains("\"frameKind\":\"UPDATE\""))
            .findFirst()
            .orElse(null);
    assertNotNull(updateFrame, "expected UPDATE frame after delta: " + sent);
    assertTrue(updateFrame.contains("\"pathHash\":\"hash-c\""), "frame carries the new row");
    // Slice 3a.1.9 §B.B.B B1: DeltaEnvelope with kind="insert" wrapping
    // the row payload. Both the kind discriminator AND the row's
    // pathHash field appear on the wire.
    assertTrue(
        updateFrame.contains("\"kind\":\"insert\""),
        "Insert envelope kind discriminator");
    assertTrue(
        updateFrame.contains("\"row\":"),
        "envelope payload includes the row field");
  }

  @Test
  @DisplayName("delete delta: SSE forwards the path-hash-only Delete envelope")
  void deleteDeltaForwarded() throws Exception {
    stubService.queueSnapshot(
        1L, List.of(stubView("hash-z", "PROCESSING", 0, 100L)));
    bridge.start().get(2, TimeUnit.SECONDS);

    SseClient client = mockSseClient();
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));
    controller.handle(client);
    int before = sent.size();

    stubService.queueDelta(
        2L, IndexingJobsDelta.newBuilder().setDeletePathHash("hash-z").build());
    awaitFrame(sent, before);

    String deleteFrame =
        sent.stream()
            .skip(before)
            .filter(s -> s.contains("\"frameKind\":\"UPDATE\"") && s.contains("hash-z"))
            .findFirst()
            .orElse(null);
    assertNotNull(deleteFrame, "expected UPDATE frame for Delete delta: " + sent);
    // Slice 3a.1.9 §B.B.B B1: Delete deltas now wrap in DeltaEnvelope
    // with kind="delete" and primaryKeyValue=<the pk value>. The
    // generic primaryKeyValue field name decouples the wire shape from
    // any one Resource's primary-key field name.
    assertTrue(
        deleteFrame.contains("\"kind\":\"delete\""),
        "Delete envelope kind discriminator");
    assertTrue(
        deleteFrame.contains("\"primaryKeyValue\":\"hash-z\""),
        "Delete envelope carries the primary-key value via the generic primaryKeyValue field");
  }

  @Test
  @DisplayName("operations dispatch: cancel/retry/resolve through CoreOperationCatalog handlers")
  void operationsDispatchEndToEnd() {
    // Stand up the same dispatcher shape HeadAssembly uses: real
    // CoreOperationCatalog + HandlerRegistry with the three slice 445
    // handlers wired against a stub IndexingService.
    var captured = new java.util.HashMap<String, String>();
    IndexingService stub =
        new IndexingService() {
          @Override
          public Map<String, Object> resolvePathHash(String pathHash, EngineContext engineContext) {
            captured.put("resolve", pathHash);
            return Map.of("found", true, "path", "/abs/p.txt", "lastSeenAtMs", 42L);
          }

          @Override
          public Map<String, Object> cancelIndexingJob(String pathHash, EngineContext engineContext) {
            captured.put("cancel", pathHash);
            return Map.of("cancelled", true, "previousState", "PROCESSING");
          }

          @Override
          public Map<String, Object> retryIndexingJob(String pathHash, EngineContext engineContext) {
            captured.put("retry", pathHash);
            return Map.of("retried", true, "previousState", "FAILED");
          }

          @Override
          public List<java.nio.file.Path> getWatchedPaths(EngineContext engineContext) {
            return List.of();
          }

          @Override
          public void addWatchedPath(java.nio.file.Path path, EngineContext engineContext) {}

          @Override
          public int removeWatchedPath(java.nio.file.Path path, EngineContext engineContext) {
            return 0;
          }

          @Override
          public void flush(EngineContext engineContext) {}
        };

    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(CoreOperationCatalog.CANCEL_INDEXING_JOB, new CancelIndexingJobHandler(() -> stub));
    handlers.register(CoreOperationCatalog.RETRY_INDEXING_JOB, new RetryIndexingJobHandler(() -> stub));
    handlers.register(CoreOperationCatalog.RESOLVE_PATH_HASH, new ResolvePathHashHandler(() -> stub));

    OperationDispatcher dispatcher =
        new OperationExecutorImpl(attempts, handlers, entry -> {}, Clock.systemUTC());

    var catalog = new CoreOperationCatalog();

    // resolve
    var resolveOp = catalog.findById(CoreOperationCatalog.RESOLVE_PATH_HASH).orElseThrow();
    OperationResult resolveResult =
        dispatcher.dispatch(resolveOp, "{\"pathHash\":\"hash-1\"}", TestRequestContexts.browser());
    assertTrue(resolveResult.success(), () -> "resolve: " + resolveResult.message());
    assertEquals("hash-1", captured.get("resolve"));
    assertEquals("/abs/p.txt", resolveResult.structuredData().get("path"));

    // cancel
    var cancelOp = catalog.findById(CoreOperationCatalog.CANCEL_INDEXING_JOB).orElseThrow();
    OperationResult cancelResult =
        dispatcher.dispatch(cancelOp, "{\"pathHash\":\"hash-2\"}", TestRequestContexts.browser());
    assertTrue(cancelResult.success(), () -> "cancel: " + cancelResult.message());
    assertEquals("hash-2", captured.get("cancel"));

    // retry
    var retryOp = catalog.findById(CoreOperationCatalog.RETRY_INDEXING_JOB).orElseThrow();
    OperationResult retryResult =
        dispatcher.dispatch(retryOp, "{\"pathHash\":\"hash-3\"}", TestRequestContexts.browser());
    assertTrue(retryResult.success(), () -> "retry: " + retryResult.message());
    assertEquals("hash-3", captured.get("retry"));
  }

  // ---- helpers ----

  private static SseClient mockSseClient() {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(null);
    return client;
  }

  private static io.justsearch.ipc.IndexingJobView stubView(
      String pathHash, String state, int attempts, long lastUpdatedMs) {
    return io.justsearch.ipc.IndexingJobView.newBuilder()
        .setPathHash(pathHash)
        .setState(state)
        .setAttempts(attempts)
        .setLastUpdatedMs(lastUpdatedMs)
        .setErrorMessage("")
        .setRetryAfterMs(0L)
        .setCollection("default")
        .build();
  }

  private static void awaitFrame(List<String> sent, int beforeIdx) throws InterruptedException {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (sent.size() <= beforeIdx && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(sent.size() > beforeIdx, "no frame arrived in 2s");
  }

  /**
   * Stub frame producer: buffers frames before subscribe, flushes on subscribe. Implements
   * {@link IndexingJobsSource} directly — the seam {@link RemoteIndexingJobsBridge} actually
   * consumes (RemoteIndexingJobsBridge.java:107) — because item A14 removed the gRPC service this
   * used to extend.
   *
   * <p><b>Delivery is deliberately synchronous on the subscribing/queuing thread</b>, replacing
   * the {@code directExecutor()} the in-process gRPC server and channel were built with. The
   * assertions after {@code bridge.start().get(2, SECONDS)} read the bridge's cached snapshot
   * immediately, so the buffered frames must have landed before {@code subscribe} returns.
   */
  private static final class StubIndexingJobsSource implements IndexingJobsSource {

    private final List<IndexingJobsFrame> pre = new ArrayList<>();
    private final AtomicReference<Consumer<IndexingJobsFrame>> active = new AtomicReference<>();
    /**
     * Per-subscription "producer stopped" flag, set by the returned handle's {@code close()},
     * which {@link RemoteIndexingJobsBridge#stop()} invokes. Frames queued after a stop are
     * dropped here, exactly as the gRPC StreamObserver adapter dropped them.
     */
    private final AtomicReference<AtomicBoolean> stopped = new AtomicReference<>();

    @Override
    public KnowledgeClient.IndexingJobsStream subscribe(
        Consumer<IndexingJobsFrame> onFrame, Consumer<Throwable> onError, Runnable onCompleted) {
      synchronized (this) {
        AtomicBoolean flag = new AtomicBoolean(false);
        stopped.set(flag);
        active.set(onFrame);
        for (var f : pre) {
          deliver(f);
        }
        pre.clear();
        return () -> flag.set(true);
      }
    }

    void queueSnapshot(long seq, List<io.justsearch.ipc.IndexingJobView> items) {
      synchronized (this) {
        var snap = IndexingJobsSnapshot.newBuilder().addAllItems(items).build();
        var frame =
            IndexingJobsFrame.newBuilder().setSnapshot(snap).setSeq(seq).build();
        if (active.get() != null) {
          deliver(frame);
        } else {
          pre.add(frame);
        }
      }
    }

    void queueDelta(long seq, IndexingJobsDelta delta) {
      synchronized (this) {
        var frame = IndexingJobsFrame.newBuilder().setDelta(delta).setSeq(seq).build();
        if (active.get() != null) {
          deliver(frame);
        } else {
          pre.add(frame);
        }
      }
    }

    private void deliver(IndexingJobsFrame frame) {
      AtomicBoolean flag = stopped.get();
      if (flag == null || flag.get()) {
        return;
      }
      active.get().accept(frame);
    }
  }
}

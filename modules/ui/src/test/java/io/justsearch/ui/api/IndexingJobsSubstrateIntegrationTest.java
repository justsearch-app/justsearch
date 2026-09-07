package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
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
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 445 integration test — exercises the full substrate chain end-to-end
 * with no live backend:
 *
 * <pre>
 * in-process gRPC server          (stand-in for worker's WorkerIngestService)
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
 */
@DisplayName("Slice 445 substrate integration")
final class IndexingJobsSubstrateIntegrationTest {

  private Server grpcServer;
  private ManagedChannel grpcChannel;
  private StubIndexingJobsService stubService;
  private RemoteIndexingJobsBridge bridge;
  private IndexingJobsChangeRegistry changeRegistry;
  private IndexingJobsStreamController controller;
  private Telemetry telemetry;

  @BeforeEach
  void setUp() throws Exception {
    String name = InProcessServerBuilder.generateName();
    stubService = new StubIndexingJobsService();
    grpcServer =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(stubService)
            .build()
            .start();
    grpcChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
    var asyncStub = IngestServiceGrpc.newStub(grpcChannel);
    bridge = new RemoteIndexingJobsBridge(() -> asyncStub);
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
        new IndexingJobsStreamController(changeRegistry, bridge, telemetry, Clock.systemUTC());
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (controller != null) controller.shutdown();
    if (bridge != null) bridge.stop();
    if (grpcChannel != null) {
      grpcChannel.shutdownNow();
      grpcChannel.awaitTermination(2, TimeUnit.SECONDS);
    }
    if (grpcServer != null) {
      grpcServer.shutdownNow();
      grpcServer.awaitTermination(2, TimeUnit.SECONDS);
    }
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
          public Map<String, Object> resolvePathHash(String pathHash) {
            captured.put("resolve", pathHash);
            return Map.of("found", true, "path", "/abs/p.txt", "lastSeenAtMs", 42L);
          }

          @Override
          public Map<String, Object> cancelIndexingJob(String pathHash) {
            captured.put("cancel", pathHash);
            return Map.of("cancelled", true, "previousState", "PROCESSING");
          }

          @Override
          public Map<String, Object> retryIndexingJob(String pathHash) {
            captured.put("retry", pathHash);
            return Map.of("retried", true, "previousState", "FAILED");
          }

          @Override
          public List<java.nio.file.Path> getWatchedPaths() {
            return List.of();
          }

          @Override
          public void addWatchedPath(java.nio.file.Path path) {}

          @Override
          public int removeWatchedPath(java.nio.file.Path path) {
            return 0;
          }

          @Override
          public void flush() {}
        };

    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(CoreOperationCatalog.CANCEL_INDEXING_JOB, new CancelIndexingJobHandler(() -> stub));
    handlers.register(CoreOperationCatalog.RETRY_INDEXING_JOB, new RetryIndexingJobHandler(() -> stub));
    handlers.register(CoreOperationCatalog.RESOLVE_PATH_HASH, new ResolvePathHashHandler(() -> stub));

    OperationDispatcher dispatcher =
        new OperationExecutorImpl(handlers, entry -> {}, Clock.systemUTC());

    var catalog = new CoreOperationCatalog();

    // resolve
    var resolveOp = catalog.findById(CoreOperationCatalog.RESOLVE_PATH_HASH).orElseThrow();
    OperationResult resolveResult =
        dispatcher.dispatch(resolveOp, "{\"pathHash\":\"hash-1\"}");
    assertTrue(resolveResult.success(), () -> "resolve: " + resolveResult.message());
    assertEquals("hash-1", captured.get("resolve"));
    assertEquals("/abs/p.txt", resolveResult.structuredData().get("path"));

    // cancel
    var cancelOp = catalog.findById(CoreOperationCatalog.CANCEL_INDEXING_JOB).orElseThrow();
    OperationResult cancelResult =
        dispatcher.dispatch(cancelOp, "{\"pathHash\":\"hash-2\"}");
    assertTrue(cancelResult.success(), () -> "cancel: " + cancelResult.message());
    assertEquals("hash-2", captured.get("cancel"));

    // retry
    var retryOp = catalog.findById(CoreOperationCatalog.RETRY_INDEXING_JOB).orElseThrow();
    OperationResult retryResult =
        dispatcher.dispatch(retryOp, "{\"pathHash\":\"hash-3\"}");
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

  /** Stub gRPC service: buffers frames before subscribe, flushes on subscribe. */
  private static final class StubIndexingJobsService
      extends IngestServiceGrpc.IngestServiceImplBase {

    private final List<IndexingJobsFrame> pre = new ArrayList<>();
    private final AtomicReference<StreamObserver<IndexingJobsFrame>> active =
        new AtomicReference<>();

    @Override
    public void subscribeIndexingJobs(
        SubscribeIndexingJobsRequest request, StreamObserver<IndexingJobsFrame> obs) {
      synchronized (this) {
        active.set(obs);
        for (var f : pre) {
          obs.onNext(f);
        }
        pre.clear();
      }
    }

    void queueSnapshot(long seq, List<io.justsearch.ipc.IndexingJobView> items) {
      synchronized (this) {
        var snap = IndexingJobsSnapshot.newBuilder().addAllItems(items).build();
        var frame =
            IndexingJobsFrame.newBuilder().setSnapshot(snap).setSeq(seq).build();
        var obs = active.get();
        if (obs != null) {
          obs.onNext(frame);
        } else {
          pre.add(frame);
        }
      }
    }

    void queueDelta(long seq, IndexingJobsDelta delta) {
      synchronized (this) {
        var frame = IndexingJobsFrame.newBuilder().setDelta(delta).setSeq(seq).build();
        var obs = active.get();
        if (obs != null) {
          obs.onNext(frame);
        } else {
          pre.add(frame);
        }
      }
    }
  }
}

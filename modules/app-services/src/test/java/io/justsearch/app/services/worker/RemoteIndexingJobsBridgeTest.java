package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 445: end-to-end head-side bridge ↔ in-process gRPC stub. Confirms the
 * frame translation logic and listener fan-out without needing a live worker.
 */
@DisplayName("RemoteIndexingJobsBridge")
final class RemoteIndexingJobsBridgeTest {

  private Server server;
  private ManagedChannel channel;
  private StubIndexingJobsService stub;
  private RemoteIndexingJobsBridge bridge;

  @BeforeEach
  void setUp() throws Exception {
    String name = InProcessServerBuilder.generateName();
    stub = new StubIndexingJobsService();
    server =
        InProcessServerBuilder.forName(name).directExecutor().addService(stub).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    var stubInstance = IngestServiceGrpc.newStub(channel);
    // Lane F item A6: the bridge asks an IndexingJobsSource for frames instead of holding the
    // async gRPC stub itself. This lambda IS the wire source, so the test still exercises real
    // proto frames over a real (in-process) gRPC server — only the seam moved.
    bridge =
        new RemoteIndexingJobsBridge(
            () ->
                (onFrame, onError, onCompleted) -> {
                  java.util.concurrent.atomic.AtomicBoolean stopped =
                      new java.util.concurrent.atomic.AtomicBoolean(false);
                  stubInstance.subscribeIndexingJobs(
                      SubscribeIndexingJobsRequest.newBuilder().build(),
                      new StreamObserver<IndexingJobsFrame>() {
                        @Override
                        public void onNext(IndexingJobsFrame frame) {
                          if (!stopped.get()) {
                            onFrame.accept(frame);
                          }
                        }

                        @Override
                        public void onError(Throwable t) {
                          if (!stopped.get()) {
                            onError.accept(t);
                          }
                        }

                        @Override
                        public void onCompleted() {
                          onCompleted.run();
                        }
                      });
                  return () -> stopped.set(true);
                });
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (bridge != null) bridge.stop();
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(2, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(2, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("translates snapshot frame to SnapshotReplaced delta")
  void snapshotFrameDelivered() throws Exception {
    stub.queueSnapshot(
        7L,
        List.of(
            view("hash-a", "PENDING", 0, 1000L, ""),
            view("hash-b", "PROCESSING", 1, 2000L, "")));

    var deliveries = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(deliveries::add);
    bridge.start().get(2, TimeUnit.SECONDS);

    assertEquals(1, deliveries.size());
    var delta = deliveries.get(0);
    assertInstanceOf(RemoteIndexingJobsBridge.Delta.SnapshotReplaced.class, delta);
    var snap = (RemoteIndexingJobsBridge.Delta.SnapshotReplaced) delta;
    assertEquals(7L, snap.snapshotSeq());
    assertEquals(2, snap.items().size());
    assertEquals("hash-a", snap.items().get(0).pathHash());
    assertEquals(7L, bridge.latestSnapshotSeq());
    assertEquals(2, bridge.latestSnapshot().size());
  }

  @Test
  @DisplayName("translates delta frames into typed Insert / Update / Delete")
  void deltaFramesTranslated() throws Exception {
    stub.queueSnapshot(0L, List.of());
    stub.queueDelta(
        1L,
        IndexingJobsDelta.newBuilder()
            .setInsert(view("hash-x", "PENDING", 0, 1L, ""))
            .build());
    stub.queueDelta(
        2L,
        IndexingJobsDelta.newBuilder()
            .setUpdate(view("hash-x", "PROCESSING", 0, 2L, ""))
            .build());
    stub.queueDelta(3L, IndexingJobsDelta.newBuilder().setDeletePathHash("hash-x").build());

    var deliveries = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(deliveries::add);
    bridge.start().get(2, TimeUnit.SECONDS);
    awaitDeliveries(deliveries, 4);

    assertInstanceOf(RemoteIndexingJobsBridge.Delta.SnapshotReplaced.class, deliveries.get(0));
    assertInstanceOf(RemoteIndexingJobsBridge.Delta.Insert.class, deliveries.get(1));
    assertInstanceOf(RemoteIndexingJobsBridge.Delta.Update.class, deliveries.get(2));
    assertInstanceOf(RemoteIndexingJobsBridge.Delta.Delete.class, deliveries.get(3));

    var ins = (RemoteIndexingJobsBridge.Delta.Insert) deliveries.get(1);
    assertEquals(1L, ins.seq());
    assertEquals("hash-x", ins.row().pathHash());

    var del = (RemoteIndexingJobsBridge.Delta.Delete) deliveries.get(3);
    assertEquals("hash-x", del.pathHash());
  }

  @Test
  @DisplayName("post-stop frames are dropped — no listener fan-out, no state mutation")
  void stopGatesSubsequentFrames() throws Exception {
    stub.queueSnapshot(1L, List.of(view("hash-pre", "PENDING", 0, 1L, "")));

    var deliveries = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(deliveries::add);
    bridge.start().get(2, TimeUnit.SECONDS);
    awaitDeliveries(deliveries, 1);
    long preStopSeq = bridge.latestSnapshotSeq();

    bridge.stop();

    stub.queueDelta(
        99L,
        IndexingJobsDelta.newBuilder()
            .setInsert(view("hash-post", "PENDING", 0, 99L, ""))
            .build());
    Thread.sleep(50); // give the delta a chance to arrive on the channel

    assertEquals(1, deliveries.size(), "no fan-out after stop");
    assertEquals(preStopSeq, bridge.latestSnapshotSeq(), "no state mutation after stop");
  }

  @Test
  @DisplayName("subscribers added after start receive only subsequent deltas")
  void lateSubscriberReceivesOnlyNewDeltas() throws Exception {
    stub.queueSnapshot(0L, List.of(view("hash-y", "PENDING", 0, 1L, "")));

    var early = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(early::add);
    bridge.start().get(2, TimeUnit.SECONDS);
    awaitDeliveries(early, 1);

    var late = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(late::add);

    stub.queueDelta(
        5L,
        IndexingJobsDelta.newBuilder()
            .setInsert(view("hash-z", "PENDING", 0, 5L, ""))
            .build());
    awaitDeliveries(late, 1);

    assertEquals(2, early.size(), "early subscriber sees snapshot + insert");
    assertEquals(1, late.size(), "late subscriber sees only the post-subscribe insert");
    assertInstanceOf(RemoteIndexingJobsBridge.Delta.Insert.class, late.get(0));
  }

  @Test
  @DisplayName("§B.2: deltas keep latestSnapshot live — a drained job reflects DONE, not frozen PENDING")
  void latestSnapshotReflectsDeltasNotFrozenSubscribeTime() throws Exception {
    // Subscribe-time snapshot: one PENDING job (mirrors the live §B.2 setup).
    stub.queueSnapshot(1L, List.of(view("hash-job", "PENDING", 0, 1L, "")));
    // Worker drains it: PENDING → DONE (an UPDATE delta).
    stub.queueDelta(
        2L,
        IndexingJobsDelta.newBuilder().setUpdate(view("hash-job", "DONE", 0, 2L, "")).build());

    var deliveries = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(deliveries::add);
    bridge.start().get(2, TimeUnit.SECONDS);
    awaitDeliveries(deliveries, 2); // snapshot + update

    // The cached snapshot a NEW subscriber would receive (IndexingJobsStreamController
    // serves bridge.latestSnapshot()) MUST reflect the CURRENT state (DONE), not the
    // frozen subscribe-time PENDING. Pre-fix this asserted "PENDING" (the §B.2 bug:
    // phantom pending rows the live queueDepth reported as 0).
    var snap = bridge.latestSnapshot();
    assertEquals(1, snap.size());
    assertEquals("hash-job", snap.get(0).pathHash());
    assertEquals(
        "DONE",
        snap.get(0).state(),
        "latestSnapshot must reflect the UPDATE delta (DONE), not the frozen PENDING");
    assertEquals(2L, bridge.latestSnapshotSeq(), "snapshot seq advances with applied deltas");
  }

  @Test
  @DisplayName("§B.2: a deleted job is removed from latestSnapshot")
  void deleteDeltaRemovesFromLatestSnapshot() throws Exception {
    stub.queueSnapshot(1L, List.of(view("hash-gone", "PENDING", 0, 1L, "")));
    stub.queueDelta(2L, IndexingJobsDelta.newBuilder().setDeletePathHash("hash-gone").build());

    var deliveries = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(deliveries::add);
    bridge.start().get(2, TimeUnit.SECONDS);
    awaitDeliveries(deliveries, 2);

    assertTrue(
        bridge.latestSnapshot().isEmpty(),
        "a deleted row must be removed from the cached snapshot");
  }

  @Test
  @DisplayName("§B.2 fix-pass: latestSnapshotPair() returns a coherent (seq, items) pair")
  void latestSnapshotPairIsCoherent() throws Exception {
    stub.queueSnapshot(5L, List.of(view("h", "PENDING", 0, 1L, "")));
    stub.queueDelta(
        6L, IndexingJobsDelta.newBuilder().setUpdate(view("h", "DONE", 0, 2L, "")).build());
    var deliveries = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
    bridge.subscribe(deliveries::add);
    bridge.start().get(2, TimeUnit.SECONDS);
    awaitDeliveries(deliveries, 2);

    // The (seq, items) pair moves together: after the seq-6 DONE delta the pair is (6, [DONE]) —
    // never items@5 with seq@6 or vice versa (the torn-read the single AtomicReference prevents).
    var pair = bridge.latestSnapshotPair();
    assertEquals(6L, pair.seq());
    assertEquals(1, pair.items().size());
    assertEquals("DONE", pair.items().get(0).state());
    // The convenience accessors derive from the same atomic record (no separate field to tear).
    assertEquals(pair.seq(), bridge.latestSnapshotSeq());
    assertEquals(pair.items(), bridge.latestSnapshot());
  }

  // ---- helpers ----

  private static io.justsearch.ipc.IndexingJobView view(
      String pathHash, String state, int attempts, long lastUpdatedMs, String error) {
    return io.justsearch.ipc.IndexingJobView.newBuilder()
        .setPathHash(pathHash)
        .setState(state)
        .setAttempts(attempts)
        .setLastUpdatedMs(lastUpdatedMs)
        .setErrorMessage(error)
        .setRetryAfterMs(0L)
        .setCollection("default")
        .build();
  }

  private static void awaitDeliveries(
      List<RemoteIndexingJobsBridge.Delta> deliveries, int n) throws InterruptedException {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (deliveries.size() < n && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(
        deliveries.size() >= n,
        "expected at least " + n + " deltas, got " + deliveries.size());
  }

  /**
   * In-process gRPC stub: buffers frames queued before subscribe, flushes them
   * on subscribe, and forwards subsequent frames live.
   */
  private static final class StubIndexingJobsService
      extends IngestServiceGrpc.IngestServiceImplBase {

    private final List<IndexingJobsFrame> pre = new java.util.ArrayList<>();
    private volatile StreamObserver<IndexingJobsFrame> active;

    @Override
    public synchronized void subscribeIndexingJobs(
        SubscribeIndexingJobsRequest request, StreamObserver<IndexingJobsFrame> obs) {
      active = obs;
      for (var frame : pre) {
        obs.onNext(frame);
      }
      pre.clear();
    }

    synchronized void queueSnapshot(long seq, List<io.justsearch.ipc.IndexingJobView> items) {
      var snap = IndexingJobsSnapshot.newBuilder().addAllItems(items).build();
      var frame = IndexingJobsFrame.newBuilder().setSnapshot(snap).setSeq(seq).build();
      if (active != null) {
        active.onNext(frame);
      } else {
        pre.add(frame);
      }
    }

    synchronized void queueDelta(long seq, IndexingJobsDelta delta) {
      var frame = IndexingJobsFrame.newBuilder().setDelta(delta).setSeq(seq).build();
      if (active != null) {
        active.onNext(frame);
      } else {
        pre.add(frame);
      }
    }
  }
}

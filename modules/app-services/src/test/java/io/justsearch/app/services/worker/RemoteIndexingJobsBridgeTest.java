package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 445: head-side bridge ↔ a directly-supplied {@link IndexingJobsSource}. Confirms the frame
 * translation logic and listener fan-out without needing a live worker.
 *
 * <p>The frames are still real {@code io.justsearch.ipc} protos ({@code IndexingJobsFrame} /
 * {@code IndexingJobsSnapshot} / {@code IndexingJobsDelta} survive at
 * {@code modules/ipc-common/src/main/proto/indexing.proto:1425/1412/1417}); what changed is how
 * they reach the bridge. Lane F stage A item A14 deleted the {@code IngestService} service block
 * from that proto, so {@code IngestServiceGrpc} is no longer generated and the in-process gRPC
 * server this test used to stand up cannot be built at all. It was never the subject: the bridge
 * takes a {@code Supplier<IndexingJobsSource>} ({@link RemoteIndexingJobsBridge}:107) and asks it
 * for frames ({@link IndexingJobsSource}:26), so handing the queued frames straight to
 * {@code onFrame} exercises exactly the same seam the gRPC round-trip did — with the transport
 * ceremony removed.
 */
@DisplayName("RemoteIndexingJobsBridge")
final class RemoteIndexingJobsBridgeTest {

  private StubIndexingJobsSource stub;
  private RemoteIndexingJobsBridge bridge;
  private TestEngineExecutors processExecutors;

  @BeforeEach
  void setUp() {
    stub = new StubIndexingJobsSource();
    processExecutors = new TestEngineExecutors();
    bridge = new RemoteIndexingJobsBridge(processExecutors, () -> stub);
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) bridge.stop();
    if (processExecutors != null) processExecutors.close();
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
   * Direct {@link IndexingJobsSource} fake: buffers frames queued before subscribe, flushes them
   * on subscribe, and forwards subsequent frames live — the same buffering the gRPC stub service
   * did before item A14 removed the service that made it expressible.
   *
   * <p><b>Delivery is deliberately synchronous on the subscribing/queuing thread.</b> That
   * replaces the {@code directExecutor()} the in-process gRPC server and channel were both built
   * with, and it is load-bearing: {@code bridge.start().get(2, SECONDS)} is followed immediately
   * by {@code assertEquals(1, deliveries.size())}, which only holds if the buffered snapshot
   * reaches the bridge before {@code subscribe} returns.
   */
  private static final class StubIndexingJobsSource implements IndexingJobsSource {

    private final List<IndexingJobsFrame> pre = new java.util.ArrayList<>();
    private volatile Consumer<IndexingJobsFrame> active;
    /**
     * Per-subscription "producer stopped" flag, set by the returned handle's {@code close()} —
     * which {@link RemoteIndexingJobsBridge#stop()} calls (RemoteIndexingJobsBridge.java:170..181).
     * Frames queued after a stop are still offered to the live sink and dropped here, exactly as
     * the gRPC StreamObserver adapter dropped them.
     */
    private volatile AtomicBoolean stopped;

    @Override
    public synchronized KnowledgeClient.IndexingJobsStream subscribe(
        Consumer<IndexingJobsFrame> onFrame, Consumer<Throwable> onError, Runnable onCompleted) {
      AtomicBoolean flag = new AtomicBoolean(false);
      stopped = flag;
      active = onFrame;
      for (var frame : pre) {
        deliver(frame);
      }
      pre.clear();
      return () -> flag.set(true);
    }

    synchronized void queueSnapshot(long seq, List<io.justsearch.ipc.IndexingJobView> items) {
      var snap = IndexingJobsSnapshot.newBuilder().addAllItems(items).build();
      var frame = IndexingJobsFrame.newBuilder().setSnapshot(snap).setSeq(seq).build();
      if (active != null) {
        deliver(frame);
      } else {
        pre.add(frame);
      }
    }

    synchronized void queueDelta(long seq, IndexingJobsDelta delta) {
      var frame = IndexingJobsFrame.newBuilder().setDelta(delta).setSeq(seq).build();
      if (active != null) {
        deliver(frame);
      } else {
        pre.add(frame);
      }
    }

    private void deliver(IndexingJobsFrame frame) {
      AtomicBoolean flag = stopped;
      if (flag == null || flag.get()) {
        return;
      }
      active.accept(frame);
    }
  }
}

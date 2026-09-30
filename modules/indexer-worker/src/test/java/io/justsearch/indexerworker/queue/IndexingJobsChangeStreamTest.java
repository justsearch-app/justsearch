package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Slice 445 Phase 2: live runtime confirmation that the Xerial driver's
 * {@code addUpdateListener} + {@code addCommitListener} hooks fire as expected
 * on the {@code SqliteJobQueue} connection. Mirrors the static
 * (javap-on-jar) verification done at checkpoint commit {@code 044b21ab3}.
 */
final class IndexingJobsChangeStreamTest {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;

  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void changeStreamIsAttachedAfterOpen() {
    assertNotNull(jobQueue.changeStream(), "changeStream should be attached after open()");
    assertEquals(0, jobQueue.changeStream().currentSeq(), "no deltas yet → seq=0");
  }

  @Test
  void enqueueEmitsInsertDelta() throws Exception {
    CapturingSubscriber sub = new CapturingSubscriber();
    var s = jobQueue.changeStream().subscribeWithSnapshot(sub);
    assertEquals(0, s.snapshotSeq(), "snapshot at seq=0 (empty queue)");
    assertTrue(s.items().isEmpty(), "snapshot empty");

    jobQueue.enqueue(List.of(Path.of("/tmp/a.txt")));

    sub.awaitDeliveries(1);
    assertEquals(1, sub.deltas.size());
    var delta = sub.deltas.get(0);
    assertInstanceOf(IndexingJobChangeFeed.Delta.Insert.class, delta);
    var insert = (IndexingJobChangeFeed.Delta.Insert) delta;
    assertEquals("PENDING", insert.row().state());
    assertEquals(64, insert.row().pathHash().length(), "SHA-256 hex = 64 chars");
    assertEquals(1, jobQueue.changeStream().currentSeq());

    s.subscription().close();
  }

  @Test
  void projectionFailureAfterCommitDoesNotRollbackPersistedJob() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    Path jobPath = tempDir.resolve("projection-failure.txt");
    Connection originalConnection = readField("connection", Connection.class);
    IndexingJobsChangeStream originalChangeStream = jobQueue.changeStream();
    Connection connectionSpy = spy(originalConnection);
    IndexingJobsChangeStream changeStreamSpy = spy(originalChangeStream);
    RuntimeException projectionFailure = new RuntimeException("projection failure");
    doThrow(projectionFailure).when(changeStreamSpy).commitSucceeded();
    writeField("connection", connectionSpy);
    writeField("changeStream", changeStreamSpy);

    try {
      RuntimeException thrown =
          assertThrows(RuntimeException.class, () -> jobQueue.enqueue(List.of(jobPath)));
      assertSame(projectionFailure, thrown, "the projection failure must reach the caller intact");
      verify(connectionSpy).commit();
      verify(connectionSpy, never()).rollback();

      try (Connection observer = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
          var statement = observer.createStatement();
          var rows = statement.executeQuery("SELECT state FROM jobs")) {
        assertTrue(rows.next(), "the committed job must remain visible to another connection");
        assertEquals("PENDING", rows.getString(1));
        assertFalse(rows.next(), "the fixture should contain exactly one inserted job");
      }
    } finally {
      writeField("connection", originalConnection);
      writeField("changeStream", originalChangeStream);
    }
  }

  @Test
  void failedNormalCloseRefusesReopenUntilTheRetainedConnectionCloses() throws Exception {
    Path jobPath = tempDir.resolve("close-reopen.txt");
    jobQueue.enqueue(List.of(jobPath));
    Connection original = readField("connection", Connection.class);
    Connection intercepted = org.mockito.Mockito.mock(Connection.class,
        org.mockito.AdditionalAnswers.delegatesTo(original));
    var failure = new java.sql.SQLException("native queue close unavailable");
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(call -> {
      if (first.getAndSet(false)) throw failure;
      original.close();
      return null;
    }).when(intercepted).close();
    writeField("connection", intercepted);
    try {
      assertSame(failure, assertThrows(java.io.IOException.class, jobQueue::close).getCause());
      assertSame(intercepted, readField("connection", Connection.class));
      assertSame(failure, assertThrows(java.sql.SQLException.class, jobQueue::open).getCause());
      assertThrows(IllegalStateException.class, () -> jobQueue.enqueue(List.of(jobPath)));
      assertFalse(jobQueue.queueDbHealthSnapshot().healthy());
    } finally {
      jobQueue.close();
      original.close();
    }
    assertTrue(original.isClosed());
    jobQueue.open();
    assertEquals("PENDING", observedJobState());
  }

  @Test
  void errorAfterSqlWriteRollsBackWithoutAnImplicitCommit() throws Exception {
    transactionWorkFailure(false, false);
  }

  @Test
  void failedRollbackClosesConnectionWithoutRestoringAutoCommit() throws Exception {
    transactionWorkFailure(true, false);
  }

  @Test
  void failedRollbackAndCloseRefuseFurtherQueueUseUntilCleanup() throws Exception {
    transactionWorkFailure(true, true);
  }

  private void transactionWorkFailure(boolean rollbackFails, boolean closeFails) throws Exception {
    Path jobPath = tempDir.resolve("transaction-failure.txt");
    jobQueue.enqueue(List.of(jobPath));
    Connection original = readField("connection", Connection.class);
    Connection intercepted = org.mockito.Mockito.mock(Connection.class,
        org.mockito.AdditionalAnswers.delegatesTo(original));
    Throwable primary = rollbackFails ? new IllegalStateException("ledger preparation failed after job update")
        : new AssertionError("ledger preparation failed after job update");
    org.mockito.Mockito.doAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.contains("INSERT INTO ingestion_ledger")) throw primary;
      return original.prepareStatement(sql);
    }).when(intercepted).prepareStatement(org.mockito.ArgumentMatchers.anyString());
    var rollback = new java.sql.SQLException("rollback unavailable");
    var close = new java.sql.SQLException("connection close unavailable");
    if (rollbackFails) doThrow(rollback).when(intercepted).rollback();
    if (closeFails) doThrow(close).when(intercepted).close();
    List<IndexingJobChangeFeed.Delta> deltas = new java.util.ArrayList<>();
    var stream = jobQueue.changeStream();
    stream.subscribe(deltas::add);
    writeField("connection", intercepted);
    try {
      Class<? extends Throwable> expected = rollbackFails ? IllegalStateException.class : AssertionError.class;
      assertSame(primary, assertThrows(expected,
          () -> jobQueue.markDone(jobPath, successOutcome(), null)));
      assertEquals("PENDING", observedJobState(), "an independent connection must not see a partial DONE write");
      verify(intercepted).rollback();
      if (rollbackFails) {
        verify(intercepted, never()).setAutoCommit(true);
        verify(intercepted).close();
        assertSame(rollback, primary.getSuppressed()[0]);
        if (closeFails) {
          assertSame(close, primary.getSuppressed()[1]);
          assertSame(close, assertThrows(java.io.IOException.class, jobQueue::close).getCause());
          assertSame(intercepted, readField("connection", Connection.class), "failed close retains its handle");
        }
        assertThrows(IllegalStateException.class, () -> jobQueue.enqueue(List.of(jobPath)));
        assertThrows(IllegalStateException.class, () -> stream.subscribeWithSnapshot(delta -> {}));
        assertThrows(java.sql.SQLException.class, jobQueue::open,
            "reopening cannot discard an unresolved transaction owner");
      }
      assertTrue(deltas.isEmpty(), "a rolled-back or uncertain update cannot be projected");
      assertEquals("PENDING", observedJobState(), "an independent connection must not see a partial DONE write");
    } finally {
      writeField("connection", original);
    }
    jobQueue.close();
    jobQueue.open();
    assertEquals("PENDING", observedJobState());
  }

  @Test
  void failedRestoreAfterCommitStillProjectsTheConfirmedRow() throws Exception {
    failedRestoreAfterCommit(false);
  }

  @Test
  void failedRestoreKeepsPrimaryCauseWhenASubscriberThrowsError() throws Exception {
    failedRestoreAfterCommit(true);
  }

  private void failedRestoreAfterCommit(boolean subscriberFails) throws Exception {
    Path jobPath = tempDir.resolve("restore-failure.txt");
    jobQueue.enqueue(List.of(jobPath));
    Connection original = readField("connection", Connection.class);
    Connection intercepted = org.mockito.Mockito.mock(Connection.class,
        org.mockito.AdditionalAnswers.delegatesTo(original));
    var restore = new java.sql.SQLException("auto-commit restore unavailable");
    doThrow(restore).when(intercepted).setAutoCommit(true);
    List<IndexingJobChangeFeed.Delta> deltas = new java.util.ArrayList<>();
    jobQueue.changeStream().subscribe(deltas::add);
    var subscriberFailure = new AssertionError("subscriber failed after confirmed commit");
    if (subscriberFails) jobQueue.changeStream().subscribe(delta -> { throw subscriberFailure; });
    var projection = spy(jobQueue.changeStream());
    writeField("connection", intercepted);
    writeField("changeStream", projection);
    try {
      var failure = assertThrows(OutcomeWriteException.class,
          () -> jobQueue.markDone(jobPath, successOutcome(), null));
      assertSame(restore, failure.getCause());
      if (subscriberFails) assertSame(subscriberFailure, restore.getSuppressed()[0]);
      verify(intercepted).commit();
      verify(intercepted, never()).rollback();
      assertEquals("DONE", observedJobState());
      assertEquals(1, deltas.size(), "confirmed commit must still reach its projection");
      verify(projection).commitSucceeded();
      assertEquals("DONE", ((IndexingJobChangeFeed.Delta.Update) deltas.getFirst()).row().state());
      assertFalse(failure.getMessage().contains("transaction rolled back"));
      assertThrows(IllegalStateException.class, () -> jobQueue.enqueue(List.of(jobPath)));
    } finally {
      writeField("connection", original);
    }
  }

  private String observedJobState() throws Exception {
    try (var observer = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("jobs.db"));
        var statement = observer.createStatement();
        var rows = statement.executeQuery("SELECT state FROM jobs")) {
      assertTrue(rows.next());
      String state = rows.getString(1);
      assertFalse(rows.next());
      return state;
    }
  }

  private static IngestionOutcome successOutcome() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
        io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE);
  }

  /**
   * Tempdoc 812 D2 — the capture-side rollup key round-trips: the scan that enqueued a job is
   * persisted on the row and rides every delta the Head reads, so the Head can group per-document
   * terminal outcomes by scan instead of guessing from render adjacency. A job enqueued outside a
   * scan carries an EMPTY key (never a fake one).
   */
  @Test
  void scanIdRoundTripsFromEnqueueToDelta() throws Exception {
    CapturingSubscriber sub = new CapturingSubscriber();
    var s = jobQueue.changeStream().subscribeWithSnapshot(sub);

    // Tempdoc 812 D2 rides 813 Slice B's single jobs-table write path: one call states the size
    // AND the scan key, because the INSERT OR REPLACE resets any column the call omits.
    jobQueue.enqueueEntries(
        List.of(new JobQueue.EnqueueEntry(Path.of("/tmp/scanned.txt"), 1_024L)),
        "scifact",
        "scan-42");
    jobQueue.enqueueEntries(
        List.of(new JobQueue.EnqueueEntry(Path.of("/tmp/single.txt"), 512L)), "scifact");

    sub.awaitDeliveries(2);
    var scanned = (IndexingJobChangeFeed.Delta.Insert) sub.deltas.get(0);
    var single = (IndexingJobChangeFeed.Delta.Insert) sub.deltas.get(1);
    assertEquals("scan-42", scanned.row().scanId());
    assertEquals("", single.row().scanId(), "no scan → no key, not a fabricated one");

    // The key also survives the state transition the ledger actually reads (the terminal outcome).
    var batch = jobQueue.pollPending(2);
    assertEquals(2, batch.size());
    jobQueue.markDone(Path.of("/tmp/scanned.txt"));
    sub.awaitDeliveries(5); // 2 PROCESSING claims + 1 DONE (the poll updates both rows)
    var lastForScanned =
        sub.deltas.stream()
            .filter(d -> d instanceof IndexingJobChangeFeed.Delta.Update)
            .map(d -> ((IndexingJobChangeFeed.Delta.Update) d).row())
            .filter(r -> "DONE".equals(r.state()))
            .findFirst()
            .orElseThrow();
    assertEquals("scan-42", lastForScanned.scanId());

    // And through the snapshot path a reconnecting Head reads.
    var s2 = jobQueue.changeStream().subscribeWithSnapshot(new CapturingSubscriber());
    assertTrue(
        s2.items().stream().anyMatch(r -> "scan-42".equals(r.scanId())),
        "the snapshot frame carries the scan key too");
    s2.subscription().close();
    s.subscription().close();
  }

  @Test
  void markDoneEmitsUpdateDelta() throws Exception {
    jobQueue.enqueue(List.of(Path.of("/tmp/b.txt")));

    CapturingSubscriber sub = new CapturingSubscriber();
    jobQueue.changeStream().subscribe(sub);

    var batch = jobQueue.pollPending(1);
    assertFalse(batch.isEmpty(), "batch should contain 1 job");
    // pollPending UPDATEs PENDING → PROCESSING; subscribe was after enqueue, so we see one update.
    sub.awaitDeliveries(1);
    assertInstanceOf(IndexingJobChangeFeed.Delta.Update.class, sub.deltas.get(0));

    sub.deltas.clear();
    jobQueue.markDone(
        batch.get(0).path(),
        IngestionOutcome.of(
            IngestionOutcomeClass.SUCCESS_FULL,
            IngestionReasonCodes.SUCCESS,
            io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));

    sub.awaitDeliveries(1);
    var delta = sub.deltas.get(0);
    assertInstanceOf(IndexingJobChangeFeed.Delta.Update.class, delta);
    var update = (IndexingJobChangeFeed.Delta.Update) delta;
    assertEquals("DONE", update.row().state(), "markDone transitions PROCESSING → DONE");
  }

  @Test
  void rapidMutationsArriveInCausalOrderWithoutLoss() throws Exception {
    // Verification step #5 from the slice 445 tempdoc: 1000 rapid
    // INSERT/UPDATE rows → all deltas arrive in causal order (= SQLite write
    // order). SqliteJobQueue uses a single Connection so writes serialize;
    // the change-stream emits one delta per commit. The contract: count
    // matches the mutation count, monotonic seq, no drops.
    int n = 1000;
    List<Path> paths = new java.util.ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      paths.add(Path.of("/tmp/stress-" + i + ".txt"));
    }
    CapturingSubscriber sub = new CapturingSubscriber();
    var snap = jobQueue.changeStream().subscribeWithSnapshot(sub);
    assertEquals(0, snap.snapshotSeq(), "empty queue at subscribe");
    snap.subscription();

    long startNs = System.nanoTime();
    int enqueued = jobQueue.enqueue(paths);
    long enqueueMs = (System.nanoTime() - startNs) / 1_000_000;
    assertEquals(n, enqueued, "all paths enqueued");

    sub.awaitDeliveries(n);

    // Causal order: change-stream emits in SQLite write order. Since enqueue
    // serializes via SqliteJobQueue's lock, deltas land in the same order
    // we requested.
    long previousSeq = 0L;
    java.util.Set<String> seenHashes = new java.util.HashSet<>();
    for (int i = 0; i < n; i++) {
      var delta = sub.deltas.get(i);
      assertInstanceOf(IndexingJobChangeFeed.Delta.Insert.class, delta);
      var insert = (IndexingJobChangeFeed.Delta.Insert) delta;
      assertEquals("PENDING", insert.row().state());
      assertTrue(
          insert.row().lastUpdatedMs() >= previousSeq,
          "causal order: lastUpdatedMs must be non-decreasing across the delta sequence");
      previousSeq = insert.row().lastUpdatedMs();
      seenHashes.add(insert.row().pathHash());
    }
    assertEquals(n, seenHashes.size(), "every row distinct (no dup deliveries)");
    assertEquals(n, jobQueue.changeStream().currentSeq(), "seq reached n exactly");

    // Soft-bound on throughput so a regression that adds a sleep or a
    // per-row commit gets caught. 1000 inserts on a temp SQLite + a single
    // change-stream listener should land well under 5s on any dev machine.
    assertTrue(enqueueMs < 5000, "1000 enqueues took " + enqueueMs + "ms (>5s threshold)");

    // Now do 1000 state transitions (UPDATEs via pollPending) and verify
    // we see another N deltas.
    sub.deltas.clear();
    var batch = jobQueue.pollPending(n);
    assertEquals(n, batch.size());
    sub.awaitDeliveries(n);
    for (var delta : sub.deltas) {
      assertInstanceOf(IndexingJobChangeFeed.Delta.Update.class, delta);
    }
    assertEquals(2L * n, jobQueue.changeStream().currentSeq(), "seq advanced by 2n total");
  }

  @Test
  void snapshotCapturesPriorState() throws Exception {
    jobQueue.enqueue(List.of(Path.of("/tmp/c.txt"), Path.of("/tmp/d.txt")));

    CapturingSubscriber sub = new CapturingSubscriber();
    var snap = jobQueue.changeStream().subscribeWithSnapshot(sub);

    assertEquals(2, snap.items().size(), "snapshot reflects pre-subscribe enqueues");
    // Both inserts pre-dated subscribe: snapshotSeq should equal currentSeq at that moment.
    assertTrue(snap.snapshotSeq() >= 2, "seq advanced by 2 inserts");
    assertTrue(sub.deltas.isEmpty(), "no live deltas yet");

    snap.subscription().close();
  }

  private static final class CapturingSubscriber
      implements java.util.function.Consumer<IndexingJobChangeFeed.Delta> {
    final List<IndexingJobChangeFeed.Delta> deltas = new CopyOnWriteArrayList<>();

    @Override
    public void accept(IndexingJobChangeFeed.Delta d) {
      deltas.add(d);
    }

    void awaitDeliveries(int n) throws InterruptedException {
      long deadline = System.nanoTime() + 10_000_000_000L; // 10s
      while (deltas.size() < n && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(n, deltas.size(), "expected " + n + " deltas, got " + deltas.size());
    }
  }

  private <T> T readField(String name, Class<T> type) throws ReflectiveOperationException {
    Field field = SqliteJobQueue.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(jobQueue));
  }

  private void writeField(String name, Object value) throws ReflectiveOperationException {
    Field field = SqliteJobQueue.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(jobQueue, value);
  }
}

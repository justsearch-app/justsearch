/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite coverage for committed recorded-walk notifications and receipt retention. */
final class RecordedWalkNotificationTest {
  private static final String PLAN = "a".repeat(64);

  @TempDir Path temp;

  @Test
  void failedNotificationSetupDetachesEarlierDisplayStreamAndAllowsRetry() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("failed-notification-open.db"))) {
      var construction = org.mockito.Mockito.mockConstruction(SqliteRecordedWalkChanges.class,
          (mock, context) -> { throw new IllegalStateException("notification setup fixture"); });
      try (construction) {
        assertThrows(RuntimeException.class, queue::open);
        assertTrue(queue.indexingJobChangeFeed().isEmpty(), "failed open must not expose a closed display stream");
      }
      queue.open();
      assertTrue(queue.indexingJobChangeFeed().isPresent());
      assertEquals(key(115), queue.beginRecordedWalk(key(115), PLAN, true).operationKey());
    }
  }

  @Test
  void fatalObserverPropagatesAfterCommitWithoutErasingReceipt() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("fatal-observer.db"))) {
      queue.open();
      var fatal = new AssertionError("fatal observer fixture");
      var subscription = queue.subscribeRecordedWalks(key -> { throw fatal; });
      try (subscription) {
        assertSame(fatal, assertThrows(AssertionError.class,
            () -> queue.beginRecordedWalk(key(116), PLAN, true)));
      }
      assertTrue(queue.recordedWalk(key(116)).isPresent());
    }
  }

  @Test
  void restoreFailureClosesNotificationsAndPreservesConfirmedProgress() throws Exception {
    Path db = temp.resolve("restore-notification.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key(117), PLAN, true);
      var field = SqliteJobQueue.class.getDeclaredField("connection");
      field.setAccessible(true);
      var original = (Connection) field.get(queue);
      var intercepted = org.mockito.Mockito.mock(Connection.class,
          org.mockito.AdditionalAnswers.delegatesTo(original));
      var restore = new java.sql.SQLException("restore notification fixture");
      org.mockito.Mockito.doThrow(restore).when(intercepted).setAutoCommit(true);
      field.set(queue, intercepted);
      var failure = assertThrows(IllegalStateException.class, () -> queue.closeRecordedWalkEnumeration(
          key(117), opened.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE));
      assertSame(restore, failure.getCause());
      org.mockito.Mockito.verify(intercepted).commit();
      org.mockito.Mockito.verify(intercepted, org.mockito.Mockito.never()).rollback();
      org.mockito.Mockito.verify(intercepted).close();
      assertThrows(IllegalStateException.class, () -> queue.subscribeRecordedWalks(key -> {}));
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_walk_progress WHERE enumeration_outcome = 'COMPLETE'"));
      queue.close();
      queue.open();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE,
          queue.recordedWalk(key(117)).orElseThrow().enumerationOutcome());
    }
  }

  @Test
  void observerCanReadRecordedWalkFromSecondThreadAfterCommit() throws Exception {
    String key = key(101);
    Path db = temp.resolve("observer-read.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var observed = new CountDownLatch(1);
      var read = new AtomicReference<JobQueue.WalkProgress>();
      var failure = new AtomicReference<Throwable>();
      var subscription = queue.subscribeRecordedWalks(notified -> {
        if (!key.equals(notified)) return;
        try {
          read.set(CompletableFuture.supplyAsync(
              () -> queue.recordedWalk(key).orElseThrow()).get(2, TimeUnit.SECONDS));
        } catch (Throwable problem) {
          failure.set(problem);
        } finally {
          observed.countDown();
        }
      });
      try (subscription) {
        queue.beginRecordedWalk(key, PLAN, true);
        assertTrue(observed.await(2, TimeUnit.SECONDS), "notification observer did not complete");
      }
      assertNull(failure.get(), "observer read failed or remained blocked by the queue lock");
      assertEquals(key, read.get().operationKey());
    }
  }

  @Test
  void rolledBackMutationSendsNoEventAndNextCommittedTransactionSendsOne() throws Exception {
    String key = key(102);
    String nextKey = key(114);
    Path db = temp.resolve("rollback.db");
    Path file = temp.resolve("rollback.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      var events = new CopyOnWriteArrayList<String>();
      var event = new CountDownLatch(1);
      var subscription = queue.subscribeRecordedWalks(notified -> {
        events.add(notified);
        event.countDown();
      });
      try (subscription) {
        execute(db, "CREATE TRIGGER refuse_walk_revision AFTER UPDATE ON ingestion_walk_progress "
            + "BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END");
        assertThrows(IllegalStateException.class, () -> queue.enqueueRecordedEntries(key,
            opened.enumerationEpoch(), List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null));
        assertTrue(events.isEmpty(), "rolled-back progress must not produce a phantom event");
        execute(db, "DROP TRIGGER refuse_walk_revision");

        queue.beginRecordedWalk(nextKey, PLAN, true);
        assertTrue(event.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(nextKey), events,
            "a rolled-back provisional row id must not notify its old walk key");
      }
    }
  }

  @Test
  void lateOutcomeReleaseLetsObserverSealAndSupersededDoneReturnAlsoReleases() throws Exception {
    String key = key(103);
    Path db = temp.resolve("late-release.db");
    Path file = temp.resolve("late-release.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = new AtomicReference<JobQueue.WalkProgress>();
      var failure = new AtomicReference<Throwable>();
      var notified = new CountDownLatch(1);
      var armed = new AtomicInteger();
      var subscription = queue.subscribeRecordedWalks(operation -> {
        if (!key.equals(operation) || armed.getAndIncrement() != 0) return;
        try {
          sealed.set(queue.trySealRecordedWalk(key));
        } catch (Throwable problem) {
          failure.set(problem);
        } finally {
          notified.countDown();
        }
      });
      try (subscription) {
        queue.markDoneTransitions(List.of(transition(claim, "b".repeat(64))), success());
        assertTrue(notified.await(2, TimeUnit.SECONDS));
      }
      assertNull(failure.get());
      assertNotNull(sealed.get());
      assertNotNull(sealed.get().sealedAt(), "release notification must run after the owner exits");
    }

    String supersededKey = key(104);
    Path supersededDb = temp.resolve("superseded-release.db");
    Path supersededFile = temp.resolve("superseded-release.txt");
    try (var queue = new SqliteJobQueue(supersededDb, ignored -> true)) {
      queue.open();
      var opened = queue.beginRecordedWalk(supersededKey, PLAN, true);
      queue.enqueueRecordedEntries(supersededKey, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(supersededFile)), null);
      var claim = queue.pollPending(1).getFirst();
      assertEquals(1, queue.clearAll());
      queue.closeRecordedWalkEnumeration(supersededKey, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = new AtomicReference<JobQueue.WalkProgress>();
      var notified = new CountDownLatch(1);
      var subscription = queue.subscribeRecordedWalks(operation -> {
        if (!supersededKey.equals(operation)) return;
        try {
          sealed.set(queue.trySealRecordedWalk(supersededKey));
        } finally {
          notified.countDown();
        }
      });
      try (subscription) {
        queue.returnUnfinishedClaims(List.of(claim));
        assertTrue(notified.await(2, TimeUnit.SECONDS), "claim release did not notify the seal owner");
      }
      assertNotNull(sealed.get());
      assertNotNull(sealed.get().sealedAt());
    }
  }

  @Test
  void failedCallbackRollbackRetainsOwnerAndSendsNoReleaseNotification() throws Exception {
    String key = key(105);
    Path db = temp.resolve("failed-rollback-notification.db");
    Path file = temp.resolve("failed-rollback-notification.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.FAILED);
      var events = new AtomicInteger();
      var subscription = queue.subscribeRecordedWalks(operation -> {
        if (key.equals(operation)) events.incrementAndGet();
      });
      try (subscription) {
        execute(db, "CREATE TRIGGER refuse_failed_ledger BEFORE INSERT ON ingestion_ledger "
            + "BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END");
        assertThrows(OutcomeWriteException.class,
            () -> queue.markClaimFailed(claim, transientFailure(), null));
        assertEquals(0, events.get());
        assertNull(queue.trySealRecordedWalk(key).sealedAt(), "rollback must retain the issued owner");
        execute(db, "DROP TRIGGER refuse_failed_ledger");
        queue.markClaimFailed(claim, transientFailure(), null);
        assertEquals(1, events.get());
      }
    }
  }

  @Test
  void reentrantSealAndDuplicateAcknowledgementRemainFinite() throws Exception {
    String key = key(106);
    Path db = temp.resolve("reentrant-ack.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var callbacks = new AtomicInteger();
      var first = new CountDownLatch(1);
      var second = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      var subscription = queue.subscribeRecordedWalks(operation -> {
        if (!key.equals(operation)) return;
        int call = callbacks.incrementAndGet();
        try {
          var receipt = queue.trySealRecordedWalk(key);
          if (call == 1) {
            assertTrue(queue.acknowledgeRecordedWalk(key, receipt.revision()));
            assertTrue(queue.acknowledgeRecordedWalk(key, receipt.revision()));
            first.countDown();
          } else if (call == 2) {
            second.countDown();
          }
        } catch (Throwable problem) {
          failure.set(problem);
          first.countDown();
          second.countDown();
        }
      });
      try (subscription) {
        assertNotNull(queue.trySealRecordedWalk(key).sealedAt());
        assertTrue(first.await(2, TimeUnit.SECONDS));
        assertTrue(second.await(2, TimeUnit.SECONDS), "acknowledgement notification did not complete");
      }
      assertNull(failure.get());
      assertEquals(2, callbacks.get(), "duplicate acknowledgement must not create a notification loop");
      assertTrue(queue.recordedWalk(key).orElseThrow().acknowledgedRevision() > 0);
    }
  }

  @Test
  void throwingObserverDoesNotHideReceiptFromAnotherObserver() throws Exception {
    String key = key(107);
    Path db = temp.resolve("observer-failure.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var observed = new AtomicReference<JobQueue.WalkProgress>();
      var delivered = new CountDownLatch(1);
      var throwing = queue.subscribeRecordedWalks(operation -> {
        if (key.equals(operation)) throw new IllegalStateException("observer fixture");
      });
      var reader = queue.subscribeRecordedWalks(operation -> {
        if (key.equals(operation)) {
          observed.set(queue.recordedWalk(key).orElseThrow());
          delivered.countDown();
        }
      });
      try (throwing; reader) {
        assertDoesNotThrow(() -> queue.trySealRecordedWalk(key));
        assertTrue(delivered.await(2, TimeUnit.SECONDS));
      }
      assertNotNull(observed.get().sealedAt());
    }
  }

  @Test
  void unsubscribeAndQueueCloseStopFutureNotifications() throws Exception {
    String key = key(108);
    Path db = temp.resolve("unsubscribe.db");
    var events = new AtomicInteger();
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var subscription = queue.subscribeRecordedWalks(operation -> {
        if (key.equals(operation)) events.incrementAndGet();
      });
      subscription.close();
      queue.beginRecordedWalk(key, PLAN, true);
      assertEquals(0, events.get());
      queue.close();
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      reopened.beginRecordedWalk(key, PLAN, false);
      assertEquals(0, events.get(), "a closed queue must detach its old subscription");
    }
  }

  @Test
  void retentionPrunesAcknowledgedEmptyWalkWithoutNotification() throws Exception {
    String key = key(109);
    Path db = temp.resolve("retention-empty.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = queue.trySealRecordedWalk(key);
      assertTrue(queue.acknowledgeRecordedWalk(key, sealed.revision()));
      backdateProgress(db, key);
      var events = new AtomicInteger();
      var subscription = queue.subscribeRecordedWalks(operation -> events.incrementAndGet());
      try (subscription) {
        assertEquals(0, queue.cleanupOldJobs(7));
        assertEquals(0, queue.cleanupOldLedgerEvents(7));
      }
      assertTrue(queue.recordedWalk(key).isEmpty());
      assertEquals(0, events.get(), "progress deletion must not notify a missing receipt");
    }
  }

  @Test
  void retentionKeepsAcknowledgedProgressWhileKeyedJobRemains() throws Exception {
    String key = key(110);
    Path db = temp.resolve("retention-job.db");
    Path file = temp.resolve("retention-job.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var sealed = sealIndexed(queue, key, file);
      assertTrue(queue.acknowledgeRecordedWalk(key, sealed.revision()));
      backdateProgress(db, key);
      execute(db, "DELETE FROM ingestion_ledger WHERE operation_key = '" + key + "'");
      assertEquals(0, queue.cleanupOldJobs(7), "a fresh keyed job alone keeps its walk progress");
      assertEquals(0, queue.cleanupOldLedgerEvents(7));
      assertTrue(queue.recordedWalk(key).isPresent());
    }
  }

  @Test
  void retentionKeepsProgressUntilRemainingLedgerIsRemoved() throws Exception {
    String key = key(111);
    Path db = temp.resolve("retention-ledger.db");
    Path file = temp.resolve("retention-ledger.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var sealed = sealIndexed(queue, key, file);
      assertTrue(queue.acknowledgeRecordedWalk(key, sealed.revision()));
      backdateAll(db, key, normalized(file));
      assertEquals(1, queue.cleanupOldJobs(7));
      assertTrue(queue.recordedWalk(key).isPresent(), "remaining ledger evidence retains progress");
      assertEquals(1, queue.cleanupOldLedgerEvents(7));
      assertTrue(queue.recordedWalk(key).isEmpty());
    }
  }

  @Test
  void retentionPreservesUnacknowledgedAndMissingProgressEvidence() throws Exception {
    String unacknowledgedKey = key(112);
    Path unacknowledgedDb = temp.resolve("retention-unacknowledged.db");
    Path unacknowledgedFile = temp.resolve("retention-unacknowledged.txt");
    try (var queue = new SqliteJobQueue(unacknowledgedDb, ignored -> true)) {
      queue.open();
      sealIndexed(queue, unacknowledgedKey, unacknowledgedFile);
      backdateAll(unacknowledgedDb, unacknowledgedKey, normalized(unacknowledgedFile));
      assertEquals(0, queue.cleanupOldJobs(7));
      assertEquals(0, queue.cleanupOldLedgerEvents(7));
      assertTrue(queue.recordedWalk(unacknowledgedKey).isPresent());
    }

    String missingKey = key(113);
    Path missingDb = temp.resolve("retention-missing-progress.db");
    Path missingFile = temp.resolve("retention-missing-progress.txt");
    try (var queue = new SqliteJobQueue(missingDb, ignored -> true)) {
      queue.open();
      var sealed = sealIndexed(queue, missingKey, missingFile);
      assertTrue(queue.acknowledgeRecordedWalk(missingKey, sealed.revision()));
      backdateAll(missingDb, missingKey, normalized(missingFile));
      execute(missingDb, "DELETE FROM ingestion_walk_progress WHERE operation_key = '" + missingKey + "'");
      assertEquals(0, queue.cleanupOldJobs(7));
      assertEquals(0, queue.cleanupOldLedgerEvents(7));
      assertEquals(1, count(missingDb, "SELECT count(*) FROM jobs WHERE path = '"
          + normalized(missingFile) + "'"));
      assertEquals(1, count(missingDb, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = '"
          + missingKey + "'"));
    }
  }

  private static JobQueue.WalkProgress sealIndexed(SqliteJobQueue queue, String key, Path file) {
    var opened = queue.beginRecordedWalk(key, PLAN, true);
    queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
        List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
    var claim = queue.pollPending(1).getFirst();
    queue.markDoneTransitions(List.of(transition(claim, "c".repeat(64))), success());
    queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
        JobQueue.WalkEnumerationOutcome.COMPLETE);
    return queue.trySealRecordedWalk(key);
  }

  private static String key(int suffix) {
    return "01994180-0000-7000-8000-000000000" + String.format("%03d", suffix);
  }

  private static JobQueue.IngestionLedgerTransition transition(JobQueue.IndexJob claim, String hash) {
    return new JobQueue.IngestionLedgerTransition(claim, null, hash);
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome transientFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "IO_FAILED",
        IngestionRetryPolicy.RETRY_WITH_BACKOFF);
  }

  private static String normalized(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static void execute(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void backdateProgress(Path db, String key) throws Exception {
    long old = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L;
    try (Connection connection = connection(db);
        var statement = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET sealed_at = ? WHERE operation_key = ?")) {
      statement.setLong(1, old);
      statement.setString(2, key);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void backdateAll(Path db, String key, String path) throws Exception {
    long old = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L;
    try (Connection connection = connection(db);
        var progress = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET sealed_at = ? WHERE operation_key = ?");
        var job = connection.prepareStatement("UPDATE jobs SET last_updated = ? WHERE path = ?");
        var ledger = connection.prepareStatement(
            "UPDATE ingestion_ledger SET observed_at = ? WHERE operation_key = ?")) {
      progress.setLong(1, old);
      progress.setString(2, key);
      assertEquals(1, progress.executeUpdate());
      job.setLong(1, old);
      job.setString(2, path);
      assertEquals(1, job.executeUpdate());
      ledger.setLong(1, old);
      ledger.setString(2, key);
      assertEquals(1, ledger.executeUpdate());
    }
  }

  private static long count(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static Connection connection(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
  }
}

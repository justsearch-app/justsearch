/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class QueueDefectRegressionTest {
  @TempDir Path temp;

  static IngestionOutcome outcome(IngestionOutcomeClass type, IngestionRetryPolicy retry) {
    return IngestionOutcome.of(type, type == IngestionOutcomeClass.SKIPPED_POLICY ? "CANCELLED" : "TEST", retry);
  }

  static void connection(SqliteJobQueue queue, Connection conn) throws Exception {
    var field = SqliteJobQueue.class.getDeclaredField("connection");
    field.setAccessible(true);
    field.set(queue, conn);
  }

  @Test void unrelatedHeartbeatsCannotRenewRestartOrphansAfterFailedRecovery() throws Exception {
    Path db = temp.resolve("heartbeat.db"), orphan = temp.resolve("orphan"), live = temp.resolve("live");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); queue.enqueue(List.of(orphan)); queue.pollPending(1);
      queue.close(); queue.open();
      Connection actual = JobCountsTest.connection(queue);
      Connection failed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doThrow(new SQLException("startup recovery unavailable")).when(failed).prepareStatement(startsWith("SELECT path, scan_id, walk_seen_epoch"));
      connection(queue, failed);
      assertEquals(0, queue.recoverStuckJobs());
      connection(queue, actual);
      queue.enqueue(List.of(live));
      var claim = queue.pollPending(1).getFirst();
      try (var stmt = actual.createStatement()) { stmt.executeUpdate("UPDATE jobs SET last_updated=1 WHERE state='PROCESSING'"); }
      queue.heartbeatProcessing();
      assertEquals(1, queue.recoverStuckJobs(60_000), "unrelated liveness cannot postpone the orphan");
      assertEquals(List.of(orphan), queue.pollPending(1).stream().map(JobQueue.IndexJob::path).toList());
      assertTrue(queue.ownsClaimForPublication(claim), "the live batch still owns its claim");
    }
  }

  @Test void failedCompletionRetainsLiveOwnershipAndOldClaimCannotRenewAnotherRevision() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("ownership.db"))) {
      queue.open(); Path path = temp.resolve("path"); queue.enqueue(List.of(path));
      var claim = queue.pollPending(1).getFirst();
      Connection actual = JobCountsTest.connection(queue);
      try (var stmt = actual.createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET last_updated=1");
        stmt.execute("CREATE TRIGGER fail_completion BEFORE INSERT ON ingestion_ledger BEGIN SELECT RAISE(ABORT,'completion fault'); END");
      }
      assertThrows(OutcomeWriteException.class, () -> queue.markClaimDone(claim,
          outcome(IngestionOutcomeClass.SKIPPED_POLICY, IngestionRetryPolicy.NONE), null));
      assertEquals(0, queue.recoverStuckJobs(1));
      assertTrue(queue.pollPending(1).isEmpty());
      try (var stmt = actual.createStatement()) {
        stmt.execute("DROP TRIGGER fail_completion");
        stmt.executeUpdate("UPDATE jobs SET unit_revision='replacement',last_updated=1");
      }
      queue.heartbeatProcessing();
      assertEquals(1, queue.recoverStuckJobs(60_000), "the issued object does not own the replacement revision");
      assertFalse(queue.ownsClaimForPublication(claim));
    }
  }

  @ParameterizedTest @ValueSource(strings={"depth", "completed", "states", "strictStates", "failureCount", "lastFailure", "nextRetry", "outcomes", "bufferDepth", "bufferList", "prefix"})
  void populatedReadFailuresAreUnavailableAndRecordHealth(String query) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve(query + ".db"))) {
      queue.open(); queue.enqueue(List.of(temp.resolve("file")));
      queue.putSwitchBuffer("file", "UPSERT", "payload");
      queue.close(); queue.open();
      Connection actual = JobCountsTest.connection(queue);
      var fail = new AtomicBoolean(true);
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doAnswer(call -> {
        String sql = call.getArgument(0);
        if (fail.get() && matches(query, sql)) throw new SQLException("selected read unavailable");
        return actual.prepareStatement(sql);
      }).when(observed).prepareStatement(anyString());
      doAnswer(call -> {
        Statement statement = actual.createStatement();
        Statement wrapped = mock(Statement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
        doAnswer(read -> {
          String sql = read.getArgument(0);
          if (fail.get() && matches(query, sql)) throw new SQLException("selected read unavailable");
          return statement.executeQuery(sql);
        }).when(wrapped).executeQuery(anyString());
        return wrapped;
      }).when(observed).createStatement();
      connection(queue, observed);
      assertThrows(IllegalStateException.class, () -> read(queue, query));
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      fail.set(false);
      assertTrue(queue.performIntegrityCheck());
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "structural integrity does not prove the failed read works");
      assertTrue(queue.queueDbHealthSnapshot().lastDbErrorAtMs() > 0);
      assertNotNull(read(queue, query));
      connection(queue, actual);
      assertEquals(1, queue.queueDepth());
      assertEquals(1, queue.switchBufferDepth());
    }
  }

  private static boolean matches(String query, String sql) {
    return switch(query) {
      case "depth" -> sql.contains("WHERE state IN ('PENDING', 'PROCESSING')");
      case "completed" -> sql.contains("WHERE state = 'DONE'");
      case "states", "strictStates" -> sql.contains("pending_ready_count");
      case "failureCount" -> sql.contains("SUM(n)") && sql.contains("'RETRY_EXHAUSTED'");
      case "lastFailure" -> sql.contains("ORDER BY last_updated DESC LIMIT 1");
      case "nextRetry" -> sql.contains("MIN(retry_after)");
      case "outcomes" -> sql.contains("GROUP BY outcome_class");
      case "bufferDepth" -> sql.contains("COUNT(*) FROM switch_buffer");
      case "bufferList" -> sql.contains("FROM switch_buffer ORDER BY");
      case "prefix" -> sql.contains("GROUP BY shown_state");
      default -> throw new AssertionError(query);
    };
  }

  private Object read(SqliteJobQueue queue, String query) {
    return switch(query) {
      case "depth" -> queue.queueDepth();
      case "completed" -> queue.completedCount();
      case "states" -> queue.jobStateCounts();
      case "strictStates" -> queue.jobStateCountsStrict();
      case "failureCount", "lastFailure", "nextRetry" -> queue.failureSummary();
      case "outcomes" -> queue.ingestionOutcomeSummary(0);
      case "bufferDepth" -> queue.switchBufferDepth();
      case "bufferList" -> queue.listSwitchBufferOps();
      case "prefix" -> queue.countByPathPrefix(temp.toString());
      default -> throw new AssertionError(query);
    };
  }

  @Test void closedConnectionAndFailedIntegrityCannotBecomeHealthyThroughUnrelatedReads() throws Exception {
    var queue = new SqliteJobQueue(temp.resolve("closed.db")); queue.open(); queue.close();
    assertThrows(IllegalStateException.class, queue::queueDepth);
    assertThrows(IllegalStateException.class, queue::completedCount);
    assertThrows(IllegalStateException.class, queue::jobStateCounts);
    assertThrows(IllegalStateException.class, queue::performIntegrityCheck);
    assertFalse(queue.queueDbHealthSnapshot().healthy());
  }

  @Test void successfulIntegrityCheckKeepsRecordedFailureAndErrorHistory() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("health.db"))) {
      queue.open(); queue.enqueue(List.of(temp.resolve("file")));
      queue.close(); queue.open();
      Connection actual = JobCountsTest.connection(queue);
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doThrow(new SQLException("transient summary read failure")).when(observed).prepareStatement(anyString());
      connection(queue, observed);
      try { queue.failureSummary(); }
      catch (IllegalStateException unavailable) { assertInstanceOf(SQLException.class, unavailable.getCause()); }
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      assertTrue(errorAt > 0);
      connection(queue, actual);
      assertEquals(1, queue.queueDepth());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertTrue(queue.performIntegrityCheck());
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "structural integrity does not prove the failed read works");
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
    }
  }

  @ParameterizedTest @ValueSource(strings={"sql", "corrupt", "empty"})
  void freshDatabaseIntegrityChecksRunAndFailuresStayRecorded(String fault) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("integrity.db"))) {
      queue.open();
      Connection actual = JobCountsTest.connection(queue);
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doAnswer(call -> {
        Statement statement = actual.createStatement();
        Statement wrapped = mock(Statement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
        doAnswer(read -> {
          if (fault.equals("sql")) throw new SQLException("quick_check unavailable");
          var rows = mock(java.sql.ResultSet.class);
          when(rows.next()).thenReturn(!fault.equals("empty"), false);
          when(rows.getString(1)).thenReturn("database disk image is malformed");
          return rows;
        }).when(wrapped).executeQuery("PRAGMA quick_check");
        return wrapped;
      }).when(observed).createStatement();
      connection(queue, observed);
      assertThrows(SQLException.class, queue::performIntegrityCheck);
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      connection(queue, actual);
      assertEquals(0, queue.queueDepth());
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "unrelated successful reads cannot prove integrity");
      assertTrue(queue.performIntegrityCheck());
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "a later passing check does not clear the recorded failure");
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
    }
  }

  @ParameterizedTest @ValueSource(booleans={false,true})
  void cancellationKeepsRecordedReceiptsImmutableAndFencesLateCompletion(boolean batch) throws Exception {
    Path db = temp.resolve("cancel.db"), path = temp.resolve("cancel");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open(); var walk = queue.beginRecordedWalk("walk", "a".repeat(64), true);
      queue.enqueueRecordedEntries("walk", walk.enumerationEpoch(), List.of(new JobQueue.EnqueueEntry(path, 1)), null);
      var claim = queue.pollPending(1).getFirst();
      var cancel = outcome(IngestionOutcomeClass.SKIPPED_POLICY, IngestionRetryPolicy.NONE);
      queue.markDone(path, cancel); queue.markDone(path, cancel);
      assertFalse(queue.ownsClaimForPublication(claim));
      var success = outcome(IngestionOutcomeClass.SUCCESS_FULL, IngestionRetryPolicy.NONE);
      if (batch) queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim,null,"b".repeat(64))), success);
      else assertFalse(queue.markClaimDone(claim, success, null));
      assertFalse(queue.hasIssuedRecordedClaims("walk"));
      assertEquals(1, queue.recentIngestionEvents(100).size());
      queue.closeRecordedWalkEnumeration("walk", walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = queue.trySealRecordedWalk("walk");
      assertNotNull(sealed.sealedAt());
      queue.markDone(path, cancel);
      assertEquals(sealed, queue.recordedWalk("walk").orElseThrow());
      assertEquals(0, queue.jobStateCounts().pendingCount());
      assertEquals("SKIPPED_POLICY", queue.watcherReconciliationRow(path.toString()).get("last_outcome_class"));
      assertNull(queue.watcherReconciliationRow(path.toString()).get("retry_after"));
      queue.close(); queue.open();
      assertEquals(sealed, queue.recordedWalk("walk").orElseThrow());
      assertEquals(1, queue.recentIngestionEvents(100).size());
    }
  }

  @Test void outcomeMetricsCountOnlyCommittedTerminalEffectsAndIsolateObserverFailures() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("metrics.db"))) {
      queue.open(); var seen = new ArrayList<String>(); queue.setOutcomeObserver(seen::add);
      Path a=temp.resolve("a"), b=temp.resolve("b"), c=temp.resolve("c"), d=temp.resolve("d");
      queue.enqueue(List.of(a,b,c,d));
      queue.markDone(a, outcome(IngestionOutcomeClass.SKIPPED_POLICY, IngestionRetryPolicy.NONE));
      queue.markDoneBatch(List.of(b,c), outcome(IngestionOutcomeClass.SUCCESS_FULL, IngestionRetryPolicy.NONE));
      queue.markFailed(d, outcome(IngestionOutcomeClass.IO_FAILED, IngestionRetryPolicy.RETRY_WITH_BACKOFF));
      assertEquals(List.of("SKIPPED_POLICY","SUCCESS_FULL","SUCCESS_FULL"), seen);
      queue.markFailed(d, outcome(IngestionOutcomeClass.IO_FAILED, IngestionRetryPolicy.NONE));
      queue.markFailed(temp.resolve("missing"), outcome(IngestionOutcomeClass.PARSER_FAILED, IngestionRetryPolicy.NONE));
      assertEquals(List.of("SKIPPED_POLICY","SUCCESS_FULL","SUCCESS_FULL","IO_FAILED"), seen);
      Path rolled=temp.resolve("rolled"); queue.enqueue(List.of(rolled));
      try(var stmt=JobCountsTest.connection(queue).createStatement()) {
        stmt.execute("CREATE TRIGGER fail_ledger BEFORE INSERT ON ingestion_ledger BEGIN SELECT RAISE(ABORT,'ledger fault'); END");
      }
      assertThrows(OutcomeWriteException.class, () -> queue.markDone(rolled, outcome(IngestionOutcomeClass.SKIPPED_POLICY, IngestionRetryPolicy.NONE)));
      assertEquals(4, seen.size());
      try(var stmt=JobCountsTest.connection(queue).createStatement()) {stmt.execute("DROP TRIGGER fail_ledger");}
      queue.setOutcomeObserver(type -> {throw new IllegalStateException("observer failure");});
      queue.markDone(rolled, outcome(IngestionOutcomeClass.SKIPPED_POLICY, IngestionRetryPolicy.NONE));
      assertEquals(4, queue.completedCount());
    }
  }

  @Test void recordedMetricsIncludeCancellationAndAdministrativeSkipOnceAndExcludeDeferrals() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("recorded-metrics.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var seen = new ArrayList<String>();
      queue.setOutcomeObserver(seen::add);
      var walk = queue.beginRecordedWalk("walk", "a".repeat(64), true);
      Path cancelled = temp.resolve("cancelled"), stopped = temp.resolve("stopped");
      queue.enqueueRecordedEntries("walk", walk.enumerationEpoch(), List.of(
          new JobQueue.EnqueueEntry(cancelled, 1), new JobQueue.EnqueueEntry(stopped, 1)), null);
      var claims = queue.pollPending(2);
      var cancelledClaim = claims.stream().filter(claim -> claim.path().equals(cancelled)).findFirst().orElseThrow();
      var stoppedClaim = claims.stream().filter(claim -> claim.path().equals(stopped)).findFirst().orElseThrow();
      queue.deferClaim(stoppedClaim, outcome(IngestionOutcomeClass.DEFERRED_POLICY, IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT), null, 1);
      assertTrue(seen.isEmpty());
      queue.markDone(cancelled, outcome(IngestionOutcomeClass.SKIPPED_POLICY, IngestionRetryPolicy.NONE));
      assertFalse(queue.markClaimDone(cancelledClaim, outcome(IngestionOutcomeClass.SUCCESS_FULL, IngestionRetryPolicy.NONE), null));
      var stoppedAgain = queue.pollPending(1).getFirst();
      queue.closeRecordedWalkEnumeration("walk", walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.CANCELLED);
      queue.returnUnfinishedClaims(List.of(stoppedAgain));
      assertEquals(List.of("SKIPPED_POLICY", "SKIPPED_POLICY"), seen);
      var sealed = queue.trySealRecordedWalk("walk");
      assertEquals(0, sealed.completedUnits(), "skips do not count as indexed successes");
      assertEquals(2, tools.jackson.databind.json.JsonMapper.builder().build()
          .readTree(sealed.receiptJson()).path("currentSkippedUnits").asInt());
    }
  }

  @ParameterizedTest @ValueSource(strings={"RETRY_WITH_BACKOFF", "RETRY_UNTIL_AVAILABLE"})
  void retryMetricsExcludeAttemptsAndIncludeExhaustionOnlyOnce(String policy) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("retry-metrics.db"))) {
      queue.open();
      var seen = new ArrayList<String>();
      queue.setOutcomeObserver(seen::add);
      Path path = temp.resolve("file");
      queue.enqueue(List.of(path));
      var retry = outcome(IngestionOutcomeClass.IO_FAILED, IngestionRetryPolicy.valueOf(policy));
      var first = queue.pollPending(1).getFirst();
      assertTrue(queue.markClaimFailed(first, retry, null));
      assertFalse(queue.markClaimFailed(first, retry, null), "an exact claim can finish only once");
      assertTrue(seen.isEmpty());
      try (var update = JobCountsTest.connection(queue).prepareStatement(
          "UPDATE jobs SET first_failed_at=?,retry_after=0")) {
        update.setLong(1, System.currentTimeMillis() - java.time.Duration.ofDays(8).toMillis());
        update.executeUpdate();
      }
      var next = queue.pollPending(1).getFirst();
      assertTrue(queue.markClaimFailed(next, retry, null));
      assertFalse(queue.markClaimFailed(next, retry, null));
      boolean exhausted = policy.equals("RETRY_WITH_BACKOFF");
      assertEquals(exhausted ? List.of("IO_FAILED") : List.of(), seen);
      assertEquals(exhausted ? "RETRY_EXHAUSTED" : "PENDING",
          queue.watcherReconciliationRow(path.toString()).get("state"));
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"IO_FAILED,NONE", "IO_FAILED,RETRY_WITH_BACKOFF",
      "IO_FAILED,RETRY_UNTIL_AVAILABLE", "PARSER_TIMEOUT,RETRY_WITH_BACKOFF"})
  void maintenanceNoopPreservesEverySavedFieldAndActualEnqueueCounters(String type, String policy) throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve(policy+".db"))) {
      queue.open(); Path file=java.nio.file.Files.writeString(temp.resolve("file"), "unchanged content");
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file,17,new JobQueue.EnqueueProvenance("user","rpc"),null,temp)),"C","scan");
      queue.markFailed(file,outcome(IngestionOutcomeClass.valueOf(type),IngestionRetryPolicy.valueOf(policy)));
      var before=queue.watcherReconciliationRow(file.toString());
      long admitted=queue.throughputMeters().enqueuedTotal();
      assertEquals(0,queue.enqueueMaintenanceScanEntries(
          List.of(new JobQueue.EnqueueEntry(file,17).withinRoot(temp)),"C","maintenance"));
      assertEquals(before,queue.watcherReconciliationRow(file.toString()));
      assertEquals(admitted,queue.throughputMeters().enqueuedTotal());
      assertEquals(1,queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file,99)),"other","explicit"));
      assertEquals("PENDING",queue.watcherReconciliationRow(file.toString()).get("state"));
      assertEquals("0",queue.watcherReconciliationRow(file.toString()).get("attempts"));
    }
  }
}

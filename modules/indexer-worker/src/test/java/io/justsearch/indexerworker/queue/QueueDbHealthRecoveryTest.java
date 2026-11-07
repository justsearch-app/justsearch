/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Accepted C8D1-D6: real queue executions, with faults confined to owned JDBC delegates. */
final class QueueDbHealthRecoveryTest {
  @TempDir Path temp;

  private static Object field(SqliteJobQueue queue, String name) throws Exception {
    Field field = SqliteJobQueue.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(queue);
  }

  private static void field(SqliteJobQueue queue, String name, Object value) throws Exception {
    Field field = SqliteJobQueue.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(queue, value);
  }

  private static Connection actual(SqliteJobQueue queue) throws Exception {
    return (Connection) field(queue, "connection");
  }

  private static void sql(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) { statement.execute(sql); }
  }

  private static final class ReadFault {
    private Predicate<String> select = ignored -> false;
    private String shape = "sql";
    private final AtomicBoolean active = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    void fail(Predicate<String> select, String shape) {
      this.select = select;
      this.shape = shape;
      active.set(true);
    }

    void restore() { active.set(false); release.countDown(); }

    Connection connection(Connection actual) throws SQLException {
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doAnswer(call -> {
        String sql = call.getArgument(0);
        PreparedStatement statement = actual.prepareStatement(sql);
        PreparedStatement wrapped = mock(PreparedStatement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
        doAnswer(read -> rows(sql, statement::executeQuery)).when(wrapped).executeQuery();
        doAnswer(close -> {
          statement.close();
          if (selected(sql) && shape.equals("statementClose")) throw new SQLException("selected statement close");
          return null;
        }).when(wrapped).close();
        return wrapped;
      }).when(observed).prepareStatement(anyString());
      doAnswer(call -> {
        Statement statement = actual.createStatement();
        Statement wrapped = mock(Statement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
        AtomicReference<String> selectedSql = new AtomicReference<>("");
        doAnswer(read -> {
          String sql = read.getArgument(0);
          selectedSql.set(sql);
          return rows(sql, () -> statement.executeQuery(sql));
        }).when(wrapped).executeQuery(anyString());
        doAnswer(close -> {
          statement.close();
          if (selected(selectedSql.get()) && shape.equals("statementClose")) throw new SQLException("selected statement close");
          return null;
        }).when(wrapped).close();
        return wrapped;
      }).when(observed).createStatement();
      return observed;
    }

    private boolean selected(String sql) { return active.get() && select.test(sql); }

    private ResultSet rows(String sql, SqlRows read) throws Exception {
      if (!selected(sql)) return read.run();
      if (shape.equals("sql")) throw new SQLException("selected database read");
      if (shape.equals("runtime")) throw new IllegalStateException("selected quick-check runtime");
      if (shape.equals("blocked")) {
        entered.countDown();
        assertTrue(release.await(15, TimeUnit.SECONDS), "owned read release deadline");
      }
      ResultSet actual = read.run();
      ResultSet rows = mock(ResultSet.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      switch (shape) {
        case "empty" -> doReturn(false).when(rows).next();
        case "mixed" -> {
          doReturn(true, true, false).when(rows).next();
          doReturn("oK", "not ok").when(rows).getString(1);
        }
        case "bad" -> {
          doReturn(true, false).when(rows).next();
          doReturn("not ok").when(rows).getString(1);
        }
        case "unknown" -> doReturn("UNKNOWN_STATE").when(rows).getString(1);
        case "busy" -> doReturn(1).when(rows).getInt(1);
        case "resultClose" -> doAnswer(close -> {
          actual.close();
          throw new SQLException("selected result close");
        }).when(rows).close();
        default -> { /* The real selected query still supplies all rows. */ }
      }
      return rows;
    }
  }

  @FunctionalInterface private interface SqlRows { ResultSet run() throws SQLException; }

  private static Predicate<String> query(String operation) {
    return sql -> switch (operation) {
      case "depth" -> sql.contains("WHERE state IN ('PENDING', 'PROCESSING')");
      case "completed" -> sql.contains("WHERE state = 'DONE'");
      case "states", "strictStates" -> sql.contains("pending_ready_count");
      case "pendingBytes" -> sql.contains("AS unknown_size_jobs");
      case "bufferDepth" -> sql.contains("COUNT(*) FROM switch_buffer");
      case "bufferList", "strictBufferList", "generationBufferList" -> sql.contains("FROM switch_buffer ORDER BY") || sql.contains("FROM switch_buffer WHERE generation = ? ORDER BY");
      case "failureCount" -> sql.contains("SUM(n)") && sql.contains("'RETRY_EXHAUSTED'");
      case "lastFailure" -> sql.contains("ORDER BY last_updated DESC LIMIT 1");
      case "nextRetry" -> sql.contains("MIN(retry_after)");
      case "recent" -> sql.contains("FROM ingestion_ledger") && !sql.contains("GROUP BY");
      case "outcomes" -> sql.contains("GROUP BY outcome_class");
      case "unowned" -> sql.contains("ingestion_root IS NULL");
      case "failedList", "prefixFailedList" -> sql.contains("FROM jobs WHERE") && sql.contains("ORDER BY last_updated DESC LIMIT ?");
      case "prefix" -> sql.contains("GROUP BY shown_state");
      case "expectedCollection" -> sql.contains("SELECT collection FROM jobs");
      case "sourceFileWork" -> sql.contains("SELECT state, content_hash, last_outcome_class");
      case "readiness" -> sql.contains("SELECT EXISTS(SELECT 1 FROM jobs");
      case "nonterminalPrefix", "nonterminalCollection" -> sql.contains("SELECT 1 FROM jobs WHERE state IN");
      default -> throw new AssertionError(operation);
    };
  }

  private Object read(SqliteJobQueue queue, String operation) {
    return switch (operation) {
      case "depth" -> queue.queueDepth();
      case "completed" -> queue.completedCount();
      case "states" -> queue.jobStateCounts();
      case "strictStates" -> queue.jobStateCountsStrict();
      case "pendingBytes" -> queue.pendingBytes();
      case "bufferDepth" -> queue.switchBufferDepth();
      case "bufferList" -> queue.listSwitchBufferOps();
      case "strictBufferList" -> queue.listSwitchBufferOpsStrict();
      case "generationBufferList" -> queue.listSwitchBufferOpsStrictForGeneration("generation");
      case "failureCount", "lastFailure", "nextRetry" -> queue.failureSummary();
      case "recent" -> queue.recentIngestionEvents(100);
      case "outcomes" -> queue.ingestionOutcomeSummary(0);
      case "unowned" -> queue.unownedFailureCount();
      case "failedList" -> queue.listFailedJobs(100);
      case "prefixFailedList" -> queue.listFailedJobsByPathPrefix(temp.toString(), 100);
      case "prefix" -> queue.countByPathPrefix(temp.toString());
      case "expectedCollection" -> queue.matchesExpectedCollection(temp.resolve("file"), null);
      case "sourceFileWork" -> queue.sourceFileWork(temp.resolve("file").toString(), "a".repeat(64));
      case "readiness" -> queue.hasPendingIngestWork();
      case "nonterminalPrefix" -> queue.hasNonterminalJobsByPathPrefixStrict(temp.toString());
      case "nonterminalCollection" -> queue.hasNonterminalJobsByCollectionStrict("default");
      default -> throw new AssertionError(operation);
    };
  }

  private void failure(SqliteJobQueue queue, String operation) {
    switch (operation) {
      case "pendingBytes" -> assertEquals(JobQueue.PendingBytes.UNAVAILABLE, read(queue, operation));
      case "prefixFailedList" -> assertEquals(List.of(), read(queue, operation));
      case "readiness" -> assertEquals(false, read(queue, operation));
      default -> assertThrows(IllegalStateException.class, () -> read(queue, operation));
    }
    assertFalse(queue.queueDbHealthSnapshot().healthy());
  }

  @ParameterizedTest
  @ValueSource(strings={"depth", "completed", "states", "strictStates", "pendingBytes", "bufferDepth", "bufferList", "strictBufferList", "generationBufferList", "failureCount", "lastFailure", "nextRetry", "recent", "outcomes", "unowned", "failedList", "prefixFailedList", "prefix", "expectedCollection", "sourceFileWork", "readiness", "nonterminalPrefix", "nonterminalCollection"})
  void matchingCompleteReadRecoversRepeatedFailuresAndPreservesHistory(String operation) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("reads.db"))) {
      queue.open();
      queue.enqueue(List.of(temp.resolve("file")));
      assertTrue(queue.putSwitchBuffer("file", "UPSERT", "payload"));
      Connection actual = actual(queue);
      ReadFault fault = new ReadFault();
      field(queue, "connection", fault.connection(actual));
      for (int cycle = 0; cycle < 2; cycle++) {
        fault.fail(query(operation), "sql");
        failure(queue, operation);
        failure(queue, operation);
        long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
        fault.restore();
        assertTrue(queue.performIntegrityCheck());
        assertFalse(queue.queueDbHealthSnapshot().healthy(), "integrity cannot certify a read");
        assertNotNull(read(queue, operation));
        assertTrue(queue.queueDbHealthSnapshot().healthy(), "complete matching read recovers");
        assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
      }
    }
  }

  @ParameterizedTest @ValueSource(booleans={false,true})
  void distinctDiagnosticsKindsRecoverInBothOrders(boolean reverse) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("two.db"))) {
      queue.open();
      Connection actual = actual(queue);
      ReadFault fault = new ReadFault();
      field(queue, "connection", fault.connection(actual));
      fault.fail(sql -> sql.contains("FROM ingestion_ledger"), "sql");
      failure(queue, "recent"); failure(queue, "outcomes");
      fault.restore();
      assertEquals(0, queue.queueDepth());
      assertTrue(queue.performIntegrityCheck());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      read(queue, reverse ? "outcomes" : "recent");
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "one distinct diagnostics kind remains");
      read(queue, reverse ? "recent" : "outcomes");
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @Test void delegatedReadKindsAreDistinctAndAliasesAgree() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("delegated.db"))) {
      queue.open(); ReadFault fault = new ReadFault();
      field(queue, "connection", fault.connection(actual(queue)));
      for (String operation : List.of("bufferDepth", "states", "pendingBytes", "bufferList")) {
        fault.fail(query(operation), "sql"); failure(queue, operation);
      }
      fault.restore();
      assertEquals(0, queue.jobStateCountsStrict().pendingCount());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertEquals(List.of(), queue.listSwitchBufferOpsStrictForGeneration("empty"));
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertEquals(0, queue.switchBufferDepth());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertEquals(JobQueue.PendingBytes.EMPTY, queue.pendingBytes());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @ParameterizedTest @ValueSource(strings={"depth", "completed", "states", "pendingBytes", "bufferDepth", "unowned", "failureCount", "nextRetry", "readiness"})
  void absentRequiredAggregateRowsDoNotRecover(String operation) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("absent.db"))) {
      queue.open(); ReadFault fault = new ReadFault();
      field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(query(operation), "sql"); failure(queue, operation);
      fault.fail(query(operation), "empty");
      read(queue, operation);
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "fallback is not a completed observation");
      fault.restore(); read(queue, operation);
      assertTrue(queue.queueDbHealthSnapshot().healthy(), "actual zero is complete");
    }
  }

  @ParameterizedTest @ValueSource(strings={"resultClose", "statementClose"})
  void successfulRowsWithFailedCleanupCannotRecover(String shape) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("cleanup.db"))) {
      queue.open(); ReadFault fault = new ReadFault();
      field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(query("depth"), "sql"); failure(queue, "depth");
      fault.fail(query("depth"), shape); failure(queue, "depth");
      fault.restore(); assertEquals(0, queue.queueDepth());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @ParameterizedTest @ValueSource(strings={"sql", "runtime", "empty", "mixed", "bad", "resultClose", "statementClose"})
  void onlyActualCompleteQuickCheckRecoversIntegrity(String shape) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("integrity.db"))) {
      queue.open(); ReadFault fault = new ReadFault();
      field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(sql -> sql.equals("PRAGMA quick_check"), shape);
      if (shape.equals("runtime")) assertThrows(IllegalStateException.class, queue::performIntegrityCheck);
      else assertThrows(SQLException.class, queue::performIntegrityCheck);
      assertFalse(queue.queueDbHealthSnapshot().lastQuickCheckOk());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      fault.restore(); assertEquals(0, queue.queueDepth());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertTrue(queue.performIntegrityCheck(), "real restored SQLite PRAGMA must pass");
      assertTrue(queue.queueDbHealthSnapshot().lastQuickCheckOk());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
    }
  }

  @Test void invalidAndPartialPrefixResultsCannotRecover() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("prefix.db"))) {
      queue.open(); queue.enqueue(List.of(temp.resolve("file")));
      ReadFault fault = new ReadFault(); field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(query("prefix"), "sql"); failure(queue, "prefix");
      fault.restore(); assertEquals(0, queue.countByPathPrefix("").pendingCount());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      fault.fail(query("prefix"), "unknown");
      assertEquals(0, queue.countByPathPrefix(temp.toString()).pendingCount());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      fault.restore(); assertEquals(1, queue.countByPathPrefix(temp.toString()).pendingCount());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @Test void snapshotRemainsNonblockingDuringRealOwnedRead() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("snapshot.db"))) {
      queue.open(); ReadFault fault = new ReadFault(); field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(query("depth"), "sql"); failure(queue, "depth");
      fault.fail(query("depth"), "blocked");
      AtomicReference<Throwable> failed = new AtomicReference<>();
      Thread reader = new Thread(() -> { try { queue.queueDepth(); } catch (Throwable failure) { failed.set(failure); } }, "c8-owned-read");
      reader.start();
      try {
        assertTrue(fault.entered.await(15, TimeUnit.SECONDS));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertFalse(queue.queueDbHealthSnapshot().healthy()));
      } finally { fault.restore(); reader.join(15_000); }
      assertFalse(reader.isAlive()); assertNull(failed.get());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @Test void maintenanceAdmissionRecoveryRequiresItsActualModeAndTransaction() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("maintenance.db"))) {
      queue.open(); Connection actual = actual(queue); Path path = temp.resolve("file");
      sql(actual, "CREATE TRIGGER fail_admission BEFORE INSERT ON jobs BEGIN SELECT RAISE(ABORT,'admission fault'); END");
      assertThrows(IllegalStateException.class, () -> queue.enqueueMaintenanceScanEntries(List.of(new JobQueue.EnqueueEntry(path, 1)), null, "scan"));
      assertEquals(0, queue.queueDepth());
      sql(actual, "DROP TRIGGER fail_admission");
      assertEquals(1, queue.enqueue(List.of(path)));
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "ordinary admission cannot recover maintenance mode");
      assertEquals(0, queue.enqueueMaintenanceScanEntries(List.of(), null, "scan"));
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertEquals(1, queue.enqueueMaintenanceScanEntries(List.of(new JobQueue.EnqueueEntry(path, 1)), null, "scan"));
      assertTrue(queue.queueDbHealthSnapshot().healthy());
      assertEquals(1, queue.queueDepth());
    }
  }

  @Test void conditionalReplayAliasesRecoverOnlyAfterNonemptyCompletedRemoval() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("replay.db"))) {
      queue.open(); assertTrue(queue.putSwitchBufferForGeneration("g", "key", "UPSERT", "payload"));
      var selected = queue.listSwitchBufferOpsStrictForGeneration("g"); Connection actual = actual(queue);
      sql(actual, "CREATE TRIGGER fail_replay BEFORE DELETE ON switch_buffer BEGIN SELECT RAISE(ABORT,'replay fault'); END");
      assertThrows(IllegalStateException.class, () -> queue.removeReplayedSwitchBufferOps(selected));
      assertEquals(selected, queue.listSwitchBufferOpsStrictForGeneration("g"));
      sql(actual, "DROP TRIGGER fail_replay");
      assertEquals(0, queue.removeReplayedSwitchBufferOpsForGeneration("g", List.of()));
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertEquals(1, queue.removeReplayedSwitchBufferOpsForGeneration("g", selected));
      assertTrue(queue.queueDbHealthSnapshot().healthy());
      assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  @Test void heartbeatNeedsAtLeastOneActuallyIssuedUpdate() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("heartbeat.db"))) {
      queue.open(); Path path = temp.resolve("file"); queue.enqueue(List.of(path));
      var claim = queue.pollPending(1).getFirst(); Connection actual = actual(queue);
      sql(actual, "CREATE TRIGGER fail_heartbeat BEFORE UPDATE ON jobs BEGIN SELECT RAISE(ABORT,'heartbeat fault'); END");
      queue.heartbeatProcessing(); assertFalse(queue.queueDbHealthSnapshot().healthy());
      sql(actual, "DROP TRIGGER fail_heartbeat");
      assertTrue(queue.markClaimDone(claim, IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY, "TEST", IngestionRetryPolicy.NONE), null));
      queue.heartbeatProcessing(); assertFalse(queue.queueDbHealthSnapshot().healthy(), "empty heartbeat does no matching update");
      queue.enqueue(List.of(path)); var current = queue.pollPending(1).getFirst();
      queue.heartbeatProcessing(); assertTrue(queue.queueDbHealthSnapshot().healthy());
      assertTrue(queue.ownsClaimForPublication(current)); assertFalse(queue.ownsClaimForPublication(claim));
    }
  }

  @Test void walCheckpointBusyIsNeitherSuccessNorANewFailure() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("wal.db"))) {
      queue.open(); queue.enqueue(List.of(temp.resolve("file")));
      ReadFault fault = new ReadFault(); field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(sql -> sql.startsWith("PRAGMA wal_checkpoint"), "sql");
      assertFalse(queue.checkpointWal()); assertFalse(queue.queueDbHealthSnapshot().healthy());
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      fault.fail(sql -> sql.startsWith("PRAGMA wal_checkpoint"), "busy");
      assertFalse(queue.checkpointWal()); assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
      fault.restore(); assertTrue(queue.checkpointWal()); assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @Test void actualSuccessfulCloseThenFullOpenRecoversOnlyConnectionHistory() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("close.db"))) {
      queue.open(); Connection actual = actual(queue);
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      AtomicBoolean fail = new AtomicBoolean(true);
      doAnswer(call -> { if (fail.get()) throw new SQLException("owned close fault"); actual.close(); return null; }).when(observed).close();
      field(queue, "connection", observed);
      assertThrows(java.io.IOException.class, queue::close);
      assertThrows(SQLException.class, queue::open);
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      fail.set(false); queue.close(); assertTrue(actual.isClosed());
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "closed is not a usable queue");
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      queue.openWithIntegrityCheck(); assertTrue(queue.queueDbHealthSnapshot().healthy());
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
    }
  }

  @Test void sameObjectReopenRetainsReadDebtButANewObjectStartsFresh() throws Exception {
    Path db = temp.resolve("history.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); ReadFault fault = new ReadFault(); field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(query("depth"), "sql"); failure(queue, "depth"); fault.restore();
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      queue.close(); queue.open();
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "same object retains unrelated read history");
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
      assertEquals(0, queue.queueDepth()); assertTrue(queue.queueDbHealthSnapshot().healthy());
      fault = new ReadFault(); field(queue, "connection", fault.connection(actual(queue)));
      fault.fail(query("completed"), "sql"); failure(queue, "completed"); fault.restore();
    }
    try (var fresh = new SqliteJobQueue(db)) {
      fresh.open(); assertTrue(fresh.queueDbHealthSnapshot().healthy());
      assertEquals(0, fresh.queueDbHealthSnapshot().lastDbErrorAtMs());
      assertEquals(0, fresh.queueDbHealthSnapshot().lastBackupAtMs());
      assertTrue(fresh.queueDbHealthSnapshot().lastQuickCheckOk());
    }
  }

  @Test void transactionResetPoisonNeedsActualCloseAndFreshUsableOpen() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("poison.db"))) {
      queue.open(); Connection actual = actual(queue);
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doThrow(new SQLException("owned autocommit restoration fault")).when(observed).setAutoCommit(true);
      field(queue, "connection", observed);
      assertEquals(0, queue.enqueue(List.of(temp.resolve("committed"))));
      assertTrue(actual.isClosed(), "existing poison handling closed the retained actual owner");
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      assertThrows(IllegalStateException.class, queue::performIntegrityCheck);
      assertThrows(SQLException.class, queue::open);
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      queue.close();
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "cleanup alone is not a usable queue");
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      queue.open(); assertTrue(queue.queueDbHealthSnapshot().healthy());
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
      assertEquals(1, queue.queueDepth(), "confirmed commit is preserved despite failed reset");
    }
  }

  @ParameterizedTest @ValueSource(strings={"same", "newer", "nested", "pending", "replacePending", "replaceNewer", "replaceComplete"})
  void delayedMutationCompletionRequiresOriginalFailureAndActualOwners(String scenario) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("owner.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open(); Connection original = actual(queue); IndexingJobsChangeStream prior = queue.changeStream();
      ReentrantLock ownerLock = (ReentrantLock) field(queue, "lock");
      ReadFault projectionFault = new ReadFault();
      Connection projectionOwner = projectionFault.connection(original);
      var ensure = SqliteJobQueue.class.getDeclaredMethod("ensureOpen"); ensure.setAccessible(true);
      prior.close();
      IndexingJobsChangeStream stream = new IndexingJobsChangeStream(projectionOwner, ownerLock, () -> {
        try { ensure.invoke(queue); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
      });
      field(queue, "changeStream", stream);
      stream.subscribe(delta -> {});
      var walk = queue.beginRecordedWalk("owned-walk", "a".repeat(64), true);
      Path path = temp.resolve("member");
      queue.enqueueRecordedEntries("owned-walk", walk.enumerationEpoch(), List.of(new JobQueue.EnqueueEntry(path, 1)), null);
      var before = queue.recordedWalk("owned-walk").orElseThrow();
      sql(original, "CREATE TRIGGER fail_clear BEFORE INSERT ON ingestion_ledger BEGIN SELECT RAISE(ABORT,'clear fault'); END");
      assertThrows(IllegalStateException.class, queue::clearAll);
      assertEquals(before, queue.recordedWalk("owned-walk").orElseThrow());
      assertTrue(queue.recentIngestionEvents(100).isEmpty());
      sql(original, "DROP TRIGGER fail_clear");
      if (scenario.equals("pending") || scenario.equals("replacePending")) projectionFault.fail(sql -> sql.contains("FROM jobs"), "sql");
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicBoolean first = new AtomicBoolean(true);
      AtomicReference<Throwable> failed = new AtomicReference<>();
      AtomicReference<Integer> result = new AtomicReference<>();
      AtomicReference<Thread> callbackThread = new AtomicReference<>();
      AtomicBoolean nestedFailed = new AtomicBoolean();
      var subscription = queue.subscribeRecordedWalks(key -> {
        if (first.getAndSet(false)) {
          assertEquals("owned-walk", key);
          callbackThread.set(Thread.currentThread());
          if (scenario.equals("nested")) {
            try {
              sql(original, "CREATE TRIGGER fail_nested BEFORE UPDATE ON jobs BEGIN SELECT RAISE(ABORT,'nested clear fault'); END");
              try {
                assertThrows(IllegalStateException.class, queue::clearAll);
                assertFalse(queue.queueDbHealthSnapshot().healthy());
                nestedFailed.set(true);
              } finally { sql(original, "DROP TRIGGER fail_nested"); }
            } catch (SQLException failure) { throw new AssertionError(failure); }
          }
          entered.countDown();
          try { assertTrue(release.await(15, TimeUnit.SECONDS), "owned callback release deadline"); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
      });
      Thread retry = new Thread(() -> { try { result.set(queue.clearAll()); } catch (Throwable failure) { failed.set(failure); } }, "c8-owned-clear");
      retry.start();
      try {
        assertTrue(entered.await(15, TimeUnit.SECONDS), "real committed recorded-walk callback entered");
        assertFalse(queue.queueDbHealthSnapshot().healthy());
        assertSame(retry, callbackThread.get(), "real callback executes on the outer mutation thread");
        if (scenario.equals("nested")) assertTrue(nestedFailed.get(), "same-thread nested call really failed before outer completion");
        if (scenario.equals("pending") || scenario.equals("replacePending")) {
          Field resync = IndexingJobsChangeStream.class.getDeclaredField("resyncRequired"); resync.setAccessible(true);
          assertTrue((boolean) resync.get(stream), "actual old stream retains failed materialization/resync debt");
        }
        boolean replace = scenario.startsWith("replace");
        if (!replace) {
          assertSame(original, actual(queue)); assertSame(stream, queue.changeStream());
        }
        if (replace) {
          queue.close(); assertTrue(original.isClosed()); queue.open();
          assertNotSame(original, actual(queue)); assertNotSame(stream, queue.changeStream());
          Field resync = IndexingJobsChangeStream.class.getDeclaredField("resyncRequired"); resync.setAccessible(true);
          assertFalse((boolean) resync.get(queue.changeStream()), "fresh stream has no old projection debt");
          assertFalse(queue.queueDbHealthSnapshot().healthy(), "fresh owner cannot certify old mutation");
        }
        if (scenario.equals("newer") || scenario.equals("replaceNewer")) {
          Connection current = actual(queue);
          sql(current, "CREATE TRIGGER fail_newer BEFORE UPDATE ON jobs BEGIN SELECT RAISE(ABORT,'newer clear fault'); END");
          assertThrows(IllegalStateException.class, queue::clearAll);
          sql(current, "DROP TRIGGER fail_newer");
        }
      } finally {
        release.countDown(); retry.join(15_000); subscription.close(); projectionFault.restore();
      }
      assertFalse(retry.isAlive(), "owned retry joined"); assertNull(failed.get()); assertEquals(1, result.get());
      if (scenario.equals("same")) assertTrue(queue.queueDbHealthSnapshot().healthy(), "same-owner complete neighbor recovers");
      else {
        assertFalse(queue.queueDbHealthSnapshot().healthy(), "older delayed completion cannot certify new identity/owner");
        long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
        assertEquals(1, queue.clearAll()); assertTrue(queue.queueDbHealthSnapshot().healthy());
        if (scenario.equals("pending")) {
          assertSame(original, actual(queue)); assertSame(stream, queue.changeStream());
          Field resync = IndexingJobsChangeStream.class.getDeclaredField("resyncRequired"); resync.setAccessible(true);
          assertFalse((boolean) resync.get(stream), "later real same-owner mutation completed resync");
        }
        assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
      }
      assertEquals(1, queue.recentIngestionEvents(100).size(), "administrative receipt stays idempotent");
      assertEquals(0, queue.recordedWalk("owned-walk").orElseThrow().completedUnits());
      assertEquals(0, queue.queueDepth());
    }
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** C8P2/P3 scalar values come from known rows, independently of health-control flags. */
final class QueueScalarObservationTest {
  @TempDir Path temp;

  private static Connection connection(SqliteJobQueue queue) throws Exception {
    var field = SqliteJobQueue.class.getDeclaredField("connection");
    field.setAccessible(true);
    return (Connection) field.get(queue);
  }

  private static void connection(SqliteJobQueue queue, Connection observed) throws Exception {
    var field = SqliteJobQueue.class.getDeclaredField("connection");
    field.setAccessible(true);
    field.set(queue, observed);
  }

  private Path path(String name) { return temp.resolve(name); }

  private void state(Connection connection, String name, String state, String revision, String root)
      throws SQLException {
    try (var update = connection.prepareStatement(
        "UPDATE jobs SET state=?,unit_revision=?,ingestion_root=? WHERE path=?")) {
      update.setString(1, state); update.setString(2, revision);
      update.setString(3, root); update.setString(4, path(name).toString());
      assertEquals(1, update.executeUpdate(), "fixture owns exactly this row");
    }
  }

  private void matchingAndExcludedRows(SqliteJobQueue queue) throws Exception {
    assertEquals(8, queue.enqueue(List.of(path("failed"), path("exhausted"), path("ordinary"),
        path("rooted"), path("pending"), path("processing"), path("done"), path("rootedExhausted"))));
    Connection actual = connection(queue);
    state(actual, "failed", "FAILED", "release030/test", null);
    state(actual, "exhausted", "RETRY_EXHAUSTED", "release030/test", null);
    state(actual, "ordinary", "FAILED", "new/test", null);
    state(actual, "rooted", "FAILED", "release030/test", "owned");
    state(actual, "pending", "PENDING", "release030/test", null);
    state(actual, "processing", "PROCESSING", "release030/test", null);
    state(actual, "done", "DONE", "release030/test", null);
    state(actual, "rootedExhausted", "RETRY_EXHAUSTED", "release030/test", "owned");
  }

  private void retry(Connection actual, String name, long deadline) throws SQLException {
    try (var update = actual.prepareStatement("UPDATE jobs SET retry_after=? WHERE path=?")) {
      update.setLong(1, deadline); update.setString(2, path(name).toString());
      assertEquals(1, update.executeUpdate());
    }
  }

  @Test void populatedLegacyTerminalCountPreserved() throws Exception {
    try (var queue = new SqliteJobQueue(path("count.db"))) {
      queue.open(); matchingAndExcludedRows(queue);
      assertEquals(2, queue.unownedFailureCount(), "only unrooted legacy FAILED and RETRY_EXHAUSTED match");
      state(connection(queue), "failed", "DONE", "release030/test", null);
      assertEquals(1, queue.unownedFailureCount(), "the remaining RETRY_EXHAUSTED row still matches");
      state(connection(queue), "exhausted", "RETRY_EXHAUSTED", "release030/test", "owned");
      assertEquals(0, queue.unownedFailureCount());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @Test void emptyAndNonmatchingLegacyCountRemainZero() throws Exception {
    try (var queue = new SqliteJobQueue(path("zero.db"))) {
      queue.open(); assertEquals(0, queue.unownedFailureCount());
      queue.enqueue(List.of(path("ordinary"), path("rooted"), path("pending"), path("processing")));
      Connection actual = connection(queue);
      state(actual, "ordinary", "FAILED", "new/test", null);
      state(actual, "rooted", "RETRY_EXHAUSTED", "release030/test", "owned");
      state(actual, "pending", "PENDING", "release030/test", null);
      state(actual, "processing", "PROCESSING", "release030/test", null);
      assertEquals(0, queue.unownedFailureCount());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @Test void readyPendingWorkRemainsVisible() throws Exception {
    try (var queue = new SqliteJobQueue(path("ready.db"))) {
      queue.open(); assertEquals(1, queue.enqueue(List.of(path("ready"))));
      assertTrue(queue.hasPendingIngestWork(), "one actual pending row without a retry delay is ready");
      assertTrue(queue.queueDbHealthSnapshot().healthy());
    }
  }

  @Test void emptyFutureMixedAndDueReadinessValuesArePreserved() throws Exception {
    try (var queue = new SqliteJobQueue(path("readiness.db"))) {
      queue.open(); assertFalse(queue.hasPendingIngestWork());
      queue.enqueue(List.of(path("later"))); retry(connection(queue), "later", Long.MAX_VALUE);
      assertFalse(queue.hasPendingIngestWork(), "future retry alone is not runnable");
      queue.enqueue(List.of(path("ready")));
      assertTrue(queue.hasPendingIngestWork(), "mixed ready and delayed rows are runnable");
      state(connection(queue), "ready", "DONE", "ordinary", null);
      assertFalse(queue.hasPendingIngestWork(), "completed row does not make delayed work ready");
      retry(connection(queue), "later", 0);
      assertTrue(queue.hasPendingIngestWork(), "elapsed retry deadline is ready without another admission");
      state(connection(queue), "later", "PROCESSING", "ordinary", null);
      assertFalse(queue.hasPendingIngestWork(), "processing is not pending readiness");
    }
  }

  private static final class ScalarFaults {
    String count = "normal";
    String readiness = "normal";

    private static boolean countQuery(String sql) {
      return sql.startsWith("SELECT COUNT(*) FROM jobs WHERE ingestion_root IS NULL");
    }

    private static boolean readinessQuery(String sql) { return sql.contains("SELECT EXISTS(SELECT 1 FROM jobs"); }

    private static ResultSet rows(ResultSet actual, String shape) throws SQLException {
      if (shape.equals("empty")) {
        ResultSet observed = mock(ResultSet.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
        doReturn(false).when(observed).next();
        return observed;
      }
      return actual;
    }

    Connection observe(Connection actual) throws SQLException {
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doAnswer(call -> {
        Statement statement = actual.createStatement();
        Statement wrapped = mock(Statement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
        doAnswer(read -> {
          String sql = read.getArgument(0);
          if (!countQuery(sql)) return statement.executeQuery(sql);
          if (count.equals("sql")) throw new SQLException("owned legacy count unavailable");
          return rows(statement.executeQuery(sql), count);
        }).when(wrapped).executeQuery(anyString());
        return wrapped;
      }).when(observed).createStatement();
      doAnswer(call -> {
        String sql = call.getArgument(0);
        if (!readinessQuery(sql)) return actual.prepareStatement(sql);
        if (readiness.equals("sql")) throw new SQLException("owned readiness unavailable");
        PreparedStatement statement = actual.prepareStatement(sql);
        PreparedStatement wrapped = mock(PreparedStatement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
        doAnswer(read -> rows(statement.executeQuery(), readiness)).when(wrapped).executeQuery();
        return wrapped;
      }).when(observed).prepareStatement(anyString());
      return observed;
    }
  }

  @Test void unavailableAndAbsentRowsPreserveScalarResults() throws Exception {
    try (var queue = new SqliteJobQueue(path("unavailable.db"))) {
      queue.open(); matchingAndExcludedRows(queue);
      ScalarFaults faults = new ScalarFaults(); connection(queue, faults.observe(connection(queue)));
      faults.count = "sql"; faults.readiness = "sql";
      assertThrows(IllegalStateException.class, queue::unownedFailureCount);
      assertFalse(queue.hasPendingIngestWork(), "readiness keeps its existing SQL-error false fallback");
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      faults.count = "empty"; faults.readiness = "empty";
      assertEquals(0, queue.unownedFailureCount(), "missing aggregate keeps the defensive zero result");
      assertFalse(queue.hasPendingIngestWork(), "missing EXISTS row keeps the defensive false result");
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "absent expected rows certify no recovery");
      faults.count = "normal"; faults.readiness = "normal";
      assertEquals(2, queue.unownedFailureCount());
      assertTrue(queue.hasPendingIngestWork());
    }
  }

  @ParameterizedTest @ValueSource(booleans={false,true})
  void matchingRecoveryPreservesValuesAndOtherKindDebt(boolean reverse) throws Exception {
    try (var queue = new SqliteJobQueue(path("recovery.db"))) {
      queue.open(); matchingAndExcludedRows(queue);
      ScalarFaults faults = new ScalarFaults(); connection(queue, faults.observe(connection(queue)));
      faults.count = "sql"; faults.readiness = "sql";
      assertThrows(IllegalStateException.class, queue::unownedFailureCount);
      assertFalse(queue.hasPendingIngestWork());
      long errorAt = queue.queueDbHealthSnapshot().lastDbErrorAtMs();
      faults.count = "empty"; faults.readiness = "empty";
      assertEquals(0, queue.unownedFailureCount()); assertFalse(queue.hasPendingIngestWork());
      assertFalse(queue.queueDbHealthSnapshot().healthy());
      faults.count = "normal"; faults.readiness = "normal";
      if (reverse) assertTrue(queue.hasPendingIngestWork());
      else assertEquals(2, queue.unownedFailureCount());
      assertFalse(queue.queueDbHealthSnapshot().healthy(), "the other scalar operation is still unresolved");
      if (reverse) assertEquals(2, queue.unownedFailureCount());
      else assertTrue(queue.hasPendingIngestWork());
      assertTrue(queue.queueDbHealthSnapshot().healthy());
      assertEquals(errorAt, queue.queueDbHealthSnapshot().lastDbErrorAtMs());
    }
  }
}

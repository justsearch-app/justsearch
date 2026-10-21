/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexingJobsStatePresenceTest {
  @TempDir Path temp;

  @Test void presenceOutsideWindowTracksTransitionsReplacementRollbackAndReconnect() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      var feed = queue.changeStream();
      var changes = new ArrayList<IndexingJobChangeFeed.Delta>();
      var initial = feed.subscribeWithSnapshot(changes::add);
      assertEquals(Set.of(), initial.presentStates());
      queue.enqueue(List.of(temp.resolve("a")));
      assertEquals(Set.of("PENDING"), changes.getLast().presentStates());
      assertTrue(feed.subscribeWithSnapshot(d -> {}).items().isEmpty());
      queue.enqueue(List.of(temp.resolve("b")));
      assertNull(changes.getLast().presentStates(), "count-only changes do not publish presence");
      Connection connection = connection(queue);
      mutate(feed, connection, "UPDATE jobs SET last_updated=last_updated+1");
      assertTrue(changes.stream().skip(2).allMatch(d -> d.presentStates() == null), "heartbeats do not publish presence");
      var reconnect = feed.subscribeWithSnapshot(d -> {});
      assertEquals(Set.of("PENDING"), reconnect.presentStates());
      assertTrue(reconnect.items().isEmpty());
      // REPLACE does not report its implicit deletion through SQLite's update hook.
      mutate(feed, connection, "UPDATE jobs SET state='DONE'");
      assertEquals(Set.of("DONE"), changes.getLast().presentStates());
      queue.enqueue(List.of(temp.resolve("a")));
      assertEquals(Set.of("DONE", "PENDING"), changes.getLast().presentStates());
      mutate(feed, connection, "DELETE FROM jobs WHERE state='DONE'");
      assertEquals(Set.of("PENDING"), changes.getLast().presentStates());
      mutate(feed, connection, "UPDATE jobs SET state='DONE'");
      queue.enqueue(List.of(temp.resolve("a")));
      assertEquals(Set.of("PENDING"), changes.getLast().presentStates(), "REPLACE removes the last old state too");
      int beforeRollback = changes.size();
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) { statement.executeUpdate("UPDATE jobs SET state='FAILED'"); }
      connection.rollback();
      connection.setAutoCommit(true);
      feed.commitSucceeded(); feed.drainCommitted();
      assertEquals(beforeRollback, changes.size());
      queue.clearAll();
      assertEquals(Set.of(), changes.getLast().presentStates(), "removing last outside-window row changes presence");
      assertEquals(Set.of(), feed.subscribeWithSnapshot(d -> {}).presentStates());
      initial.subscription().close(); reconnect.subscription().close();
    }
  }

  @Test void presenceReadIsAnIndexedSearchForEveryState() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("plans.db"))) {
      queue.open();
      try (var stmt = connection(queue).prepareStatement("EXPLAIN QUERY PLAN " + IndexingJobsChangeStream.STATE_EXISTS_SQL)) {
      for (String state : List.of("PENDING", "PROCESSING", "DONE", "FAILED", "RETRY_EXHAUSTED")) {
        stmt.setString(1, state);
        try (var rows = stmt.executeQuery()) {
          assertTrue(rows.next());
          String detail = rows.getString("detail");
          System.out.printf("Q18 %s plan=%s%n", state, detail);
          assertTrue(detail.contains("SEARCH jobs USING COVERING INDEX idx_jobs_state_updated"), detail);
          assertFalse(detail.contains("SCAN"), detail);
        }
      }
      }
    }
  }

  @Test void commitReadsOnlyDepartedStatesAndKeepsTrackingConnectionLocal() throws Exception {
    try (var actual = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
      try (var stmt = actual.createStatement()) {
        stmt.execute("CREATE TABLE jobs(path TEXT PRIMARY KEY, state TEXT, attempts INTEGER DEFAULT 0, last_updated INTEGER, error_message TEXT, retry_after INTEGER, collection TEXT, scan_id TEXT)");
        stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state,last_updated)");
      }
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      Connection observed = org.mockito.Mockito.mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      org.mockito.Mockito.doAnswer(call -> {
        String sql = call.getArgument(0);
        if (sql.equals(IndexingJobsChangeStream.STATE_EXISTS_SQL)) reads.incrementAndGet();
        return actual.prepareStatement(sql);
      }).when(observed).prepareStatement(org.mockito.ArgumentMatchers.anyString());
      try (var feed = new IndexingJobsChangeStream(observed, new java.util.concurrent.locks.ReentrantLock(), () -> {})) {
        var changes = new ArrayList<IndexingJobChangeFeed.Delta>();
        var sub = feed.subscribeWithSnapshot(changes::add);
        assertEquals(5, reads.get(), "one indexed read per state in a snapshot");
        mutate(feed, actual, "INSERT INTO jobs(path,state) VALUES ('a','PENDING'),('b','PENDING')");
        assertEquals(5, reads.get(), "entered states need no read");
        mutate(feed, actual, "UPDATE jobs SET last_updated=42");
        assertEquals(5, reads.get(), "heartbeat needs no state read");
        mutate(feed, actual, "UPDATE jobs SET state='DONE' WHERE path='a'");
        assertEquals(6, reads.get(), "only the departed PENDING state is rechecked");
        assertEquals(Set.of("PENDING", "DONE"), changes.getLast().presentStates());
        mutate(feed, actual, "UPDATE jobs SET state='DONE' WHERE path='b'");
        assertEquals(7, reads.get());
        assertEquals(Set.of("DONE"), changes.getLast().presentStates());
        int beforeIgnored = changes.size();
        mutate(feed, actual, "INSERT OR IGNORE INTO jobs(path,state) VALUES ('a','FAILED')");
        assertEquals(8, reads.get(), "a potential replacement only rechecks its old state");
        assertEquals(beforeIgnored, changes.size(), "an ignored insertion never publishes a phantom state");
        try (var stmt = actual.createStatement(); var rows = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='trigger'")) {
          assertTrue(rows.next()); assertEquals(0, rows.getInt(1), "no persistent schema change");
        }
        sub.subscription().close();
      }
    }
  }

  private static Connection connection(SqliteJobQueue queue) throws Exception {
    var field = SqliteJobQueue.class.getDeclaredField("connection"); field.setAccessible(true);
    return (Connection) field.get(queue);
  }

  private static void mutate(IndexingJobsChangeStream feed, Connection connection, String sql) throws Exception {
    try (var stmt = connection.createStatement()) { stmt.executeUpdate(sql); }
    feed.commitSucceeded(); feed.drainCommitted();
  }
}

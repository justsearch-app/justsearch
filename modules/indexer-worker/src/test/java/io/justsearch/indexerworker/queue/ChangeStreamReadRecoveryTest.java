/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class ChangeStreamReadRecoveryTest {
  @TempDir Path temp;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void committedCompletionRecoversAfterReadFailuresWithoutFurtherMutations(boolean consecutiveFailures) throws Exception {
    try (Connection actual = DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("stream.db"))) {
      try (var stmt = actual.createStatement()) {
        stmt.execute("CREATE TABLE jobs(path TEXT PRIMARY KEY,state TEXT,attempts INTEGER DEFAULT 0,last_updated INTEGER,error_message TEXT,retry_after INTEGER,collection TEXT,scan_id TEXT)");
        stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state,last_updated)");
      }
      var rowFailure = new AtomicBoolean(); var presenceFailure = new AtomicBoolean(); var windowFailure = new AtomicBoolean();
      var attempts = new AtomicInteger();
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doAnswer(call -> {
        String sql = call.getArgument(0);
        if (rowFailure.get() && sql.contains("WHERE rowid = ?")) {
          attempts.incrementAndGet(); throw new SQLException("row materialization unavailable");
        }
        if (presenceFailure.get() && sql.equals(IndexingJobsChangeStream.STATE_EXISTS_SQL)) {
          attempts.incrementAndGet(); throw new SQLException("presence unavailable");
        }
        return actual.prepareStatement(sql);
      }).when(observed).prepareStatement(anyString());
      doAnswer(call -> {
        Statement actualStatement = actual.createStatement();
        Statement wrapped = mock(Statement.class, org.mockito.AdditionalAnswers.delegatesTo(actualStatement));
        doAnswer(read -> {
          String sql = read.getArgument(0);
          if (windowFailure.get() && sql.contains("FROM jobs WHERE state = 'PROCESSING'")) {
            attempts.incrementAndGet(); throw new SQLException("window unavailable");
          }
          return actualStatement.executeQuery(sql);
        }).when(wrapped).executeQuery(anyString());
        return wrapped;
      }).when(observed).createStatement();
      var lock = new ReentrantLock();
      try (var feed = new IndexingJobsChangeStream(observed, lock, () -> {})) {
        var changes = new CopyOnWriteArrayList<IndexingJobChangeFeed.Delta>();
        var subscription = feed.subscribeWithSnapshot(changes::add).subscription();
        lock.lock();
        try {
          mutate(actual, "INSERT INTO jobs(path,state,last_updated) VALUES ('/private/file','PROCESSING',1)");
          feed.commitSucceeded(); feed.drainCommitted();
          assertEquals(1, changes.size());
          rowFailure.set(true); presenceFailure.set(consecutiveFailures); windowFailure.set(consecutiveFailures);
          mutate(actual, "UPDATE jobs SET state='DONE'");
          feed.commitSucceeded(); feed.drainCommitted();
        } finally { lock.unlock(); }
        if(consecutiveFailures) {
          await(feed,lock,() -> attempts.get() >= 4);
          rowFailure.set(false); windowFailure.set(false);
          int before = attempts.get(); await(feed,lock,() -> attempts.get() > before);
          presenceFailure.set(false);
        } else {
          assertEquals(1,attempts.get(),"only the completion-row SELECT failed");
          rowFailure.set(false);
        }
        await(feed,lock,() -> changes.stream().anyMatch(delta -> delta instanceof IndexingJobChangeFeed.Delta.Resync r
            && r.items().isEmpty() && Set.of("DONE").equals(r.presentStates())));
        assertFalse(changes.toString().contains("/private/file"));
        subscription.close();
      }
    }
  }

  @Test void legitimatelyDeletedRowsDoNotBecomePhantomUpdates() throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve("absent.db"))) {
      queue.open(); var feed=queue.changeStream();
      var changes=new java.util.ArrayList<IndexingJobChangeFeed.Delta>();
      feed.subscribe(changes::add);
      var actual=JobCountsTest.connection(queue);
      actual.setAutoCommit(false);
      mutate(actual,"INSERT INTO jobs(path,state,last_updated) VALUES ('absent','PENDING',1)");
      mutate(actual,"UPDATE jobs SET state='DONE' WHERE path='absent'");
      mutate(actual,"DELETE FROM jobs WHERE path='absent'");
      actual.commit();actual.setAutoCommit(true);feed.commitSucceeded();feed.drainCommitted();
      assertTrue(changes.stream().allMatch(delta->delta instanceof IndexingJobChangeFeed.Delta.Resync r && r.items().isEmpty()));
    }
  }

  private static void mutate(Connection connection,String sql) throws Exception {
    try(var stmt=connection.createStatement()){stmt.executeUpdate(sql);}
  }

  private static void await(IndexingJobsChangeStream feed,ReentrantLock lock,java.util.function.BooleanSupplier condition) throws Exception {
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while(!condition.getAsBoolean() && System.nanoTime()<end) {
      // The running queue drains on idle polls and read-only status observations.
      lock.lock();try{feed.drainCommitted();}finally{lock.unlock();}
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(),"projection must converge without another mutation");
  }
}

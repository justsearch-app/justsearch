/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.StatusRequest;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Verifier D7's real queue/pause counterexamples, with recovery and pause-lifetime checks. */
final class ServiceOutageBoundaryTest {
  @TempDir Path temp;

  @Test @Timeout(25)
  void publicStatusDuringActualEarlyOutagePauseWithFreshLaterBacklog() throws Exception {
    try (var h = new Fixture(true)) {
      h.enqueue(64); h.start();
      await(() -> h.paused() && h.rows().stream().filter(r -> r.policy() == null).count() == 48
          && h.rows().stream().allMatch(r -> r.state().equals("PENDING")), 5_000);
      var status = h.status();
      System.out.println("early real pause status=" + status.getCore().getState()
          + " ready=" + status.getMigration().getPendingReadyJobsCount()
          + " nextRetry=" + status.getFailure().getNextRetryAtMs());
      assertEquals("WAITING", status.getCore().getState(), "I7: actual outage pause overrides fresh backlog");
      assertEquals(48, status.getMigration().getPendingReadyJobsCount(), "keep honest queue slices");
      assertEquals(0, status.getMigration().getProcessingJobsCount());
      assertTrue(status.getFailure().getNextRetryAtMs() > System.currentTimeMillis());
      h.store.close(); h.store = new SqliteDocumentIdentityStore(h.db);
      await(() -> h.rows().stream().allMatch(r -> r.state().equals("DONE")), 4_000);
      assertEquals("IDLE", h.status().getCore().getState(), "pause state clears immediately after recovery");
      assertTrue(h.rows().stream().allMatch(r -> r.attempts() == 0));
      h.stop();
    }
  }

  @Test @Timeout(40)
  void persistentJobsReadonlyAfterFourthBatchRetainsOutagePacingAndBoundedLogs() throws Exception {
    try (var h = new Fixture(false)) {
      var actual = h.queue;
      var connection = h.connection();
      var defers = new AtomicInteger(); var flipped = new AtomicLong();
      var controls = new CopyOnWriteArrayList<Long>();
      h.queue = mock(SqliteJobQueue.class, call -> {
        String method = call.getMethod().getName();
        if (flipped.get() != 0 && (method.equals("pollPending") || method.equals("returnUnfinishedClaims"))) {
          controls.add(System.nanoTime());
        }
        Object result;
        try { result = call.getMethod().invoke(actual, call.getArguments()); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
        if (method.equals("deferClaim") && defers.incrementAndGet() == 8) {
          execute(connection, "PRAGMA query_only=ON"); flipped.set(System.nanoTime());
        }
        return result;
      });
      var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
      appender.start(); logger.addAppender(appender);
      try {
        doThrow(new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DRAINING, "runtime_draining", null))
            .when(h.writer).indexSingle(any());
        h.enqueue(2); h.start();
        await(() -> flipped.get() != 0, 12_000);
        await(() -> System.nanoTime() >= flipped.get() + 12_000_000_000L, 13_000);
        var refusal = assertThrows(SQLException.class, () -> execute(connection, "UPDATE jobs SET retry_after=retry_after"));
        assertEquals(8, refusal.getErrorCode() & 255, "real JDBC connection remains READONLY");
        var offsets = controls.stream().map(t -> (t - flipped.get()) / 1_000_000)
            .filter(t -> t >= 900 && t < 12_000).toList();
        long returns = appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("Could not return ")).count();
        long polls = appender.list.stream().filter(e -> e.getFormattedMessage().equals("Failed to poll pending jobs")).count();
        long extensions = appender.list.stream().filter(e -> e.getFormattedMessage()
            .contains("Could not extend service-outage deferrals; pausing anyway")).count();
        System.out.println("persistent READONLY control offsets=" + offsets + " returnWarnings=" + returns
            + " pollErrors=" + polls + " extensionWarnings=" + extensions + " rows=" + h.rows());
        assertAll(
            () -> assertFalse(offsets.isEmpty(), "must retry queue control after capped pause"),
            () -> assertTrue(!offsets.isEmpty() && offsets.getFirst() >= 7_900, "retain first full eight-second pause"),
            () -> assertTrue(offsets.size() <= 1, "no one-second queue-control loop: " + offsets),
            () -> assertTrue(returns <= 1 && polls <= 1 && extensions <= 1, "minute-rate failure summaries"));
        h.assertFree();
        assertEquals("WAITING", h.status().getCore().getState());
        assertTrue(h.status().getFailure().getNextRetryAtMs() > System.currentTimeMillis());
        execute(connection, "PRAGMA query_only=OFF");
        doAnswer(invocation -> null).when(h.writer).indexSingle(any());
        await(() -> h.rows().stream().allMatch(r -> r.state().equals("DONE")), 9_000);
        h.stop(); h.assertAttemptsZero();
      } finally {
        logger.detachAppender(appender); appender.stop();
        execute(connection, "PRAGMA query_only=OFF");
        h.queue = actual;
      }
    }
  }

  @Test @Timeout(25)
  void readonlyQueuePollUsesGrowingServicePauseAndRecoversWithoutCharging() throws Exception {
    try (var h = new Fixture(false)) {
      h.enqueue(2);
      var actual = h.queue; var connection = h.connection();
      var polls = new CopyOnWriteArrayList<Long>();
      h.queue = mock(SqliteJobQueue.class, call -> {
        if (call.getMethod().getName().equals("pollPending")) polls.add(System.nanoTime());
        try { return call.getMethod().invoke(actual, call.getArguments()); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
      });
      execute(connection, "PRAGMA query_only=ON");
      try {
        h.start();
        await(() -> polls.size() >= 4, 12_000);
        var gaps = java.util.stream.IntStream.range(1, polls.size())
            .mapToLong(i -> (polls.get(i) - polls.get(i - 1)) / 1_000_000).boxed().toList();
        System.out.println("READONLY initial poll intervals=" + gaps + " status=" + h.status().getCore().getState());
        assertTrue(gaps.get(0) >= 950 && gaps.get(1) >= 1_950 && gaps.get(2) >= 3_950,
            "shared queue poll outage grows 1/2/4 seconds: " + gaps);
        await(h::paused, 1_000);
        assertEquals("WAITING", h.status().getCore().getState());
        h.assertAttemptsZero();
        execute(connection, "PRAGMA query_only=OFF");
        await(() -> h.rows().stream().allMatch(r -> r.state().equals("DONE")), 9_000);
        h.stop(); h.assertAttemptsZero();
      } finally { execute(connection, "PRAGMA query_only=OFF"); h.queue = actual; }
    }
  }

  @Test @Timeout(30)
  void readonlyCommittedQueueOutcomesKeepOwnersAndUseGrowingPause() throws Exception {
    try (var h = new Fixture(false)) {
      h.enqueue(2);
      var connection = h.connection(); var written = new AtomicInteger();
      doAnswer(call -> { written.incrementAndGet(); return null; }).when(h.writer).indexSingle(any());
      var unavailable = new AtomicBoolean(true);
      var actual = h.queue; var drains = new CopyOnWriteArrayList<Long>();
      var refusals = new CopyOnWriteArrayList<Integer>();
      h.queue = mock(SqliteJobQueue.class, call -> {
        boolean outcomeWrite = call.getMethod().getName().equals("markDoneTransitions");
        if (outcomeWrite) {
          drains.add(System.nanoTime());
          if (unavailable.get()) execute(connection, "PRAGMA query_only=ON");
        }
        try { return call.getMethod().invoke(actual, call.getArguments()); }
        catch (InvocationTargetException wrapped) {
          if (outcomeWrite && wrapped.getCause() instanceof OutcomeWriteException failure
              && failure.getCause() instanceof SQLException sql) refusals.add(sql.getErrorCode() & 255);
          throw wrapped.getCause();
        } finally {
          // Isolate an actual SQLite outcome-write outage while queue polls stay available.
          if (outcomeWrite) execute(connection, "PRAGMA query_only=OFF");
        }
      });
      var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IngestionOutcomeJournal.class);
      var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
      appender.start(); logger.addAppender(appender);
      try {
        h.start();
        await(() -> drains.size() >= 12, 12_000);
        var batches = new ArrayList<Long>();
        for (long time : drains) if (batches.isEmpty() || time - batches.getLast() > 500_000_000L) batches.add(time);
        var gaps = java.util.stream.IntStream.range(1, batches.size())
            .mapToLong(i -> (batches.get(i) - batches.get(i - 1)) / 1_000_000).boxed().toList();
        long warnings = appender.list.stream().filter(event -> event.getLevel()
            .isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)).count();
        System.out.println("READONLY committed-outcome intervals=" + gaps + " warnings=" + warnings + " rows=" + h.rows());
        assertAll(
            () -> assertTrue(gaps.size() >= 3 && gaps.get(0) >= 950 && gaps.get(1) >= 1_950 && gaps.get(2) >= 3_950,
                "committed queue outcomes use service backoff: " + gaps),
            () -> assertTrue(warnings <= 1, "committed-outcome failures have minute-rate summaries: " + warnings),
            () -> assertTrue(!refusals.isEmpty() && refusals.stream().allMatch(code -> code == 8),
                "real SQLite READONLY refusals at the outcome-write boundary"));
        await(h::paused, 1_000);
        assertEquals("WAITING", h.status().getCore().getState());
        assertTrue(h.rows().stream().allMatch(r -> r.state().equals("PROCESSING") && r.attempts() == 0),
            "committed claims remain owned until their exact outcome can be persisted");
        unavailable.set(false);
        await(() -> h.rows().stream().allMatch(r -> r.state().equals("DONE")), 9_000);
        h.stop(); h.assertAttemptsZero(); assertEquals(2, written.get(), "retry committed outcomes without rewriting files");
      } finally {
        unavailable.set(false);
        if (h.loop != null) h.stop();
        logger.detachAppender(appender); appender.stop();
        execute(connection, "PRAGMA query_only=OFF"); h.queue = actual;
      }
    }
  }

  private record Row(String state, int attempts, long retry, String policy) {}
  @FunctionalInterface private interface CheckedCondition { boolean get() throws Exception; }
  private static void await(CheckedCondition condition, long timeoutMs) throws Exception {
    long end = System.nanoTime() + timeoutMs * 1_000_000;
    while (System.nanoTime() < end) { if (condition.get()) return; Thread.sleep(10); }
    assertTrue(condition.get(), "condition within " + timeoutMs + "ms");
  }
  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) { statement.execute(sql); }
  }

  private final class Fixture implements AutoCloseable {
    final Path db = temp.resolve("jobs.db");
    final SqliteJobQueue actualQueue = new SqliteJobQueue(db);
    SqliteJobQueue queue = actualQueue;
    volatile SqliteDocumentIdentityStore store;
    final IndexingCoordinator writer = mock(IndexingCoordinator.class);
    final TimeboxedContentExtractor content = mock(TimeboxedContentExtractor.class);
    IndexingLoop loop;
    WorkerIngestService service;
    Fixture(boolean readonlyIdentity) throws Exception {
      queue.open();
      store = new SqliteDocumentIdentityStore(readonlyIdentity ? Path.of("file:" + db + "?mode=ro") : db);
      when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(content.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
          new ContentExtractor.ExtractionResult("healthy readable source", null, "text/plain"),
          TikaExtractionPolicy.defaults(), "boundary", false));
    }
    void enqueue(int count) throws Exception {
      Path corpus = Path.of(System.getProperty("justsearch.test.outageCorpus", temp.toString()));
      var files = new ArrayList<JobQueue.EnqueueEntry>();
      for (int i = 0; i < count; i++) {
        Path file = corpus.resolve("capped-backlog-" + i + ".txt");
        if (!Files.exists(file)) Files.writeString(file, "healthy readable content");
        files.add(JobQueue.EnqueueEntry.stat(file).withinRoot(corpus));
      }
      queue.enqueueEntries(files);
    }
    void start() throws Exception {
      var identities = mock(io.justsearch.indexerworker.identity.DocumentIdentityStore.class);
      when(identities.resolve(anyString(), anyLong())).thenAnswer(call -> store.resolve(call.getArgument(0), call.getArgument(1)));
      loop = new IndexingLoop(null, null, queue, writer, mock(CommitOps.class), mock(DocumentFieldOps.class),
          mock(IndexCountOps.class), () -> new ResolvedConfigBuilder().build(), mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(), null, null, null, null, content, null,
          new IndexingLoopOptions(false, null, identities, null, null, null));
      service = new WorkerIngestService(queue, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
          temp, temp.resolve("index"), null, null, null, 0L);
      loop.start();
    }
    io.justsearch.ipc.StatusResponse status() { return service.indexStatus(StatusRequest.getDefaultInstance(), CallContext.none()); }
    Connection connection() throws Exception {
      var field = SqliteJobQueue.class.getDeclaredField("connection"); field.setAccessible(true);
      return (Connection) field.get(actualQueue);
    }
    boolean paused() throws Exception {
      var field = IndexingLoop.class.getDeclaredField("loopThread"); field.setAccessible(true);
      Thread owner = (Thread) field.get(loop);
      return owner != null && Arrays.stream(owner.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("sleepAfterError"));
    }
    List<Row> rows() throws SQLException {
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
          var rows = s.executeQuery("SELECT state,attempts,retry_after,last_retry_policy FROM jobs ORDER BY path")) {
        var result = new ArrayList<Row>();
        while (rows.next()) result.add(new Row(rows.getString(1), rows.getInt(2), rows.getLong(3), rows.getString(4)));
        return result;
      }
    }
    void assertFree() throws SQLException {
      assertTrue(rows().stream().allMatch(r -> r.state().equals("PENDING") && r.attempts() == 0
          && "DEFER_WITHOUT_ATTEMPT".equals(r.policy())), "all durable rows remain free deferrals: " + rows());
    }
    void assertAttemptsZero() throws SQLException { assertTrue(rows().stream().allMatch(r -> r.attempts() == 0)); }
    void stop() throws Exception { loop.close(); loop = null; }
    @Override public void close() throws Exception { if (loop != null) stop(); store.close(); actualQueue.close(); }
  }
}

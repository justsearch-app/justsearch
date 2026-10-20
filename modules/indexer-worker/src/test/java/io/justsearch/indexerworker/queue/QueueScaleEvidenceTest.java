/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.ipc.IndexingJobsDelta;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.TestInstance;

/**
 * Run with :modules:indexer-worker:queueScaleEvidence. Each Q gate can be selected with --tests.
 * Q4–Q6 are intentionally red until the status/claim assignment; they are never default tests.
 * Uses real SQLite queues and the real head bridge, without starting an Engine or dev stack.
 */
@Tag("evidence")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class QueueScaleEvidenceTest {
  private Path output;
  private Path backlog;
  private Path history;

  @BeforeAll
  void fixtures() throws Exception {
    output = Path.of(System.getProperty("queue.scale.output"));
    Files.createDirectories(output);
    backlog = output.resolve("F1-v22.db");
    history = output.resolve("F2-v22.db");
    // Generate afresh so a harness/schema edit cannot silently reuse stale fixtures.
    Files.deleteIfExists(backlog);
    Files.deleteIfExists(history);
    QueueScaleFixtures.generate(backlog, "F1", 22);
    QueueScaleFixtures.generate(history, "F2", 22);
  }

  @Test
  void q1MirrorHeapBootSubscribeAndConcurrentReconnect() {
    assertAll(() -> measureQ1MirrorHeapBootSubscribeAndConcurrentReconnect(backlog), () -> measureQ1MirrorHeapBootSubscribeAndConcurrentReconnect(history));
  }

  private void measureQ1MirrorHeapBootSubscribeAndConcurrentReconnect(Path fixture) throws Exception {
    try (var queue = openCopy(fixture, "q1"); var executors = new TestEngineExecutors()) {
      var stream = queue.changeStream();
      Object identities = field(stream, "rowIdToPathHash");
      Object reverse = field(stream, "pathHashToRowId");
      // Even the map nodes alone exceed the budget on the baseline. Avoid using another
      // million-entry traversal set merely to prove that already-known lower bound.
      long nodeFloor = (((java.util.Map<?, ?>) identities).size()
          + ((java.util.Map<?, ?>) reverse).size()) * 24L;
      System.out.printf("Q1 %s boot identity node lower bound=%d bytes%n", fixture.getFileName(), nodeFloor);
      assertTrue(nodeFloor < 5_000_000, "Q1 boot identity nodes alone exceed 5 MB: " + nodeFloor);
      var roots = new ArrayList<Object>();
      for (var field : stream.getClass().getDeclaredFields()) {
        if (java.util.Map.class.isAssignableFrom(field.getType()) || java.util.Set.class.isAssignableFrom(field.getType())) {
          field.setAccessible(true);
          roots.add(field.get(stream));
        }
      }
      long bootBytes = QueueScaleHeapAgent.reachableBytes(roots.toArray());
      System.out.printf("Q1 %s boot identity heap=%d bytes%n", fixture.getFileName(), bootBytes);
      assertTrue(bootBytes < 5_000_000, "Q1 boot mirror identity heap=" + bootBytes + " exceeds 5 MB");
      var frames = new AtomicReference<Consumer<IndexingJobsFrame>>();
      var snap = stream.subscribeWithSnapshot(d -> {});
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      var bridge = new RemoteIndexingJobsBridge(executors, () -> (onFrame, onError, onComplete) -> {
        frames.set(onFrame);
        service.subscribeIndexingJobs(io.justsearch.ipc.SubscribeIndexingJobsRequest.getDefaultInstance(),
            onFrame, io.justsearch.indexerworker.services.CallContext.none());
        return snap.subscription()::close;
      });
      try {
        bridge.start().get(5, TimeUnit.SECONDS);
        roots.add(snap.items());
        long workerBytes = QueueScaleHeapAgent.reachableBytes(roots.toArray());
        long headBytes = headBytes(bridge);
        System.out.printf("Q1 %s subscribed worker=%d head=%d bytes%n", fixture.getFileName(), workerBytes, headBytes);
        assertTrue(workerBytes < 5_000_000 && headBytes < 5_000_000, "Q1 subscribed mirror exceeds 5 MB");
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
          var copies = new ArrayList<java.util.concurrent.Future<IndexingJobChangeFeed.SnapshotAndSubscription>>();
          for (int i = 0; i < 4; i++) copies.add(threads.submit(() -> stream.subscribeWithSnapshot(d -> {})));
          for (var copy : copies) {
            var reconnect = copy.get(10, TimeUnit.SECONDS);
            assertEquals(snap.items(), reconnect.items());
            frames.get().accept(snapshot(reconnect.items(), 2));
            reconnect.subscription().close();
          }
        }
        histogram(fixture.getFileName() + "-subscribed");
      } finally {
        bridge.stop();
      }
    }
  }

  @Test
  void q2TenThousandHeadChangesAndSustainedChurn() throws Exception {
    try (var queue = openCopy(backlog, "q2"); var executors = new TestEngineExecutors()) {
      var snap = queue.changeStream().subscribeWithSnapshot(d -> {});
      // Retain the real fixture snapshot but release the queue's full-table baseline cache,
      // so this gate isolates head per-change cost instead of failing on boot heap first.
      queue.close();
      var frames = new AtomicReference<Consumer<IndexingJobsFrame>>();
      var bridge = new RemoteIndexingJobsBridge(executors, () -> (onFrame, onError, onComplete) -> {
        frames.set(onFrame);
        onFrame.accept(snapshot(snap.items(), 1));
        return snap.subscription()::close;
      });
      try {
        bridge.start().get(10, TimeUnit.SECONDS);
        var row = io.justsearch.ipc.IndexingJobView.newBuilder()
            .setPathHash("processing-probe").setState("PROCESSING").setCollection("scale");
        long changedAt = System.currentTimeMillis();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
          frames.get().accept(IndexingJobsFrame.newBuilder().setSeq(i + 2)
              .setDelta(IndexingJobsDelta.newBuilder().setUpdate(row
                  .setState(i % 2 == 0 ? "PROCESSING" : "FAILED").setLastUpdatedMs(changedAt + i))).build());
          // Once the budget is exhausted, the remaining operations cannot rescue the result.
          assertTrue(System.nanoTime() - start < 1_000_000_000L,
              "Q2 exceeded 1s before completing 10k changes (completed " + (i + 1) + ")");
        }
        System.out.printf("Q2 10k head changes=%.3fms%n", (System.nanoTime() - start) / 1e6);
        for (int round = 0; round < 3; round++) {
          for (int i = 0; i < 10_000; i++) {
            frames.get().accept(IndexingJobsFrame.newBuilder().setSeq(10_002L + round * 10_000L + i)
                .setDelta(IndexingJobsDelta.newBuilder().setUpdate(row.setState("FAILED")
                    .setPathHash("failure-" + round + "-" + i).setLastUpdatedMs(4_000_000_000_000L + round * 10_000L + i))).build());
          }
          assertTrue(bridge.latestSnapshot().size() <= 217, "Q2 head window regrew under sustained churn");
          long bytes = headBytes(bridge);
          System.out.printf("Q2 churn round=%d head heap=%d bytes%n", round, bytes);
          assertTrue(bytes < 5_000_000, "Q2 retained mirror grew beyond 5 MB");
        }
      } finally { bridge.stop(); }
    }
    measureQ2WorkerPresenceCommits();
  }

  private void measureQ2WorkerPresenceCommits() throws Exception {
    try (var queue = openCopy(backlog, "q2-presence"); var executors = new TestEngineExecutors()) {
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      var bridge = new RemoteIndexingJobsBridge(executors, () -> (frames, error, completed) -> {
        service.subscribeIndexingJobs(io.justsearch.ipc.SubscribeIndexingJobsRequest.getDefaultInstance(),
            frames, io.justsearch.indexerworker.services.CallContext.none());
        return () -> {}; // Queue close below detaches this component-only source.
      });
      try {
        bridge.start().get(5, TimeUnit.SECONDS);
        Connection connection = (Connection) field(queue, "connection");
        String path;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT path FROM jobs WHERE state='PENDING' LIMIT 1")) {
          assertTrue(rows.next()); path = rows.getString(1);
        }
        long nanos = 0;
        try (var statement = connection.prepareStatement("UPDATE jobs SET state=?, last_updated=? WHERE path=?")) {
          for (int i = 0; i < 10_000; i++) {
            statement.setString(1, i % 2 == 0 ? "PROCESSING" : "DONE");
            statement.setLong(2, System.currentTimeMillis()); statement.setString(3, path);
            statement.executeUpdate();
            // Measure the projection after JDBC commit: includes touched-state indexed reads,
            // row materialization, wire mapping and actual head delivery, excluding SQLite writes.
            long start = System.nanoTime();
            queue.changeStream().commitSucceeded(); queue.changeStream().drainCommitted();
            nanos += System.nanoTime() - start;
            assertTrue(nanos < 1_000_000_000L, "Q2 per-commit projection exhausted 1s at change " + (i + 1));
          }
        }
        System.out.printf("Q2 10k worker presence commits + real head projection=%.3fms%n", nanos / 1e6);
        assertTrue(nanos < 1_000_000_000L, "Q2 per-commit mirror projection exceeds 1s: " + nanos / 1e6 + "ms");
        assertEquals(216, bridge.latestSnapshot().size());
      } finally { bridge.stop(); }
    }
  }

  private static long headBytes(RemoteIndexingJobsBridge bridge) throws Exception {
    var roots = new ArrayList<Object>();
    roots.add(bridge.latestSnapshotPair());
    for (var field : bridge.getClass().getDeclaredFields()) {
      if (java.util.Map.class.isAssignableFrom(field.getType()) || java.util.Set.class.isAssignableFrom(field.getType())) {
        field.setAccessible(true);
        roots.add(field.get(bridge));
      }
    }
    return QueueScaleHeapAgent.reachableBytes(roots.toArray());
  }

  @Test
  void q3SnapshotAndSubscribeQueueLock() throws Exception {
    try (var queue = openCopy(backlog, "q3")) {
      for (int i = 0; i < 5; i++) {
        long start = System.nanoTime();
        var snap = queue.changeStream().subscribeWithSnapshot(d -> {});
        double ms = (System.nanoTime() - start) / 1e6;
        snap.subscription().close();
        System.out.printf("Q3 snapshot-and-subscribe lock upper bound=%.3fms rows=%d%n", ms, snap.items().size());
        assertTrue(ms < 50, "Q3 snapshot-and-subscribe exceeded its 50ms lock budget: " + ms + "ms");
      }
    }
  }

  @Test
  void q4WarmStatusReadsAndPlans() {
    assertAll(() -> measureQ4WarmStatusReadsAndPlans(backlog), () -> measureQ4WarmStatusReadsAndPlans(history));
  }

  private void measureQ4WarmStatusReadsAndPlans(Path fixture) throws Exception {
    try (var queue = openCopy(fixture, "q4")) {
      var original = (Connection) field(queue, "connection");
      var sqlReads = new java.util.LinkedHashSet<String>();
      Connection observed = org.mockito.Mockito.mock(Connection.class,
          org.mockito.AdditionalAnswers.delegatesTo(original));
      org.mockito.Mockito.doAnswer(call -> {
        String sql = call.getArgument(0);
        if (sql.stripLeading().startsWith("SELECT")) sqlReads.add(sql);
        return original.prepareStatement(sql);
      }).when(observed).prepareStatement(org.mockito.ArgumentMatchers.anyString());
      org.mockito.Mockito.doAnswer(call -> {
        var nativeStatement = original.createStatement();
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class,
            org.mockito.AdditionalAnswers.delegatesTo(nativeStatement));
        org.mockito.Mockito.doAnswer(query -> {
          String sql = query.getArgument(0);
          if (sql.stripLeading().startsWith("SELECT")) sqlReads.add(sql);
          return nativeStatement.executeQuery(sql);
        }).when(statement).executeQuery(org.mockito.ArgumentMatchers.anyString());
        return statement;
      }).when(observed).createStatement();
      setField(queue, "connection", observed);
      var failures = new ArrayList<org.junit.jupiter.api.function.Executable>();
      failures.add(() -> gate("Q4 " + fixture.getFileName() + " counts", queue::jobStateCounts, 5));
      failures.add(() -> gate("Q4 " + fixture.getFileName() + " strict counts", queue::jobStateCountsStrict, 5));
      failures.add(() -> gate("Q4 " + fixture.getFileName() + " depth", queue::queueDepth, 5));
      failures.add(() -> gate("Q4 " + fixture.getFileName() + " bytes", queue::pendingBytes, 5));
      failures.add(() -> gate("Q4 " + fixture.getFileName() + " failures", queue::failureSummary, 5));
      queue.jobStateCounts();
      queue.jobStateCountsStrict();
      queue.queueDepth();
      queue.pendingBytes();
      queue.failureSummary();
      failures.add(() -> plans(queue, sqlReads.toArray(String[]::new)));
      assertAll(failures);
    }
  }

  @Test
  void q5PendingIngestProbeReadyAndRetryWaiting() throws Exception {
    try (var queue = openCopy(backlog, "q5")) {
      // Run the actual loop method, clearing only its 250ms answer cache between measurements.
      var loop = org.mockito.Mockito.mock(IndexingLoop.class, org.mockito.Mockito.CALLS_REAL_METHODS);
      setField(loop, "jobQueue", queue);
      var probe = IndexingLoop.class.getDeclaredMethod("hasPendingIngestWork");
      probe.setAccessible(true);
      assertEquals(true, probe.invoke(loop));
      var coldProbe = (Runnable) () -> {
        try {
          setField(loop, "pendingIngestProbedAtMs", 0L);
          probe.invoke(loop);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
      };
      double ready = p95(coldProbe);
      var conn = (Connection) field(queue, "connection");
      try (var stmt = conn.createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET retry_after=4000000000000 WHERE state='PENDING'");
      }
      setField(loop, "pendingIngestProbedAtMs", 0L);
      assertEquals(false, probe.invoke(loop));
      double waiting = p95(coldProbe);
      System.out.printf("Q5 ready=%.3fms retry-waiting=%.3fms p95%n", ready, waiting);
      assertTrue(ready < 1 && waiting < 1, "Q5 cold pending probe exceeds 1ms");
    }
  }

  @Test
  void q6ClaimDeniedRetryHeadAndTimestampTies() throws Exception {
    try (var queue = openCopy(backlog, "q6")) {
      Runnable poll = () -> {
        var batch = queue.pollPending(16);
        assertEquals(16, batch.size());
        for (var job : batch) {
          assertTrue(job.scanId() == null || !"denied-walk".equals(job.scanId()), "denied row claimed");
          assertTrue(Integer.parseInt(job.path().getFileName().toString().substring(0, 7)) > 60_000,
              "retry-waiting or denied head claimed");
          queue.markDone(job.path());
        }
      };
      // Time only pollPending, with completion outside its measured interval.
      var timings = new long[40];
      for (int i = 0; i < timings.length; i++) {
        long start = System.nanoTime();
        var batch = queue.pollPending(16);
        timings[i] = System.nanoTime() - start;
        assertEquals(16, batch.size());
        for (var job : batch) {
          assertNotEquals("denied-walk", job.scanId());
          queue.markDone(job.path());
        }
      }
      var conn = (Connection) field(queue, "connection");
      try (var stmt = conn.createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET last_updated=1 WHERE path IN (SELECT path FROM jobs WHERE state='PENDING' AND scan_id IS NULL AND retry_after IS NULL ORDER BY path LIMIT 100000)");
      }
      poll.run();
      for (int i = 0; i < timings.length; i++) {
        long start = System.nanoTime();
        var batch = queue.pollPending(16);
        long elapsed = System.nanoTime() - start;
        timings[i] = Math.max(timings[i], elapsed);
        assertEquals(16, batch.size());
        for (var job : batch) queue.markDone(job.path());
      }
      Arrays.sort(timings);
      double ms = timings[37] / 1e6;
      System.out.printf("Q6 claim p95 (head + ties)=%.3fms%n", ms);
      assertTrue(ms < 10, "Q6 pollPending(16) exceeds 10ms p95");
      long drained = 40L * 16 + 16 + 40L * 16;
      while (true) {
        var batch = queue.pollPending(256);
        if (batch.isEmpty()) break;
        for (var job : batch) {
          assertNotEquals("denied-walk", job.scanId());
          assertTrue(Integer.parseInt(job.path().getFileName().toString().substring(0, 7)) > 60_000,
              "backoff or denied row claimed while draining");
          queue.markDone(job.path());
        }
        drained += batch.size();
      }
      assertEquals(640_000, drained, "every eligible row must drain without starvation");
      assertEquals(60_000, queue.jobStateCountsStrict().pendingCount());
      assertEquals(16, queue.jobStateCountsStrict().processingCount());
      System.out.printf("Q6 drained all %d eligible rows; denied/backoff rows remain pending%n", drained);
    }
  }

  @Test
  void q12MigrationTimeSavedFieldsCountsAndRollback() throws Exception {
    Path legacy = output.resolve("F1-v13.db");
    Files.deleteIfExists(legacy);
    QueueScaleFixtures.generate(legacy, "F1", 13);
    for (Path fixture : List.of(legacy, backlog)) {
      Path file = freshCopy(fixture, "q12");
      List<String> columns;
      String before;
      int sourceVersion;
      List<String> expectedClaim;
      JobQueue.JobStateCounts expectedCounts;
      JobQueue.PendingBytes expectedBytes;
      try (var conn = DriverManager.getConnection("jdbc:sqlite:" + file)) {
        columns = columns(conn);
        before = digest(conn, columns);
        sourceVersion = version(conn);
        expectedClaim = claimPaths(conn, columns.contains("walk_seen_epoch"));
        expectedCounts = directCounts(conn);
        expectedBytes = directBytes(conn);
      }
      long start = System.nanoTime();
      try (var queue = new SqliteJobQueue(file)) {
        queue.open();
        System.out.printf("Q12 %s migration/open=%.3fs%n", fixture.getFileName(), (System.nanoTime() - start) / 1e9);
        var conn = (Connection) field(queue, "connection");
        assertEquals(before, digest(conn, columns), "migration altered pre-existing saved fields");
        assertEquals(expectedCounts, queue.jobStateCountsStrict());
        assertEquals(expectedCounts, queue.jobStateCounts());
        assertEquals(expectedBytes, queue.pendingBytes());
        assertEquals(expectedClaim, queue.pollPending(16).stream().map(job -> job.path().toString()).toList(),
            "claim order and eligibility must survive migration");
      }
      if (sourceVersion < SqliteSchema.TARGET_VERSION) {
        Path failed = freshCopy(fixture, "q12-crash");
        Process crash = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx1g", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp",
            System.getProperty("queue.scale.classpath"), QueueScaleMigrationCrash.class.getName(),
            failed.toString(), Integer.toString(sourceVersion + 1)).redirectErrorStream(true)
            .redirectOutput(output.resolve("migration-crash-v" + sourceVersion + ".log").toFile()).start();
        System.out.printf("Q12 started migration-crash child pid=%d%n", crash.pid());
        try {
          if (!crash.waitFor(60, TimeUnit.SECONDS)) fail("Migration crash child timed out");
          assertEquals(74, crash.exitValue(), "child must crash within the migration transaction");
        } finally {
          if (crash.isAlive()) crash.destroyForcibly();
        }
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + failed)) {
          assertEquals(sourceVersion, version(conn));
          assertEquals(before, digest(conn, columns), "crash must not change original saved fields");
        }
        try (var queue = new SqliteJobQueue(failed)) {
          queue.open();
          assertEquals(expectedCounts, queue.jobStateCountsStrict());
          assertEquals(before, digest((Connection) field(queue, "connection"), columns));
        }
      }
    }
  }

  private static int version(Connection conn) throws Exception {
    try (var stmt = conn.createStatement(); var row = stmt.executeQuery("PRAGMA user_version")) {
      assertTrue(row.next());
      return row.getInt(1);
    }
  }

  private static List<String> claimPaths(Connection conn, boolean hasWalkEpoch) throws Exception {
    var paths = new ArrayList<String>();
    try (var stmt = conn.prepareStatement("SELECT path FROM jobs WHERE state='PENDING' "
        + "AND (retry_after IS NULL OR retry_after<=?) "
        + (hasWalkEpoch ? "AND walk_seen_epoch IS NULL " : "")
        + "ORDER BY last_updated,path LIMIT 16")) {
      stmt.setLong(1, System.currentTimeMillis());
      try (var rows = stmt.executeQuery()) { while (rows.next()) paths.add(rows.getString(1)); }
    }
    return paths;
  }

  private static JobQueue.JobStateCounts directCounts(Connection conn) throws Exception {
    try (var stmt = conn.prepareStatement("SELECT SUM(state='PENDING'), "
        + "SUM(state='PENDING' AND (retry_after IS NULL OR retry_after<=?)), "
        + "SUM(state='PROCESSING'), SUM(state='DONE'), SUM(state IN ('FAILED','RETRY_EXHAUSTED')) FROM jobs")) {
      stmt.setLong(1, System.currentTimeMillis());
      try (var row = stmt.executeQuery()) {
        assertTrue(row.next());
        return new JobQueue.JobStateCounts(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5));
      }
    }
  }

  private static JobQueue.PendingBytes directBytes(Connection conn) throws Exception {
    try (var stmt = conn.createStatement(); var row = stmt.executeQuery(
        "SELECT SUM(size_bytes), SUM(size_bytes IS NULL) FROM jobs WHERE state IN ('PENDING','PROCESSING')")) {
      assertTrue(row.next());
      return new JobQueue.PendingBytes(row.getLong(1), row.getLong(2));
    }
  }

  private SqliteJobQueue openCopy(Path fixture, String name) throws Exception {
    var queue = new SqliteJobQueue(freshCopy(fixture, name));
    queue.open();
    return queue;
  }

  private Path freshCopy(Path fixture, String name) throws Exception {
    Path file = output.resolve(name + "-" + fixture.getFileName());
    Files.deleteIfExists(Path.of(file + "-wal"));
    Files.deleteIfExists(Path.of(file + "-shm"));
    Files.deleteIfExists(Path.of(file + "-journal"));
    Files.copy(fixture, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return file;
  }

  private static IndexingJobsFrame snapshot(List<IndexingJobChangeFeed.JobRow> rows, long seq) {
    var builder = IndexingJobsSnapshot.newBuilder();
    for (var row : rows) {
      builder.addItems(io.justsearch.ipc.IndexingJobView.newBuilder().setPathHash(row.pathHash())
          .setState(row.state()).setAttempts(row.attempts()).setLastUpdatedMs(row.lastUpdatedMs())
          .setErrorMessage(row.errorMessage()).setRetryAfterMs(row.retryAfterMs())
          .setCollection(row.collection()).setScanId(row.scanId()));
    }
    return IndexingJobsFrame.newBuilder().setSeq(seq).setSnapshot(builder).build();
  }

  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static double p95(Runnable operation) {
    for (int i = 0; i < 5; i++) operation.run();
    long[] times = new long[40];
    for (int i = 0; i < times.length; i++) {
      long start = System.nanoTime();
      operation.run();
      times[i] = System.nanoTime() - start;
    }
    Arrays.sort(times);
    return times[37] / 1e6;
  }

  private static void gate(String name, Runnable operation, double limitMs) {
    double time = p95(operation);
    System.out.printf("%s p95=%.3fms%n", name, time);
    assertTrue(time < limitMs, name + " exceeds " + limitMs + "ms p95");
  }

  private static void plans(SqliteJobQueue queue, String... queries) throws Exception {
    var conn = (Connection) field(queue, "connection");
    for (String sql : queries) {
      try (var stmt = conn.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
        for (int i = 1; i <= stmt.getParameterMetaData().getParameterCount(); i++) {
          stmt.setLong(i, System.currentTimeMillis());
        }
        try (var rows = stmt.executeQuery()) {
          while (rows.next()) {
            String plan = rows.getString(4);
            System.out.println("PLAN " + sql.strip().replace('\n', ' ') + " -> " + plan);
            assertTrue(!plan.matches("(?i).*SCAN jobs(?:\\s.*)?"), "Q4 whole-table/index scan: " + plan);
          }
        }
      }
    }
  }

  private void histogram(String name) throws Exception {
    Path file = output.resolve(name + ".histogram.txt");
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
        Long.toString(ProcessHandle.current().pid()), "GC.class_histogram").redirectErrorStream(true)
        .redirectOutput(file.toFile()).start();
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) fail("jcmd histogram timed out");
      assertEquals(0, process.exitValue(), Files.readString(file));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    System.out.println("Q1 histogram: " + file);
  }

  private static List<String> columns(Connection conn) throws Exception {
    var names = new ArrayList<String>();
    try (var stmt = conn.createStatement(); var rows = stmt.executeQuery("PRAGMA table_info(jobs)")) {
      while (rows.next()) names.add(rows.getString("name"));
    }
    return names;
  }

  private static String digest(Connection conn, List<String> columns) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    try (var stmt = conn.createStatement(); var rows = stmt.executeQuery(
        "SELECT " + String.join(",", columns) + " FROM jobs ORDER BY path")) {
      while (rows.next()) {
        for (int i = 1; i <= columns.size(); i++) {
          String value = rows.getString(i);
          byte[] bytes = (value == null ? "NULL" : value.length() + ":" + value)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          digest.update(bytes);
          digest.update((byte) 0);
        }
      }
    }
    return java.util.HexFormat.of().formatHex(digest.digest());
  }
}

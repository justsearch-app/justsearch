/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** U17, preserving U19: inject faults at the service ports and inspect the real durable queue. */
final class SystemicBatchDeferralTest {
  @TempDir Path tempDir;
  private SqliteJobQueue queue;
  private IndexingLoop loop;
  private IndexingCoordinator writer;
  private DocumentIdentityStore identities;
  private Path db;

  @BeforeEach
  void setUp() throws Exception {
    db = tempDir.resolve("jobs.db");
    queue = spy(new SqliteJobQueue(db));
    queue.open();
    writer = mock(IndexingCoordinator.class);
    identities = mock(DocumentIdentityStore.class);
    when(identities.resolve(anyString(), anyLong())).thenAnswer(call ->
        new DocumentIdentityStore.Identity(call.getArgument(0), "uid-" + call.getArgument(0),
            call.getArgument(1), call.getArgument(1)));
    var extractor = mock(TimeboxedContentExtractor.class);
    when(extractor.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    when(extractor.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
        new ContentExtractor.ExtractionResult("searchable body", null, "text/plain"),
        TikaExtractionPolicy.defaults(), "test-structured", false));
    loop = new IndexingLoop(null, null, queue, writer, mock(CommitOps.class),
        mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> null,
        mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null,
        extractor, null, new IndexingLoopOptions(false, null, identities, null, null, null));
    var running = IndexingLoop.class.getDeclaredField("running");
    running.setAccessible(true);
    ((AtomicBoolean) running.get(loop)).set(true);
  }

  @AfterEach
  void closeQueue() throws Exception {
    if (queue != null) queue.close();
  }

  @Test
  void writerOutageDefersWholeBatchWithoutAttemptsAndRecoversPromptly() throws Exception {
    var files = enqueue("writer-a.txt", "writer-b.txt", "writer-c.txt");
    doThrow(new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DISK_IO,
        "writer unavailable", null)).when(writer).indexSingle(any());
    assertShortDeferral(files);
    doNothing().when(writer).indexSingle(any());
    recover(files);
  }

  @Test
  void identityOutageDefersWholeBatchWithoutAttemptsAndRecoversPromptly() throws Exception {
    var files = enqueue("identity-a.txt", "identity-b.txt", "identity-c.txt");
    when(identities.resolve(anyString(), anyLong()))
        .thenThrow(new IllegalStateException("identity store unavailable"));
    assertShortDeferral(files);
    verifyNoInteractions(writer);
    doAnswer(call -> new DocumentIdentityStore.Identity(
        call.getArgument(0), "uid-" + call.getArgument(0),
        call.getArgument(1), call.getArgument(1)))
        .when(identities).resolve(anyString(), anyLong());
    recover(files);
  }

  @Test
  void repeatedSystemicFaultsStayBoundedWithoutChargingAttempts() throws Exception {
    var files = enqueue("repeat-a.txt", "repeat-b.txt");
    doThrow(new IllegalStateException("writer closed")).when(writer).indexSingle(any());
    for (int round = 0; round < 3; round++) {
      assertShortDeferral(files);
      waitUntilDue(files);
    }
    doNothing().when(writer).indexSingle(any());
    recover(files);
    // A later outage starts with the same short bound, independent of earlier failures.
    var later = enqueue("later-a.txt", "later-b.txt");
    doThrow(new IllegalStateException("writer closed again")).when(writer).indexSingle(any());
    assertShortDeferral(later);
  }

  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      serviceOutages() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(
            false, new IllegalStateException("shared writer unavailable")),
        org.junit.jupiter.params.provider.Arguments.of(false,
            new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DRAINING, "draining", null)),
        org.junit.jupiter.params.provider.Arguments.of(
            true, new IllegalStateException("identity store unavailable")));
  }

  @org.junit.jupiter.params.ParameterizedTest(
      name = "systemicOutagePausesBetweenBatchesEvenWithReadyBacklog({index}: {arguments})")
  @org.junit.jupiter.params.provider.MethodSource("serviceOutages")
  void systemicOutagePausesBetweenBatchesEvenWithReadyBacklog(
      boolean identityFailure, RuntimeException outage)
      throws Exception {
    enqueue("a.txt", "b.txt", "c.txt", "d.txt");
    if (identityFailure) {
      when(identities.resolve(anyString(), anyLong())).thenThrow(outage);
    } else {
      doThrow(outage).when(writer).indexSingle(any());
    }
    var polledTwice = new java.util.concurrent.CountDownLatch(2);
    var pollTimes = new java.util.concurrent.CopyOnWriteArrayList<Long>();
    var firstBatchExit = new java.util.concurrent.atomic.AtomicLong();
    doAnswer(call -> {
      Object result = call.callRealMethod();
      firstBatchExit.compareAndSet(0, System.nanoTime());
      return result;
    }).when(queue).returnUnfinishedClaims(anyCollection());
    // Force small real claims so ready, never-attempted work remains after the first batch.
    doAnswer(call -> {
      pollTimes.add(System.nanoTime());
      var claims = queue.pollPending(2);
      polledTwice.countDown();
      return claims;
    }).when(queue).pollPending(intThat(limit -> limit > 2));
    var running = IndexingLoop.class.getDeclaredField("running");
    running.setAccessible(true);
    ((AtomicBoolean) running.get(loop)).set(false);
    loop.start();
    try {
      assertTrue(polledTwice.await(10, java.util.concurrent.TimeUnit.SECONDS),
          "a service outage must still revisit the backlog promptly");
      long intervalMs = java.util.concurrent.TimeUnit.NANOSECONDS
          .toMillis(pollTimes.get(1) - firstBatchExit.get());
      System.out.println("systemic backlog poll intervalMs=" + intervalMs);
      assertTrue(intervalMs >= 1000, "no hot loop across fresh batches: " + intervalMs);
    } finally {
      loop.close();
    }
  }

  @Test
  void isolatedWriterFailureUsesLadderAndDoesNotStarveLaterFiles() throws Exception {
    var first = enqueue("bad.txt", "healthy.txt");
    doAnswer(call -> {
      var doc = (io.justsearch.indexing.api.IndexDocument) call.getArgument(0);
      if (String.valueOf(doc.fields().get("path")).endsWith("bad.txt")) {
        throw new IllegalStateException("one document rejected");
      }
      return null;
    }).when(writer).indexSingle(any());
    long start = System.currentTimeMillis();
    process(queue.pollPending(10));
    commit();
    assertEquals(1, row(first.getFirst()).attempts());
    assertTrue(row(first.getFirst()).retryAfter() >= start + 60_000);
    assertEquals("DONE", row(first.get(1)).state());
    var later = enqueue("healthy-later.txt");
    process(queue.pollPending(1));
    commit();
    assertEquals("DONE", row(later.getFirst()).state());
  }

  @Test
  void isolatedIdentityFailureUsesLadderAndDoesNotStarveLaterFiles() throws Exception {
    var first = enqueue("bad.txt", "healthy.txt");
    String badHash = DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(first.getFirst()));
    when(identities.resolve(eq(badHash), anyLong()))
        .thenThrow(new IllegalStateException("one identity rejected"));
    long start = System.currentTimeMillis();
    process(queue.pollPending(10));
    commit();
    assertEquals(1, row(first.getFirst()).attempts());
    assertTrue(row(first.getFirst()).retryAfter() >= start + 60_000);
    assertEquals("DONE", row(first.get(1)).state());
    var later = enqueue("healthy-later.txt");
    process(queue.pollPending(1));
    commit();
    assertEquals("DONE", row(later.getFirst()).state());
  }

  @Test
  void documentArgumentFailureUsesLadderEvenWithoutSuccessfulPeers() throws Exception {
    var files = enqueue("invalid-a.txt", "invalid-b.txt");
    doThrow(new IllegalArgumentException("document field too large"))
        .when(writer).indexSingle(any());
    long start = System.currentTimeMillis();
    process(queue.pollPending(10));
    for (Path file : files) {
      assertEquals(1, row(file).attempts());
      assertTrue(row(file).retryAfter() >= start + 60_000);
    }
  }

  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      poisonBatches() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(1, false, new IllegalStateException("poison")),
        org.junit.jupiter.params.provider.Arguments.of(1, false, new NullPointerException("poison")),
        org.junit.jupiter.params.provider.Arguments.of(3, false, new IllegalStateException("poison")),
        org.junit.jupiter.params.provider.Arguments.of(3, false,
            new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DISK_IO, "document IO", null)),
        org.junit.jupiter.params.provider.Arguments.of(3, false,
            new IndexRuntimeIOException(IndexRuntimeIOException.Reason.CONFIGURATION, "missing_uid_field", null)),
        org.junit.jupiter.params.provider.Arguments.of(3, true, new IllegalStateException("poison identity")));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "poisonEventuallyUsesLadder({index}: {arguments})")
  @org.junit.jupiter.params.provider.MethodSource("poisonBatches")
  void poisonEventuallyUsesLadder(int count, boolean identityFailure, RuntimeException failure)
      throws Exception {
    var files = enqueue(java.util.stream.IntStream.range(0, count)
        .mapToObj(i -> "poison-" + i + ".txt").toArray(String[]::new));
    if (identityFailure) when(identities.resolve(anyString(), anyLong())).thenThrow(failure);
    else doThrow(failure).when(writer).indexSingle(any());
    for (int batch = 0; batch < 4; batch++) {
      makeDue();
      process(queue.pollPending(10));
    }
    for (Path file : files) {
      Row state = row(file);
      System.out.println("poison batch size=" + count + " attempts=" + state.attempts()
          + " policy=" + state.policy());
      assertEquals(1, state.attempts(), "ambiguous poison must leave the free-retry grace window");
      assertEquals("RETRY_WITH_BACKOFF", state.policy());
      assertTrue(state.retryAfter() >= System.currentTimeMillis() + 59_000);
    }
    // Exercise the existing durable exhaustion transition without waiting seven days.
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var query = connection.prepareStatement("UPDATE jobs SET first_failed_at = ?")) {
      query.setLong(1, System.currentTimeMillis() - java.time.Duration.ofDays(8).toMillis());
      query.executeUpdate();
    }
    makeDue();
    process(queue.pollPending(10));
    for (Path file : files) assertEquals("RETRY_EXHAUSTED", row(file).state());
    var healthy = enqueue("healthy-after-poison.txt");
    doNothing().when(writer).indexSingle(any());
    doAnswer(call -> new DocumentIdentityStore.Identity(call.getArgument(0), "healthy-uid",
        call.getArgument(1), call.getArgument(1))).when(identities).resolve(anyString(), anyLong());
    process(queue.pollPending(10));
    commit();
    assertEquals("DONE", row(healthy.getFirst()).state());
  }

  @Test
  void successResetsAmbiguousGrace() throws Exception {
    var poison = enqueue("poison.txt");
    doThrow(new IllegalStateException("ambiguous")).when(writer).indexSingle(any());
    for (int batch = 0; batch < 3; batch++) {
      makeDue();
      process(queue.pollPending(10));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var query = connection.prepareStatement("UPDATE jobs SET retry_after = ? WHERE state = 'PENDING'")) {
      query.setLong(1, System.currentTimeMillis() + 60_000);
      query.executeUpdate();
    }
    var healthy = enqueue("healthy.txt");
    doNothing().when(writer).indexSingle(any());
    process(queue.pollPending(10));
    commit();
    assertEquals("DONE", row(healthy.getFirst()).state());
    doThrow(new IllegalStateException("ambiguous again")).when(writer).indexSingle(any());
    makeDue();
    process(queue.pollPending(10));
    assertEquals(0, row(poison.getFirst()).attempts(), "service success grants a fresh grace window");
    for (int batch = 0; batch < 3; batch++) {
      makeDue();
      process(queue.pollPending(10));
    }
    assertEquals(1, row(poison.getFirst()).attempts());
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "provenOutageBacksOffCapsLogsAndResets({0})")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void provenOutageBacksOffCapsLogsAndResets(boolean identityFailure) throws Exception {
    var files = enqueue("outage-a.txt", "outage-b.txt");
    var fault = new AtomicBoolean(true);
    if (identityFailure) {
      doAnswer(call -> {
        if (fault.get()) throw new IllegalStateException("shared SQLite busy",
            new java.sql.SQLException("database busy", null, 5));
        return new DocumentIdentityStore.Identity(call.getArgument(0), "uid-" + call.getArgument(0),
            call.getArgument(1), call.getArgument(1));
      }).when(identities).resolve(anyString(), anyLong());
    } else {
      doAnswer(call -> {
        if (fault.get()) throw new IndexRuntimeIOException(
            IndexRuntimeIOException.Reason.DISK_FULL, "shared disk full", null);
        return null;
      }).when(writer).indexSingle(any());
    }
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    var times = new java.util.ArrayList<Long>();
    var batches = new java.util.concurrent.CountDownLatch(8);
    // Force real rows due so this observes the service pause, independent of per-row SQL deadlines.
    doAnswer(call -> {
      times.add(System.nanoTime());
      makeDue();
      if (times.size() == 6) {
        for (Path file : files) assertEquals(0, row(file).attempts(), "proven outage is free past cap");
        fault.set(false);
        enqueue("healthy-after-outage.txt");
      }
      if (times.size() == 7) {
        commit();
        assertEquals("DONE", row(files.getFirst()).state());
        fault.set(true);
        enqueue("new-outage.txt");
      }
      var claims = queue.pollPending(10);
      batches.countDown();
      return claims;
    }).when(queue).pollPending(intThat(limit -> limit > 10));
    var running = IndexingLoop.class.getDeclaredField("running");
    running.setAccessible(true);
    ((AtomicBoolean) running.get(loop)).set(false);
    loop.start();
    try {
      assertTrue(batches.await(40, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      loop.close();
      logger.detachAppender(appender);
      appender.stop();
    }
    var intervals = new java.util.ArrayList<Long>();
    for (int i = 1; i < Math.min(times.size(), 6); i++) intervals.add(
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1)));
    System.out.println("proven outage poll intervalsMs=" + intervals);
    long resetPause = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(times.get(7) - times.get(6));
    long errors = appender.list.stream().filter(event -> event.getLevel()
        .isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)).count();
    assertAll(
        () -> assertTrue(intervals.get(1) >= 2_000, "second pause must grow beyond one second"),
        () -> assertTrue(intervals.get(3) >= 8_000 && intervals.get(4) >= 8_000,
            "outage reaches the eight-second cap"),
        () -> assertTrue(intervals.get(4) < 10_000, "pause is capped at eight seconds"),
        () -> assertTrue(intervals.stream().mapToLong(Long::longValue).sum() > 8_000,
            "fault survives longer than cap"),
        () -> assertTrue(resetPause >= 1_000 && resetPause < 2_000,
            "success resets pause: " + resetPause),
        () -> assertEquals(1, errors, "one batch summary, no per-document or per-second log flood"));
    for (Path file : files) {
      assertEquals("DONE", row(file).state());
      assertEquals(0, row(file).attempts());
    }
    assertEquals("DONE", row(tempDir.resolve("healthy-after-outage.txt")).state());
  }

  private void makeDue() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var query = connection.prepareStatement("UPDATE jobs SET retry_after = 0 WHERE state = 'PENDING'")) {
      query.executeUpdate();
    }
  }

  private List<Path> enqueue(String... names) throws Exception {
    var files = new java.util.ArrayList<Path>();
    for (String name : names) files.add(Files.writeString(tempDir.resolve(name), "body"));
    queue.enqueueEntries(files.stream().map(file -> JobQueue.EnqueueEntry.stat(file)
        .withinRoot(tempDir)).toList());
    return files;
  }

  private void assertShortDeferral(List<Path> files) throws Exception {
    long start = System.currentTimeMillis();
    var claims = queue.pollPending(10);
    assertEquals(files.size(), claims.size());
    process(claims);
    for (Path file : files) {
      Row state = row(file);
      long delay = state.retryAfter() - start;
      System.out.println(file.getFileName() + " state=" + state.state()
          + " attempts=" + state.attempts() + " retryDelayMs=" + delay);
    }
    for (Path file : files) {
      Row state = row(file);
      long delay = state.retryAfter() - start;
      assertAll(() -> assertEquals("PENDING", state.state()),
          () -> assertEquals(0, state.attempts(), "a service outage must not spend a file attempt"),
          () -> assertTrue(delay >= 1000 && delay <= 5000, "retry within seconds: " + delay),
          () -> assertEquals("DEFER_WITHOUT_ATTEMPT", state.policy()));
    }
    assertTrue(queue.pollPending(10).isEmpty(), "no hot retry before the scheduled deadline");
  }

  private void recover(List<Path> files) throws Exception {
    waitUntilDue(files);
    var claims = queue.pollPending(10);
    assertEquals(files.size(), claims.size(), "the whole recovered batch is claimable at once");
    process(claims);
    commit();
    for (Path file : files) {
      assertEquals("DONE", row(file).state());
      assertEquals(0, row(file).attempts());
    }
  }

  private void waitUntilDue(List<Path> files) throws Exception {
    long latest = 0;
    for (Path file : files) latest = Math.max(latest, row(file).retryAfter());
    long wait = latest - System.currentTimeMillis();
    assertTrue(wait <= 5000, "test never sleeps through a file retry ladder");
    if (wait > 0) Thread.sleep(wait + 5);
  }

  private void process(List<JobQueue.IndexJob> claims) throws Exception {
    var method = IndexingLoop.class.getDeclaredMethod("processBatch", List.class);
    method.setAccessible(true);
    method.invoke(loop, claims);
  }

  private void commit() throws Exception {
    var method = IndexingLoop.class.getDeclaredMethod("finishIdleCommit");
    method.setAccessible(true);
    method.invoke(loop);
  }

  private Row row(Path file) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var query = connection.prepareStatement(
            "SELECT state, attempts, retry_after, last_retry_policy FROM jobs WHERE path = ?")) {
      query.setString(1, PathNormalizer.normalizeKey(file));
      try (var result = query.executeQuery()) {
        assertTrue(result.next());
        return new Row(result.getString(1), result.getInt(2), result.getLong(3), result.getString(4));
      }
    }
  }

  private record Row(String state, int attempts, long retryAfter, String policy) {}
}

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
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.StatusRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifier D4 I7: queue eligibility and public status must agree with a running outage pause. */
final class OutageWaitingStatusTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "outageBacklogWaitsUntilTheLoopResumesAndRecoversWithinSeconds({0})")
  @ValueSource(strings = {"writer", "identity", "draining", "mixed"})
  void outageBacklogWaitsUntilTheLoopResumesAndRecoversWithinSeconds(String service) throws Exception {
    Path database = directory.resolve("jobs.db");
    var unavailable = new AtomicBoolean(true);
    var written = new AtomicInteger();
    var signals = mock(WorkerSignalBus.class);
    var writer = mock(IndexingCoordinator.class);
    doAnswer(call -> {
      if (unavailable.get() && !service.equals("identity")) {
        throw new IndexRuntimeIOException(service.equals("draining")
            ? IndexRuntimeIOException.Reason.DRAINING : IndexRuntimeIOException.Reason.DISK_FULL,
            "shared writer outage", null);
      }
      written.incrementAndGet();
      return null;
    }).when(writer).indexSingle(any());
    var identities = mock(DocumentIdentityStore.class);
    when(identities.resolve(anyString(), anyLong())).thenAnswer(call -> {
      if (unavailable.get() && (service.equals("identity")
          || (service.equals("mixed") && call.<String>getArgument(0).equals(firstPathHash)))) {
        throw new IllegalStateException("shared identity outage", new SQLException("READONLY", "", 8));
      }
      return new DocumentIdentityStore.Identity(call.getArgument(0), "uid-" + call.getArgument(0),
          call.getArgument(1), call.getArgument(1));
    });
    var content = mock(TimeboxedContentExtractor.class);
    when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    when(content.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
        new ContentExtractor.ExtractionResult("healthy extracted content", null, "text/plain"),
        TikaExtractionPolicy.defaults(), "test", false));

    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      Path first = Files.writeString(directory.resolve("waiting-a.txt"), "body");
      Path second = Files.writeString(directory.resolve("waiting-b.txt"), "body");
      firstPathHash = DocumentIdentityStore.pathHash(
          io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(first));
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(first).withinRoot(directory),
          JobQueue.EnqueueEntry.stat(second).withinRoot(directory)));
      try (var loop = new IndexingLoop(null, null, queue, writer, mock(CommitOps.class),
          mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> null, signals,
          IndexingPacing.unthrottled(), null, null, null, null, content, null,
          new IndexingLoopOptions(false, null, identities, null, null, null))) {
        var status = new WorkerIngestService(queue, loop, signals, IndexingPacing.unthrottled(),
            directory, directory.resolve("index"), null, null, null, 0L);
        loop.start();
        await(() -> cappedPause(loop), 16_000);
        var rows = rows(database);
        long retryAt = rows.getFirst().retryAt();
        // The old one-second deadline expires here while the owner still sleeps for eight seconds.
        Thread.sleep(1_250);
        var response = status.indexStatus(StatusRequest.getDefaultInstance(), CallContext.none());
        System.out.println(service + " capped pause status=" + response.getCore().getState()
            + " ready=" + response.getMigration().getPendingReadyJobsCount()
            + " processing=" + response.getMigration().getProcessingJobsCount() + " rows=" + rows(database));
        assertEquals("WAITING", response.getCore().getState(),
            "outage backlog waits through the capped service pause (verifier I7)");
        assertEquals(0, response.getMigration().getPendingReadyJobsCount());
        assertEquals(0, response.getMigration().getProcessingJobsCount());
        assertEquals(retryAt, response.getFailure().getNextRetryAtMs());
        assertTrue(retryAt > System.currentTimeMillis(), "deadline remains future during the pause");
        assertTrue(retryAt <= System.currentTimeMillis() + 8_000, "deferral stays within seconds");
        assertTrue(rows.stream().allMatch(row -> row.attempts() == 0 && row.retryAt() == retryAt),
            "both services share the same uncounted batch deadline");
        assertTrue(queue.pollPending(10).isEmpty(), "durable claims remain ineligible during pause");
        unavailable.set(false);
        long recovered = System.nanoTime();
        await(() -> written.get() == 2, 9_000);
        System.out.println(service + " recoveryMs=" + (System.nanoTime() - recovered) / 1_000_000);
        assertTrue(System.currentTimeMillis() >= retryAt, "claims resume when their deadline arrives");
      }
      assertTrue(rows(database).stream().allMatch(row -> row.attempts() == 0 && row.state().equals("DONE")),
          "cooperative stop commits all recovered rows without losing or charging them");
    }
  }

  private String firstPathHash;

  private record Row(String state, int attempts, long retryAt) {}

  private static List<Row> rows(Path database) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var query = connection.createStatement();
        var result = query.executeQuery("SELECT state, attempts, retry_after FROM jobs ORDER BY path")) {
      var rows = new ArrayList<Row>();
      while (result.next()) rows.add(new Row(result.getString(1), result.getInt(2), result.getLong(3)));
      return rows;
    }
  }

  private static boolean cappedPause(IndexingLoop loop) throws Exception {
    var field = IndexingLoop.class.getDeclaredField("loopThread");
    field.setAccessible(true);
    Thread owner = (Thread) field.get(loop);
    boolean sleeping = Arrays.stream(owner.getStackTrace())
        .anyMatch(frame -> frame.getMethodName().equals("sleepAfterError"));
    var writer = IndexingLoop.class.getDeclaredField("writer");
    writer.setAccessible(true);
    var extractor = IndexingLoop.class.getDeclaredField("extractor");
    extractor.setAccessible(true);
    return sleeping && Math.max(((JobBatchWriter) writer.get(loop)).retryDelayMs(),
        ((JobBatchExtractor) extractor.get(loop)).retryDelayMs()) == 8_000;
  }

  private static void await(Check check, long timeoutMs) throws Exception {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000;
    while (!check.ready() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(check.ready(), "running loop did not reach the expected boundary within " + timeoutMs + " ms");
  }

  @FunctionalInterface
  private interface Check { boolean ready() throws Exception; }
}

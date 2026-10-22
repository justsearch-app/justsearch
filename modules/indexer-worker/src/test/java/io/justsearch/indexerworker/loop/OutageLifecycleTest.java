/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** U17 lifecycle boundaries use the running loop and durable claims, without interrupting Lucene. */
final class OutageLifecycleTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private SqliteJobQueue queue;
  private IndexingLoop loop;
  private Path database;
  private RunningRuntime runtime;

  @BeforeEach
  void openQueue() throws Exception {
    database = directory.resolve("jobs.db");
    queue = new SqliteJobQueue(database);
    queue.open();
  }

  @AfterEach
  void closeResources() throws Exception {
    if (loop != null) {
      // The before-state deliberately fails its first close after five seconds. Let that
      // owner's eight-second sleep finish before retrying; never interrupt a Lucene owner.
      try {
        loop.close();
      } catch (java.io.IOException stillStopping) {
        Thread owner = owner();
        if (owner != null) owner.join(9_000);
        loop.close();
      }
    }
    if (runtime != null) runtime.close();
    if (queue != null) queue.close();
  }

  @Test
  void stopDuringCappedOutagePauseWakesWithoutInterruptingOrChargingRows() throws Exception {
    var writer = mock(IndexingCoordinator.class);
    doThrow(new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DISK_FULL,
        "outage during shutdown", null)).when(writer).indexSingle(any());
    loop = newLoop(writer, mock(CommitOps.class));
    enqueue("stop-a.txt", "stop-b.txt");
    loop.start();
    await(() -> retryPause() == 8_000 && inRetryPause(), 15_000);
    Thread owner = owner();

    long start = System.nanoTime();
    assertDoesNotThrow(loop::close, "stop must wake the capped pause within the five-second join");
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    System.out.println("capped outage close elapsedMs=" + elapsed);
    assertTrue(elapsed < 5_000, "cooperative stop exceeded its join budget: " + elapsed);
    assertFalse(owner.isAlive());
    assertFalse(owner.isInterrupted(), "Lucene owner must never be interrupted to wake its pause");
    queue.close();
    queue.open();
    assertRows(2, "PENDING", 0);
    assertEquals("body", Files.readString(directory.resolve("stop-a.txt")));
    assertEquals("body", Files.readString(directory.resolve("stop-b.txt")));
  }

  @Test
  void closedLuceneRuntimeDefersBeyondGraceAndRecoversThroughTheRunningLoop() throws Exception {
    var builder = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(directory.resolve("index"))
        .withExecutorRegistrations(testLuceneExecutors());
    runtime = builder.open();
    var active = new AtomicReference<>(runtime);
    runtime.close();
    var failedWrites = new AtomicInteger();
    var writer = mock(IndexingCoordinator.class);
    doAnswer(call -> {
      try {
        active.get().indexingCoordinator().indexSingle(call.getArgument(0));
      } catch (RuntimeException failure) {
        failedWrites.incrementAndGet();
        throw failure;
      }
      return null;
    }).when(writer).indexSingle(any());
    var commits = mock(CommitOps.class);
    doAnswer(call -> {
      active.get().commitOps().commitAndTrack(call.getArgument(0));
      return null;
    }).when(commits).commitAndTrack(any(CommitReason.class));
    loop = newLoop(writer, commits);
    enqueue("closed-a.txt", "closed-b.txt");
    loop.start();
    await(() -> failedWrites.get() >= 8 && pendingRows() == 2, 15_000);
    assertRows(2, "PENDING", 0);
    await(this::inRetryPause, 1_000);
    assertEquals(8_000, retryPause(), "an absent writer remains a proven outage past grace");

    runtime = builder.open();
    active.set(runtime);
    enqueue("recovered-a.txt", "recovered-b.txt");
    await(() -> doneRows() == 4, 12_000);
    assertRows(4, "DONE", 0);
    runtime.commitOps().maybeRefreshBlocking();
    assertTrue(runtime.indexCountOps().docCount() >= 4, "recovered rows reached the real index");
    System.out.println("closed-runtime failures=" + failedWrites.get() + "; recovered rows=4 attempts=0");
  }

  private IndexingLoop newLoop(IndexingCoordinator writer, CommitOps commits) throws Exception {
    var identities = mock(DocumentIdentityStore.class);
    when(identities.resolve(anyString(), anyLong())).thenAnswer(call ->
        new DocumentIdentityStore.Identity(call.getArgument(0), "uid-" + call.getArgument(0),
            call.getArgument(1), call.getArgument(1)));
    var content = mock(TimeboxedContentExtractor.class);
    when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    when(content.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
        new ContentExtractor.ExtractionResult("searchable body", null, "text/plain"),
        TikaExtractionPolicy.defaults(), "test-structured", false));
    return new IndexingLoop(null, null, queue, writer, commits,
        mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> null,
        mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null,
        content, null, new IndexingLoopOptions(false, null, identities, null, null, null));
  }

  private void enqueue(String... names) throws Exception {
    var files = new java.util.ArrayList<Path>();
    for (String name : names) files.add(Files.writeString(directory.resolve(name), "body"));
    queue.enqueueEntries(files.stream().map(file -> JobQueue.EnqueueEntry.stat(file)
        .withinRoot(directory)).toList());
  }

  private Thread owner() throws Exception {
    var field = IndexingLoop.class.getDeclaredField("loopThread");
    field.setAccessible(true);
    return (Thread) field.get(loop);
  }

  private long retryPause() throws Exception {
    var field = IndexingLoop.class.getDeclaredField("writer");
    field.setAccessible(true);
    return ((JobBatchWriter) field.get(loop)).retryDelayMs();
  }

  private boolean inRetryPause() throws Exception {
    return Arrays.stream(owner().getStackTrace())
        .anyMatch(frame -> frame.getMethodName().equals("sleepAfterError"));
  }

  private int pendingRows() throws Exception { return countRows("PENDING"); }
  private int doneRows() throws Exception { return countRows("DONE"); }

  private int countRows(String state) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var query = connection.prepareStatement("SELECT COUNT(*) FROM jobs WHERE state = ?")) {
      query.setString(1, state);
      try (var rows = query.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
    }
  }

  private void assertRows(int count, String state, int attempts) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var query = connection.prepareStatement("SELECT state, attempts FROM jobs");
        var rows = query.executeQuery()) {
      int seen = 0;
      while (rows.next()) {
        assertEquals(state, rows.getString(1));
        assertEquals(attempts, rows.getInt(2), "lifecycle unavailability must not charge attempts");
        seen++;
      }
      assertEquals(count, seen, "all durable claims remain accounted for");
    }
  }

  private static void await(Check check, long timeoutMs) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (!check.ready() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(check.ready(), "running loop did not reach the expected boundary within " + timeoutMs + " ms");
  }

  @FunctionalInterface
  private interface Check { boolean ready() throws Exception; }
}

package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 406 Gap G: tests for {@link RunningRuntime#drainAndClose}.
 *
 * <p>Validates: (1) writes accepted before drain land in the final commit; (2) writes
 * attempted after {@code session.draining=true} fail with a typed
 * {@link IndexRuntimeIOException} carrying {@link IndexRuntimeIOException.Reason#DRAINING}
 * so callers can retry on the upgraded holder reference (tempdoc 410 V1 promoted this from
 * IllegalStateException); (3) close runs even if the queue does not drain in time
 * (best-effort).
 */
class DrainAndCloseTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  @Test
  void writesBeforeDrainLandInFinalCommit() throws Exception {
    Path indexPath = tempDir.resolve("drain-pending");
    Files.createDirectories(indexPath);
    var runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();

    // Index 5 docs without committing (so they're pending).
    for (int i = 0; i < 5; i++) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "doc-" + i,
                      SchemaFields.DOC_UID, "doc-" + i + "#0",
                      SchemaFields.CONTENT, "content " + i)));
    }
    // docCount() reads via the searcher (NRT-visible only after refresh). Since we haven't
    // refreshed, "before" should be 0 — the assertion is on the post-drain reopen value.
    long before = runtime.indexCountOps().docCount();

    runtime.drainAndClose(Duration.ofSeconds(5));

    // Reopen and verify the 5 docs are committed (final drain commit ran).
    var reopened =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();
    try {
      reopened.commitOps().maybeRefreshBlocking();
      long after = reopened.indexCountOps().docCount();
      assertEquals(before + 5, after, "drain-final commit should persist all 5 pending docs");
    } finally {
      reopened.close();
    }
  }

  @Test
  void writesAfterDrainFlagThrowDrainingIndexRuntimeIOException() throws Exception {
    Path indexPath = tempDir.resolve("drain-rejects");
    Files.createDirectories(indexPath);
    var runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();
    try {
      // Set the drain flag manually (drainAndClose does this then awaits queue → 0).
      // Tempdoc 410 V1 changed the rejection from IllegalStateException to a typed
      // IndexRuntimeIOException(DRAINING) so the IndexingLoop can defer the path with
      // WRITE_UNAVAILABLE_DRAINING instead of failing it.
      runtime.session().draining = true;
      IndexRuntimeIOException ex =
          assertThrows(
              IndexRuntimeIOException.class,
              () ->
                  runtime
                      .indexingCoordinator()
                      .indexSingle(
                          new IndexDocument(
                              Map.of(
                                  SchemaFields.DOC_ID, "doc-late",
                                  SchemaFields.DOC_UID, "doc-late#0"))));
      assertEquals(IndexRuntimeIOException.Reason.DRAINING, ex.reason());
      assertTrue(
          ex.getMessage().toLowerCase().contains("drain")
              || ex.getMessage().contains("not writable"),
          "expected drain-related ISE, got: " + ex.getMessage());
    } finally {
      runtime.session().draining = false; // reset so close path doesn't re-trigger
      runtime.close();
    }
  }

  @Test
  void drainAndCloseCompletesEvenWithoutPendingWork() throws Exception {
    Path indexPath = tempDir.resolve("drain-empty");
    Files.createDirectories(indexPath);
    var runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();
    long commitsBefore = runtime.session().commitCount.get();
    // No pending writes. drainAndClose should be a fast no-op + close (no commit, item 6).
    long startNanos = System.nanoTime();
    runtime.drainAndClose(Duration.ofSeconds(5));
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    assertTrue(
        elapsedMs < 1000, "drainAndClose with empty queue should complete quickly, took: " + elapsedMs + "ms");
    // Item 6: empty drain skips the noise commit.
    assertEquals(
        commitsBefore,
        runtime.session().commitCount.get(),
        "drain with no pending docs should not increment commitCount");
  }

  /**
   * Item 14: drainAndClose acquires the writeBarrier write lock — blocks until in-flight writers
   * release the read lock. Validates that a write started before drain completes successfully and
   * lands in the final commit; a write started after drain returns throws ISE.
   */
  @Test
  // Timing-sensitive: signals writerStarted before indexSingle, then relies on a fixed 10ms
  // sleep to assume the writer holds the readLock when drain runs. Deterministic only with a
  // production pause-hook mid-critical-section; on Linux CI the writer can arrive after draining
  // starts and (correctly) get runtime_draining. Runs on the windows-native lane (tempdoc 668).
  @Tag("windows")
  void drainAndCloseWaitsForInFlightWriter() throws Exception {
    Path indexPath = tempDir.resolve("drain-inflight");
    Files.createDirectories(indexPath);
    var runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();

    // Start a writer that holds the readLock for ~200ms by simulating slow validation
    // via a tight loop that submits one doc then sleeps. The drainAndClose call must wait
    // for the writer's readLock to release before acquiring its writeLock.
    CountDownLatch writerStarted = new CountDownLatch(1);
    AtomicReference<Throwable> writerError = new AtomicReference<>();
    Thread writer =
        new Thread(
            () -> {
              try {
                writerStarted.countDown();
                runtime
                    .indexingCoordinator()
                    .indexSingle(
                        new IndexDocument(
                            Map.of(
                                SchemaFields.DOC_ID, "early",
                                SchemaFields.DOC_UID, "early#0",
                                SchemaFields.CONTENT, "early body")));
              } catch (Throwable t) {
                writerError.set(t);
              }
            },
            "drain-test-writer");
    writer.start();

    // Wait until the writer thread has started (so its lock-acquire is in flight).
    assertTrue(
        writerStarted.await(5, TimeUnit.SECONDS), "writer thread should start within 5s");
    // Tiny pause to let writer reach indexSingle. The writeBarrier readLock is acquired before
    // the index call returns; drainAndClose's writeLock will block until writer releases.
    Thread.sleep(10);

    runtime.drainAndClose(Duration.ofSeconds(5));
    writer.join(5_000L);

    assertNull(
        writerError.get(),
        "writer that started before drain should complete cleanly; got: " + writerError.get());

    // Reopen and verify the doc landed in the final commit.
    var reopened =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();
    try {
      reopened.commitOps().maybeRefreshBlocking();
      assertEquals(
          1,
          reopened.indexCountOps().docCount(),
          "writer's doc should have landed in the drain-final commit");
    } finally {
      reopened.close();
    }
  }

  /**
   * C1 close correction supersedes item 13's close-anyway contract: a timed-out write keeps
   * its writer alive and the retained runtime can be closed after actual write exit.
   */
  @Test
  void drainAndCloseTimeoutRetainsWriterUntilRetry() throws Exception {
    Path indexPath = tempDir.resolve("drain-timeout");
    Files.createDirectories(indexPath);
    var runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .open();

    // Acquire the readLock manually from another thread and hold it longer than the drain timeout.
    // drainAndClose must give up waiting without invalidating the live writer.
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Thread lockHolder =
        new Thread(
            () -> {
              runtime.session().writeBarrier.readLock().lock();
              try {
                lockHeld.countDown();
                releaseLock.await(3, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              } finally {
                runtime.session().writeBarrier.readLock().unlock();
              }
            },
            "drain-test-lock-holder");
    lockHolder.setDaemon(true);
    lockHolder.start();
    assertTrue(lockHeld.await(2, TimeUnit.SECONDS), "lock holder should acquire readLock");

    long startNanos = System.nanoTime();
    var snapshot = runtime.session().snapshot;
    var starts = new java.util.concurrent.atomic.AtomicInteger();
    var completions = new java.util.concurrent.atomic.AtomicInteger();
    var timeouts = new java.util.concurrent.atomic.AtomicInteger();
    runtime.session().telemetryEvents = new LuceneRuntimeTypes.TelemetryEvents() {
      @Override public void onSwapStart(SwapReason reason) { starts.incrementAndGet(); }
      @Override public void onSwapComplete(long durationMs, SwapReason reason) { completions.incrementAndGet(); }
      @Override public void onDrainTimeout(long elapsedMs, long writesStillPending) { timeouts.incrementAndGet(); }
    };
    assertThrows(IllegalStateException.class, () -> runtime.drainAndClose(Duration.ofMillis(100)));
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

    // Drain returned within its timeout, retaining ownership for the next close attempt.
    assertTrue(
        elapsedMs < 1000,
        "drainAndClose should return promptly after timeout, took: " + elapsedMs + "ms");
    assertSame(snapshot, runtime.session().snapshot);
    assertTrue(snapshot.writer().isOpen(), "the in-flight write still owns the writer");
    assertEquals(1, starts.get());
    assertEquals(1, completions.get(), "a failed drain still terminates its telemetry attempt");
    assertEquals(1, timeouts.get());

    releaseLock.countDown();
    lockHolder.join(2_000L);
    runtime.drainAndClose(Duration.ofSeconds(1));
    assertNull(runtime.session().snapshot);
    assertFalse(snapshot.writer().isOpen());
    assertEquals(2, starts.get());
    assertEquals(2, completions.get());
  }
}

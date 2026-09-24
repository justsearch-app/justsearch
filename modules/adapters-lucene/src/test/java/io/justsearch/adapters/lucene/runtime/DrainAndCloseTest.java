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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * IllegalStateException); (3) a failed drain retains resources for a later bounded retry.
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

    assertTrue(runtime.isAcceptingWrites());
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
    assertFalse(runtime.isAcceptingWrites(), "closed runtime must remain unavailable after the swap lock releases");

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
      assertFalse(runtime.isAcceptingWrites(), "draining fences new work even before close completes");
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
      assertFalse(runtime.isAcceptingWrites(), "ordinary close also fences writes without a drain flag");
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
   * lands in the final commit; a write started after drain returns throws ISE. The writer is paused
   * through the existing post-admission write-path supplier seam, so the test observes the
   * production-owned read lease without acquiring a test-owned barrier lock.
   */
  @Test
  // Windows-native exercises the real Lucene directory and waits for production admission before
  // drain begins, so the earlier write and its read lease are observable.
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

    CountDownLatch writePathSupplierEntered = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    AtomicInteger writerReadHoldCount = new AtomicInteger(-1);
    AtomicReference<Throwable> writerError = new AtomicReference<>();
    IndexingCoordinator writerCoordinator =
        new IndexingCoordinator(
            runtime.session(),
            () -> {
              writerReadHoldCount.set(runtime.session().writeBarrier.getReadHoldCount());
              writePathSupplierEntered.countDown();
              try {
                if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                  throw new AssertionError("test did not release the paused write-path admission");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("paused write-path admission was interrupted", interrupted);
              }
              return runtime.session().writePathOps;
            });
    Thread writer =
        new Thread(
            () -> {
              try {
                writerCoordinator
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
    AtomicReference<Throwable> drainError = new AtomicReference<>();
    AtomicBoolean drainCompleted = new AtomicBoolean();
    Thread drainer = new Thread(() -> {
      try {
        runtime.drainAndClose(Duration.ofSeconds(10));
        drainCompleted.set(true);
      } catch (Throwable failure) {
        drainError.set(failure);
      }
    }, "drain-test-closer");
    Throwable primaryFailure = null;
    try {
      assertTrue(
          writePathSupplierEntered.await(10, TimeUnit.SECONDS),
          "the writer must reach the existing post-admission write-path seam");
      assertEquals(
          1,
          writerReadHoldCount.get(),
          "production indexSingle must hold the write-barrier read lease before write-path admission");
      drainer.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((!runtime.session().draining
              || !runtime.session().writeBarrier.hasQueuedThread(drainer))
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue(runtime.session().draining, "drain must enter while the writer retains its lease");
      assertTrue(
          runtime.session().writeBarrier.hasQueuedThread(drainer),
          "drain must queue on the write barrier behind the production writer lease");
    } catch (Throwable failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      releaseWriter.countDown();
      writer.join(10_000L);
      drainer.join(10_000L);
      if (writer.isAlive()) writer.interrupt();
      if (drainer.isAlive()) drainer.interrupt();
      if (writer.isAlive()) writer.join(1_000L);
      if (drainer.isAlive()) drainer.join(1_000L);
      if (!writer.isAlive() && !drainer.isAlive() && !drainCompleted.get()) {
        try {
          // The closed admission bit can precede a failed resource close. Retry after both
          // test threads exit unless their drain actually completed.
          runtime.drainAndClose(Duration.ofSeconds(3));
        } catch (RuntimeException | Error cleanupFailure) {
          if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
          else throw cleanupFailure;
        }
      }
    }

    assertFalse(writer.isAlive(), "the held writer must release its lease");
    assertFalse(drainer.isAlive(), "drain must complete after the writer releases its lease");
    assertNull(drainError.get(), "drain failed: " + drainError.get());

    assertNull(
        writerError.get(),
        "writer that started before drain should complete cleanly; got: " + writerError.get());
    assertThrows(IndexRuntimeIOException.class, () -> runtime.indexingCoordinator().indexSingle(
        new IndexDocument(Map.of(SchemaFields.DOC_ID, "late", SchemaFields.DOC_UID, "late#0"))));

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
    var starts = new AtomicInteger();
    var completions = new AtomicInteger();
    var timeouts = new AtomicInteger();
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
    assertFalse(runtime.isAcceptingWrites(), "failed drain retains resources without accepting new work");
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

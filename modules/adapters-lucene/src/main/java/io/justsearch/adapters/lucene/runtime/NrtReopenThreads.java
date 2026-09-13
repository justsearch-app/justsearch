/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single construction site for the NRT {@link ControlledRealTimeReopenThread}.
 *
 * <p>Tempdoc 885 item 19: the initial open ({@link ComponentsFactory}) hardcoded 0.5s/0.05s while
 * the post-bulk-backfill rebuild ({@link CommitOps#resumeNrtRefresh()}) read the configured
 * {@code index.nrt.*} values, so a configured cadence silently took effect only after the first
 * backfill. Both sites now go through {@link #create}, which is the only place the ms-to-seconds
 * conversion and the Lucene argument order exist.
 *
 * <p>Lucene's constructor throws when {@code targetMaxStaleSec < targetMinStaleSec}. The two
 * JustSearch knobs do not enforce that order — {@code index.nrt.max_stale_ms} reads as the larger
 * bound but is passed as Lucene's <em>min</em> stale — so {@code hardMaxStaleMs} is clamped to the
 * target rather than propagating an invalid pair into a construction failure.
 */
final class NrtReopenThreads {

  private static final Logger log = LoggerFactory.getLogger(NrtReopenThreads.class);

  private NrtReopenThreads() {}

  /** One retained close attempt: retries wait on the same owner instead of spawning more tasks. */
  static final class CloseAttempt {
    private final ControlledRealTimeReopenThread<IndexSearcher> thread;
    private final ExecutorService executor;
    private final Future<?> completion;

    CloseAttempt(ControlledRealTimeReopenThread<IndexSearcher> thread,
        LuceneExecutorRegistrations registrations) {
      this.thread = thread;
      executor = registrations.openNrtClose();
      try {
        // Lucene exposes only close(), which signals finish and then joins without a timeout.
        // Interrupt alone does not stop its zero-delay loop. Keep this registered task owned
        // until public close has also finalized the generation waiters after actual NRT exit.
        completion = executor.submit(thread::close);
      } finally {
        executor.shutdown();
      }
    }

    void awaitUntil(long deadlineNanos) {
      boolean interrupted = Thread.interrupted();
      try {
        while (!executor.isTerminated()) {
          long remaining = deadlineNanos - System.nanoTime();
          if (remaining <= 0) {
            log.warn("NRT close deadline exceeded; head.lucene.nrt-close owns thread {} state={}",
                thread.getName(), thread.getState());
            throw new IllegalStateException("NRT reopen close still pending after deadline");
          }
          try { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
          catch (InterruptedException expected) { interrupted = true; }
        }
        try { completion.get(0, TimeUnit.NANOSECONDS); }
        catch (InterruptedException expected) {
          interrupted = true;
          throw new IllegalStateException("Interrupted reading completed NRT close", expected);
        } catch (ExecutionException failure) {
          if (failure.getCause() instanceof Error error) throw error;
          throw new IllegalStateException("NRT reopen close failed", failure.getCause());
        } catch (TimeoutException failure) {
          throw new IllegalStateException("NRT executor terminated before close completed", failure);
        }
        if (thread.isAlive()) throw new IllegalStateException("NRT close returned with a live thread");
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }

    boolean isTerminated() { return executor.isTerminated(); }

    boolean owns(ControlledRealTimeReopenThread<IndexSearcher> candidate) {
      return thread == candidate;
    }
  }

  /**
   * Builds a named daemon reopen thread from the configured staleness bounds. The caller starts it.
   *
   * @param writer the index writer the thread reopens against
   * @param manager the searcher manager the thread refreshes
   * @param targetMaxStaleMs background reopen target; Lucene's third constructor argument
   * @param hardMaxStaleMs reopen target while a caller waits on a generation; Lucene's fourth
   *     constructor argument, clamped to {@code targetMaxStaleMs}
   */
  static ControlledRealTimeReopenThread<IndexSearcher> create(
      IndexWriter writer, SearcherManager manager, long targetMaxStaleMs, long hardMaxStaleMs) {
    long tighterMs = Math.min(hardMaxStaleMs, targetMaxStaleMs);
    if (tighterMs != hardMaxStaleMs) {
      log.warn(
          "index.nrt.max_stale_ms ({}) exceeds index.nrt.target_max_stale_ms ({}); clamping the "
              + "waiting-reopen target to {}ms",
          hardMaxStaleMs,
          targetMaxStaleMs,
          tighterMs);
    }
    ControlledRealTimeReopenThread<IndexSearcher> thread =
        new ControlledRealTimeReopenThread<>(
            writer, manager, targetMaxStaleMs / 1000.0, tighterMs / 1000.0);
    thread.setName("crtrt");
    thread.setDaemon(true);
    return thread;
  }
}

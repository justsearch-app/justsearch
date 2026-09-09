/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-write phase of a Lucene runtime — exposes {@link IndexingCoordinator},
 * {@link WritePathOps}, and {@link PruneOps} in addition to the read-side ops
 * shared by all phases.
 *
 * <p>Single-shot lifecycle; {@link #close()} is terminal. To "restart" build a
 * new {@code RunningRuntime} via {@link #origin()} and swap it into the
 * consumer's holder field.
 */
public final class RunningRuntime implements LuceneRuntime {

  private static final Logger log = LoggerFactory.getLogger(RunningRuntime.class);

  private final IndexSchema schema;
  private final LuceneRuntimeBuilder origin;
  private final RuntimeSession session;

  RunningRuntime(IndexSchema schema, LuceneRuntimeBuilder origin, RuntimeSession session) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.origin = Objects.requireNonNull(origin, "origin");
    this.session = Objects.requireNonNull(session, "session");
  }

  // ==========================================================================
  // Phase-specific ops (write side) — only on RunningRuntime
  // ==========================================================================

  public IndexingCoordinator indexingCoordinator() {
    return session.indexingCoordinator;
  }

  public PruneOps pruneOps() {
    return session.pruneOps;
  }

  /**
   * Installs the owning Engine's callback for a Lucene writer that has become permanently unusable.
   * The callback fires at most once for this runtime and never for its intentional drain/close.
   */
  public void onTerminalWriterFailure(Consumer<Throwable> listener) {
    session.onTerminalWriterFailure(listener);
  }

  /** Prevents this retiring runtime from reporting close-induced writer failures. */
  public void retireTerminalWriterFailureNotifications() {
    session.retireTerminalWriterFailureNotifications();
  }

  // ==========================================================================
  // LuceneRuntime — common methods
  // ==========================================================================

  @Override
  public IndexSchema schema() {
    return schema;
  }

  @Override
  public LuceneRuntimeBuilder origin() {
    return origin;
  }

  @Override
  public LuceneExecutorRegistrations executorRegistrations() {
    return session.executorRegistrations;
  }

  @Override
  public io.justsearch.core.execution.EngineTaskLifetime taskLifetime() {
    return session::retainTaskLifetime;
  }

  @Override
  public ReadPathOps readPathOps() {
    return session.readPathOps;
  }

  @Override
  public CommitOps commitOps() {
    return session.commitOps;
  }

  @Override
  public IndexCountOps indexCountOps() {
    return session.indexCountOps;
  }

  @Override
  public DocumentFieldOps documentFieldOps() {
    return session.documentFieldOps;
  }

  @Override
  public TextQueryOps textQueryOps() {
    return session.textQueryOps;
  }

  @Override
  public HybridSearchOps hybridSearchOps() {
    return session.hybridSearchOps;
  }

  @Override
  public ChunkSearchOps chunkSearchOps() {
    return session.chunkSearchOps;
  }

  @Override
  public SuggestOps suggestOps() {
    return session.suggestOps;
  }

  @Override
  public FacetingEngine facetingEngine() {
    return session.facetingEngine;
  }

  @Override
  public FolderBrowseEngine folderBrowseEngine() {
    return session.folderBrowseEngine;
  }

  @Override
  public Map<String, String> latestCommitUserDataBestEffort() {
    return session.latestCommitUserDataBestEffort();
  }

  @Override
  public Map<String, String> openTimeCommitUserData() {
    return session.openTimeCommitUserData();
  }

  @Override
  public ResolvedConfig resolvedConfig() {
    return session.resolvedConfig();
  }

  @Override
  public boolean commitMetadataEnabled() {
    return session.commitMetadataEnabled;
  }

  @Override
  public org.apache.lucene.analysis.Analyzer indexAnalyzerOrNull() {
    LifecycleSnapshot snap = session.snapshot;
    return snap != null ? snap.indexAnalyzer() : null;
  }

  @Override
  public VectorFormatDetector.Summary queryVectorFormatActual() {
    return session.queryVectorFormatActual();
  }

  @Override
  public void close() {
    session.close();
  }

  /**
   * Tempdoc 406 Gap G: drains in-flight writes, performs a final commit, then closes.
   *
   * <p>Sets the {@code draining} flag so new writes via {@link IndexingCoordinator} are
   * rejected with ISE (the gRPC layer maps to UNAVAILABLE so callers retry on the upgraded
   * holder reference — see the Pattern F supplier migration in tempdoc 406 Phase 4a).
   * Acquires {@link RuntimeSession#writeBarrier}'s write lock with the supplied timeout —
   * blocks until all in-flight writes (which hold the read lock) complete, eliminating the
   * race window where a writer could pass the {@code draining} check then crash on a null
   * writer mid-write (critical-analysis fix item 4). If {@code pendingDocs > 0} a final
   * {@code "drain"} commit lands the in-flight work; calls {@link #close()}.
   *
   * <p>If the write lock isn't acquired before the timeout, close still runs (best-effort);
   * a WARN is logged. Use a generous timeout for production holder swaps where in-flight
   * writes are expected.
   *
   * @param timeout maximum time to wait for in-flight writes to drain
   * @see DeferredRuntime#upgradeWriter for the upgrade variant from deferred mode
   */
  public void drainAndClose(Duration timeout) {
    drainAndClose(timeout, SwapReason.UNKNOWN);
  }

  /**
   * Tempdoc 406 swap-instrumented overload of {@link #drainAndClose(Duration)}. The
   * {@code reason} tags swap/drain telemetry events for distinguishing call sites; see
   * {@link SwapReason} for the bounded set of values.
   */
  public void drainAndClose(Duration timeout, SwapReason reason) {
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(reason, "reason");
    session.retireTerminalWriterFailureNotifications();
    LuceneRuntimeTypes.TelemetryEvents events = session.telemetryEvents;
    long swapStartNanos = System.nanoTime();
    if (events != null) events.onSwapStart(reason);
    session.draining = true;

    Lock writeLock = session.writeBarrier.writeLock();
    boolean acquired = false;
    try {
      acquired = writeLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        long pending = session.queueDepth.get();
        log.warn(
            "drainAndClose: write barrier not acquired within {}ms; {} writes still in flight."
                + " Closing anyway (best-effort).",
            timeout.toMillis(),
            pending);
        if (events != null) events.onDrainTimeout(timeout.toMillis(), pending);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
          "drainAndClose interrupted with queueDepth={}; closing best-effort",
          session.queueDepth.get());
    }
    try {
      // Only commit if there's actual pending work — avoids noise commit + spurious commitCount.
      if (session.pendingDocs.get() > 0 && session.commitOps != null) {
        try {
          session.commitOps.commitAndTrack(CommitReason.DRAIN);
        } catch (RuntimeException e) {
          log.warn("drain-final commit failed: {}", e.getMessage());
        }
      }
      close();
    } finally {
      if (acquired) writeLock.unlock();
      long durationMs = (System.nanoTime() - swapStartNanos) / 1_000_000L;
      if (events != null) events.onSwapComplete(durationMs, reason);
    }
  }

  /**
   * Tempdoc 406 observability — point-in-time snapshot of per-session runtime gauges.
   * Used by the Worker's status producer ({@code IndexStatusOps}) to surface runtime
   * internals via {@code /api/status} for jseval timeline consumption.
   */
  public LuceneRuntimeTypes.RuntimeGaugesSnapshot runtimeGaugesSnapshot() {
    LifecycleSnapshot snap = session.snapshot;
    return new LuceneRuntimeTypes.RuntimeGaugesSnapshot(
        session.queueDepth.get(),
        session.pendingDocs.get(),
        session.commitCount.get(),
        session.commitOps != null ? session.commitOps.refreshLagMs() : 0L,
        session.nrtStats.reopenTotal.get(),
        session.nrtStats.segmentsSinceReopen(snap != null ? snap.writer() : null));
  }

  /** Package-private accessor for test/internal wiring. */
  RuntimeSession session() {
    return session;
  }
}

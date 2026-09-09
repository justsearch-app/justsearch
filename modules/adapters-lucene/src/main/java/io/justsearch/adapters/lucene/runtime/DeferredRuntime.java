/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only phase that supports a one-shot upgrade to {@link RunningRuntime}.
 *
 * <p>Used by KnowledgeServer's normal-boot path: the runtime opens read-only
 * (fast), search is immediately available, and the IndexWriter is opened
 * later via {@link #upgradeWriter()} on a background thread. This subsumes
 * today's {@code setDeferredWriterMode} / {@code openWriterDeferred} pattern.
 *
 * <p><b>Compile-time safety note:</b> calling write-side methods like
 * {@code indexingCoordinator()} on this type does <i>not compile</i> — they
 * are only declared on {@link RunningRuntime}. To write, first call
 * {@link #upgradeWriter()} and use the returned {@link RunningRuntime}.
 *
 * <p>Single-shot lifecycle; {@link #close()} is terminal.
 * {@link #upgradeWriter()} is also one-shot (the {@link AtomicBoolean} guard
 * throws on a second call). After upgrade, the consumer must use the
 * returned {@link RunningRuntime} — references to the old {@code DeferredRuntime}
 * become stale (its session has been closed). Java cannot enforce
 * single-consumption at compile time (no move semantics) — this is the
 * documented residual runtime check.
 */
public final class DeferredRuntime implements LuceneRuntime {

  private final IndexSchema schema;
  private final LuceneRuntimeBuilder origin;
  private final RuntimeSession session;
  private final AtomicBoolean consumed = new AtomicBoolean(false);

  DeferredRuntime(IndexSchema schema, LuceneRuntimeBuilder origin, RuntimeSession session) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.origin = Objects.requireNonNull(origin, "origin");
    this.session = Objects.requireNonNull(session, "session");
  }

  // ==========================================================================
  // Phase-specific transition — only on DeferredRuntime
  // ==========================================================================

  /**
   * Consumes this {@code DeferredRuntime} and returns the upgraded
   * {@link RunningRuntime}. Subsequent calls throw
   * {@link IllegalStateException}.
   *
   * <p>Builds a new {@link RuntimeSession} in {@link RuntimeSession.Mode#RUNNING}
   * mode against the same path (using {@link #origin()}), then closes the
   * deferred session. In-flight searches against the deferred {@code
   * SearcherManager} survive via Lucene's {@code IndexSearcher} refcount
   * contract; subsequent search requests must go through the returned
   * {@code RunningRuntime}.
   *
   * <p>This is one of two "swap with cleanup of the old session" shapes in the
   * runtime — see {@link RunningRuntime#drainAndClose} for the variant that
   * cycles a running runtime (e.g., for blue/green re-open on the same path).
   * {@code upgradeWriter} is for the read-only → read-write transition; {@code
   * drainAndClose} is for the running → closed transition with in-flight
   * write protection.
   *
   * @return a {@link RunningRuntime} with the writer opened
   * @throws IllegalStateException if {@code upgradeWriter()} was already called
   * @see RunningRuntime#drainAndClose for the running-runtime swap variant
   */
  public RunningRuntime upgradeWriter() {
    if (!consumed.compareAndSet(false, true)) {
      throw new IllegalStateException("DeferredRuntime already consumed via upgradeWriter()");
    }
    // Build the upgraded read-write session via the same builder + path. If the
    // ctor throws (e.g., write-lock contention, IO failure), the deferred session
    // is still open and recoverable: reset `consumed` so the caller can try
    // again or call close() to release the deferred session cleanly.
    RuntimeSession upgradedSession;
    try {
      upgradedSession = new RuntimeSession(origin, RuntimeSession.Mode.RUNNING);
    } catch (RuntimeException | Error t) {
      consumed.set(false);
      throw t;
    }
    RunningRuntime upgraded = new RunningRuntime(schema, origin, upgradedSession);
    // Close the deferred session. In-flight searches survive via Lucene refcount.
    try {
      session.close();
    } catch (RuntimeException e) {
      // Best-effort — the upgraded runtime is already valid.
    }
    return upgraded;
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
    if (!consumed.get()) {
      session.close();
    }
    // If consumed, upgradeWriter() already closed the deferred session.
  }

  /** Package-private accessor for test/internal wiring. */
  RuntimeSession session() {
    return session;
  }
}

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
 * later via {@link #prepareWriterUpgrade()} on a background thread. This subsumes
 * today's {@code setDeferredWriterMode} / {@code openWriterDeferred} pattern.
 *
 * <p><b>Compile-time safety note:</b> calling write-side methods like
 * {@code indexingCoordinator()} on this type does <i>not compile</i> — they
 * are only declared on {@link RunningRuntime}. To write, first call
 * {@link #prepareWriterUpgrade()} and publish the returned {@link PreparedUpgrade}.
 *
 * <p>Single-shot lifecycle; {@link #close()} is terminal.
 * {@link #prepareWriterUpgrade()} is also one-shot (the {@link AtomicBoolean} guard
 * throws on a second call). The owner publishes the prepared {@link RunningRuntime}, retains
 * the old reader for issued work, and calls {@link PreparedUpgrade#retireReader()} only after
 * that work exits. Java cannot enforce single-consumption at compile time (no move semantics).
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
   * Opens the candidate writer without closing the predecessor. Publication and retirement have
   * separate owner steps so a failed close cannot lose either runtime's retry handle.
   */
  public PreparedUpgrade prepareWriterUpgrade() {
    if (!consumed.compareAndSet(false, true)) {
      throw new IllegalStateException("DeferredRuntime already has a prepared writer upgrade");
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
    return new PreparedUpgrade(new RunningRuntime(schema, origin, upgradedSession));
  }

  /** One private writer candidate and its still-live read-only predecessor. */
  public final class PreparedUpgrade implements AutoCloseable {
    private final RunningRuntime upgraded;
    private boolean published;
    private boolean abandoned;

    private PreparedUpgrade(RunningRuntime upgraded) { this.upgraded = upgraded; }

    public RunningRuntime runtime() { return upgraded; }

    /** Successor ownership has moved to the caller; close() may no longer abandon it. */
    public void markPublished() {
      if (abandoned) throw new IllegalStateException("Writer upgrade was abandoned");
      published = true;
    }

    /** The caller has installed the successor; the old session may now drain issued readers. */
    public void retireReader() {
      if (!published) throw new IllegalStateException("Writer successor is not published");
      session.close();
    }

    /** Closes only an unpublished candidate; published resources remain with their owners. */
    @Override public void close() {
      if (published || abandoned) return;
      upgraded.close();
      abandoned = true;
      consumed.set(false);
    }
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

  @Override public java.nio.file.Path openedIndexPath() {
    return session.indexPath;
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
  public Map<String, Object> appliedConfigurationValues() {
    return session.appliedConfigurationValues();
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
    // If consumed, the published preparation retains old-reader retirement ownership.
  }

  /** Package-private accessor for test/internal wiring. */
  RuntimeSession session() {
    return session;
  }
}

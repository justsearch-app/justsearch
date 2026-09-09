/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only phase of a Lucene runtime — exposes only the read-side ops.
 * Calling a write-side method on a {@code ReadOnlyRuntime} is a *compile error*.
 *
 * <p>Used by KnowledgeServer's Blue side during Blue/Green migration: search
 * serves from the active generation read-only while the Green side ingests
 * into a new generation.
 *
 * <p>Single-shot lifecycle; {@link #close()} is terminal.
 */
public final class ReadOnlyRuntime implements LuceneRuntime {

  private final IndexSchema schema;
  private final LuceneRuntimeBuilder origin;
  private final RuntimeSession session;

  ReadOnlyRuntime(IndexSchema schema, LuceneRuntimeBuilder origin, RuntimeSession session) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.origin = Objects.requireNonNull(origin, "origin");
    this.session = Objects.requireNonNull(session, "session");
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

  /** Package-private accessor for test/internal wiring. */
  RuntimeSession session() {
    return session;
  }
}

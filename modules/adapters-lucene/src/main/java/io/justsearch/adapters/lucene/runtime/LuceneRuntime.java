/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Map;

/**
 * Sealed value type for the Lucene runtime — tempdoc 406 substrate.
 *
 * <p>Each concrete phase ({@link RunningRuntime}, {@link ReadOnlyRuntime},
 * {@link DeferredRuntime}) is an {@link AutoCloseable} value with a single-shot
 * lifecycle: {@code open → use → close}, terminal. Restart is a *consumer
 * pattern* (build a new value via the same builder, atomically swap a holder
 * field, close the old) — not a method on the value. This matches the
 * Elasticsearch {@code AtomicReference<Engine>} pattern and JPA's
 * {@code EntityManager} / {@code EntityManagerFactory} split.
 *
 * <p>Methods on this interface are valid in every phase. Phase-specific
 * operations live on the concrete types only — calling a write-side op on a
 * {@link ReadOnlyRuntime} is a *compile error*, not a runtime check. This
 * structurally eliminates the {@code ensureStarted()} runtime guards and the
 * state-machine enum that today's {@code LuceneLifecycleManager} uses.
 *
 * <p>See {@code docs/tempdocs/406-lucene-lifecycle-manager-restart-refactor.md}
 * for design and {@code docs/future-features/service-identity-lifecycle-pattern.md}
 * for the repo-wide pattern this is the first instance of.
 */
public sealed interface LuceneRuntime extends AutoCloseable
    permits RunningRuntime, ReadOnlyRuntime, DeferredRuntime {

  /** The immutable schema this runtime was built from. */
  IndexSchema schema();

  /**
   * The builder this runtime was opened from — for "build another like this"
   * (the holder-swap pattern: build new, swap field, close old).
   */
  LuceneRuntimeBuilder origin();

  /** Exact bounded executor bundle used by this runtime session. */
  LuceneExecutorRegistrations executorRegistrations();

  /** Keeps this runtime generation open through accepted asynchronous read work. */
  io.justsearch.core.execution.EngineTaskLifetime taskLifetime();

  // ==========================================================================
  // Read-side ops — valid in every phase
  // ==========================================================================

  ReadPathOps readPathOps();

  CommitOps commitOps();

  IndexCountOps indexCountOps();

  DocumentFieldOps documentFieldOps();

  TextQueryOps textQueryOps();

  HybridSearchOps hybridSearchOps();

  ChunkSearchOps chunkSearchOps();

  SuggestOps suggestOps();

  FacetingEngine facetingEngine();

  FolderBrowseEngine folderBrowseEngine();

  // ==========================================================================
  // Status / observability — valid in every phase
  // ==========================================================================

  /** Best-effort latest commit userData; empty map if unavailable. */
  Map<String, String> latestCommitUserDataBestEffort();

  /** Commit userData captured at runtime open time; immutable. */
  Map<String, String> openTimeCommitUserData();

  /** The resolved configuration for this runtime. */
  ResolvedConfig resolvedConfig();

  /** Whether commit metadata stamping is enabled for this runtime. */
  boolean commitMetadataEnabled();

  /** The index analyzer wrapper used by this runtime, or null if unavailable. */
  org.apache.lucene.analysis.Analyzer indexAnalyzerOrNull();

  /** Configured vector format ("float32" or "int8_sq") from resolved config. */
  default String configuredVectorFormat() {
    Boolean enabled = resolvedConfig().index().vectorQuantizationEnabled();
    return Boolean.TRUE.equals(enabled) ? "int8_sq" : "float32";
  }

  /** Stored vector format from commit metadata; empty for legacy indexes. */
  default String storedVectorFormat() {
    return latestCommitUserDataBestEffort().getOrDefault("vector_format", "");
  }

  /** Actual vector format from segment inspection; null if unavailable. */
  VectorFormatDetector.Summary queryVectorFormatActual();

  /**
   * Releases all per-phase resources (Lucene Directory, IndexWriter,
   * SearcherManager, CRTRT, commit timer). Terminal — calling any other
   * method after close throws.
   */
  @Override
  void close();
}

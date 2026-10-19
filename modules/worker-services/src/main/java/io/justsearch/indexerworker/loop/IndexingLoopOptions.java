/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.path.PathResolutionStore;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Set-once-at-startup configuration for {@link IndexingLoop}.
 *
 * <p>Tempdoc 516 P3 / Slice 5 (W7.2 followup): absorbs the 5 startup-config setters that
 * {@link io.justsearch.indexerworker.server.DefaultWorkerAppServices} previously called
 * post-construction. The "P3 no-setters" goal is enforced by removing those setters and
 * routing through this record at ctor time.
 *
 * <p>All fields are optional — null means "use default". The {@link #withDefaults} factory
 * gives the no-arg defaults used by tests that don't care.
 *
 * <p>Fields
 *
 * <ul>
 *   <li>{@code detailedTracing} — when true, IndexingLoop emits OTel pipeline spans (312 Phase 0).
 *   <li>{@code pathResolutionStore} — scoped reverse-lookup store wired in production by
 *       {@link io.justsearch.indexerworker.server.DefaultWorkerAppServices} (tempdoc 419 / T5.2 /
 *       ADR-0028). Defaults to {@link PathResolutionStore#NOOP}.
 *   <li>{@code migrationActiveSupplier} — returns true when blue-green migration is rebuilding
 *       (312 Phase 4). Defaults to a constant false. When true AND an embedding provider is
 *       available, batch embedding is enabled during primary indexing.
 *   <li>{@code commitMetadataSupplier} — supplies commit-identity attrs for {@code indexing.batch}
 *       span attrs (tempdoc 400 LR2-d.2). Defaults to {@link Map#of()}.
 *   <li>{@code embeddingTelemetryEvents} — sink for {@code embedding.runtime.*} metric emissions
 *       (tempdoc 413). May be null; the embedding lifecycle keeps its own no-op default.
 *   <li>{@code forcedPaths} — the paths marked for force reindex, which the loop consumes when it
 *       reaches each one. Defaults to a fresh set. A writer upgraded from a read-only (deferred)
 *       boot is given the set its read-only services marked into, so a force submitted before the
 *       loop existed still reaches it.
 * </ul>
 */
public record IndexingLoopOptions(
    boolean detailedTracing,
    PathResolutionStore pathResolutionStore,
    DocumentIdentityStore documentIdentityStore,
    BooleanSupplier migrationActiveSupplier,
    Supplier<Map<String, String>> commitMetadataSupplier,
    EmbeddingTelemetryEvents embeddingTelemetryEvents,
    Set<String> forcedPaths) {

  public IndexingLoopOptions {
    pathResolutionStore = pathResolutionStore != null ? pathResolutionStore : PathResolutionStore.NOOP;
    documentIdentityStore =
        documentIdentityStore != null ? documentIdentityStore : DocumentIdentityStore.UNAVAILABLE;
    migrationActiveSupplier = migrationActiveSupplier != null ? migrationActiveSupplier : () -> false;
    commitMetadataSupplier = commitMetadataSupplier != null ? commitMetadataSupplier : Map::of;
    // embeddingTelemetryEvents may be null — EmbeddingProviderLifecycle keeps a no-op default
    // when this slot isn't wired.
    forcedPaths = forcedPaths != null ? forcedPaths : ConcurrentHashMap.newKeySet();
  }

  /** The production shape before a forced-path set could be handed over: a fresh set. */
  public IndexingLoopOptions(
      boolean detailedTracing,
      PathResolutionStore pathResolutionStore,
      DocumentIdentityStore documentIdentityStore,
      BooleanSupplier migrationActiveSupplier,
      Supplier<Map<String, String>> commitMetadataSupplier,
      EmbeddingTelemetryEvents embeddingTelemetryEvents) {
    this(detailedTracing, pathResolutionStore, documentIdentityStore, migrationActiveSupplier,
        commitMetadataSupplier, embeddingTelemetryEvents, null);
  }

  /** Back-compatible shape for callers that do not perform production indexing. */
  public IndexingLoopOptions(
      boolean detailedTracing,
      PathResolutionStore pathResolutionStore,
      BooleanSupplier migrationActiveSupplier,
      Supplier<Map<String, String>> commitMetadataSupplier,
      EmbeddingTelemetryEvents embeddingTelemetryEvents) {
    this(
        detailedTracing,
        pathResolutionStore,
        DocumentIdentityStore.UNAVAILABLE,
        migrationActiveSupplier,
        commitMetadataSupplier,
        embeddingTelemetryEvents,
        null);
  }

  /** Defaults for callers that don't need any of the optional slots (typically tests). */
  public static IndexingLoopOptions withDefaults() {
    return new IndexingLoopOptions(false, null, null, null, null, null);
  }
}

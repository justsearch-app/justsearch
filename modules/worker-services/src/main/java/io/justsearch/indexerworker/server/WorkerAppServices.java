/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.services.WorkerHealthService;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder;
import io.justsearch.reranker.CrossEncoderReranker;
import java.io.Closeable;

/**
 * Contract between infrastructure ({@link KnowledgeServer}) and application services.
 *
 * <p>This is a <b>construction helper</b>, not a lifecycle boundary. {@code KnowledgeServer}
 * remains the lifecycle orchestrator — it manages shutdown ordering, deferred model init
 * coordination, and telemetry gauge registration. The implementation centralizes how
 * application objects are created and wired.
 *
 * <p>{@link #close()} closes only the indexing loop (the sole owned background thread).
 * All other resources (gRPC server, Lucene runtimes, job queue, signal bus) are closed by
 * {@code KnowledgeServer} in its own shutdown sequence.
 */
public interface WorkerAppServices extends Closeable {

  // --- Concrete service accessors (used by GrpcWiring for delegate wrapping) ---

  WorkerSearchService searchService();

  WorkerIngestService ingestService();

  WorkerHealthService healthService();

  // --- Indexing loop lifecycle ---

  void startIndexingLoop();

  String indexingLoopState();

  // --- Foreground-contention pacing (tempdoc 885 item 3) ---

  /**
   * The single pacing policy the indexing loop and every backfill site throttle against. Supplied
   * by {@code KnowledgeServer} (which owns it across app-service reconstructions), not created
   * here.
   */
  IndexingPacing indexingPacing();

  // --- Deferred model wiring (distributes to internal services) ---

  // 516 P3 FINAL CUT: wireEmbeddingTelemetryEvents removed from the interface — the
  // events sink is pre-wired via DefaultWorkerAppServices's 2-arg ctor at KS init time.

  void wireEmbeddingProvider(EmbeddingProvider provider);

  /**
   * Registers an additional listener notified on every embedding-provider change
   * (reload + unload via GPU handoff). Multiple subscribers supported; each fires
   * after the primary listener wired internally by {@link #wireEmbeddingProvider}.
   *
   * <p>observations.md fix: lets KnowledgeServer null its own
   * {@code embeddingService} field on unload so {@code GpuDiagnosticSuppliers}
   * lambdas don't read from a closed instance.
   *
   * <p>Default no-op: implementations without a configurable indexing-loop
   * lifecycle (test stubs, scaffolding) silently ignore the registration.
   */
  default void addEmbeddingProviderChangeListener(
      java.util.function.Consumer<EmbeddingProvider> listener) {}

  void wireEmbeddingCompatController(EmbeddingCompatibilityController ecc);

  void wireNerService(NerService ns);

  void wireSpladeEncoder(SpladeEncoder enc);

  void wireSpladeIdfQueryEncoder(SpladeIdfQueryEncoder idfEnc);

  void wireBgeM3Encoder(BgeM3Encoder enc);

  void wireDisambiguationService(DisambiguationService ds);

  void wireGpuDiagnostics(GpuDiagnosticSuppliers suppliers);

  /**
   * Wires per-stage enabled flags into the status endpoint. Called once
   * from {@code KnowledgeServer} after encoder initialization — pass
   * {@code true} if the corresponding service is usable (config-enabled
   * AND initialization succeeded), {@code false} otherwise. Consumers
   * reading {@code /api/status} (e.g. jseval readiness polling) use these
   * flags to skip coverage checks for stages that will never update.
   * Defaults to all-enabled when this method is never called.
   */
  default void wireStageEnabled(boolean embedding, boolean splade, boolean ner) {}

  /** 360: Wires the search reranker (GPU-capable) to WorkerSearchService and RagContextOps. */
  void wireSearchReranker(CrossEncoderReranker reranker);

  /**
   * Wires the eagerly-constructed {@link io.justsearch.reranker.CitationScorer} built by the
   * composition root (tempdoc 397 §14.26 T2-E1). Replaces the previous
   * {@code wireCitationScorerSessions(SessionHandle)} which carried a bare session handle and
   * forced lazy construction inside {@code CitationMatchOps}.
   */
  default void wireCitationScorer(io.justsearch.reranker.CitationScorer scorer) {}

  // 516 P3 FINAL CUT: wireMigrationActiveSupplier removed — the supplier (a lambda
  // closing over KS.this) is pre-wired via DefaultWorkerAppServices's 2-arg ctor.

  /**
   * Wires a supplier for the {@code modelReadyLatch} that query handlers await before first
   * use (tempdoc 397 §14.28 U3). The latch counts down once {@code initDeferredModels}
   * completes; queries that arrive earlier block until it does (or time out). Closes the
   * T2-E1 boot-race regression where RagContextOps lost chunk reranking during the init
   * window.
   */
  default void wireModelReadyLatch(java.util.function.Supplier<java.util.concurrent.CountDownLatch> latchSupplier) {}

  /**
   * Wires a supplier for the {@link io.justsearch.ort.PolicySnapshot} built at boot by
   * {@code InferenceCompositionRoot.compose} (tempdoc 397 §14.28 U4). Returned via the
   * {@code GetSessionPolicies} gRPC rpc; Head's {@code /api/debug/session-policies}
   * endpoint reads this instead of re-resolving its own ConfigStore + HardwareProfile.
   */
  default void wirePolicySnapshotSupplier(
      java.util.function.Supplier<io.justsearch.ort.PolicySnapshot> supplier) {}

  // --- GPU lifecycle (sentinel thread) ---

  void onMainClaimedGpu();
}

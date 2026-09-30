/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import ai.onnxruntime.OrtException;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.model.VariantSelector;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.BgeM3Assembly;
import io.justsearch.indexerworker.bgem3.BgeM3Config;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.ner.NerConfig;
import io.justsearch.indexerworker.splade.SpladeAssembly;
import io.justsearch.indexerworker.splade.SpladeConfig;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.ort.Composition;
import io.justsearch.ort.DevModeVariantProbe;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArbiter;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.RuntimePolicyResolver;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import io.justsearch.reranker.CitationScorer;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerAssembly;
import io.justsearch.reranker.RerankerConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for tempdoc 397 §7.6. Builds {@link SessionHandle} instances for each encoder
 * by resolving {@link RuntimePolicy} + {@link ModelSessionPolicy} + {@link ModelArtifacts} and
 * handing the resulting {@link Composition} to {@link OrtSessionAssembler#buildManager}. Stage 4e
 * will collapse this further so that the assembler returns a native {@link SessionHandle}
 * directly, eliminating the adapter wrap.
 */
public final class InferenceCompositionRoot {

  private static final Logger log = LoggerFactory.getLogger(InferenceCompositionRoot.class);

  private InferenceCompositionRoot() {}

  // =========================================================================
  // §7.6 single-entry composition — tempdoc 397 §14.26 T2-C1.
  // =========================================================================

  /**
   * Composes every encoder role in one pass and returns a typed {@link InferenceSurface}. This is
   * §7.6's "one composition root" entry point: {@link KnowledgeServer#initDeferredModels()} calls
   * this method, destructures the result, and wires each {@link java.util.Optional}-valued
   * assembly into the app-services layer.
   *
   * <p>Per-encoder failures (variant missing, tokenizer load failure, ORT session creation error)
   * surface as {@link java.util.Optional#empty()} on the returned surface; a single encoder's
   * failure does not abort the others. Log messages mirror the pre-T2-C2 per-branch {@code
   * try/catch} patterns in {@code KnowledgeServer.initDeferredModels}.
   *
   * <p>Tempdoc 397 §14.26 T2-A1 consolidated dev-mode variant resolution into
   * {@link DevModeVariantProbe}: contract-driven + filesystem-probed variants flow through the
   * same {@link #resolveVariant} helper. Post-commit, the pre-existing "no variant — try
   * buildFallback anyway" branches in KnowledgeServer are redundant (same model path, same ORT
   * session options) and are removed here: if {@code variant == null} for an encoder, it stays
   * {@link java.util.Optional#empty()} on the surface. This is a subtle correctness improvement
   * too — when an {@link InstallContract} marks a model as skipped, we now respect that
   * signal instead of silently rebuilding the encoder from the filesystem.
   *
   * @param cfg resolved configuration snapshot (must be non-null)
   * @param hardware detected hardware profile
   * @param contract loaded install contract, or {@code null} for dev-mode (filesystem probe)
   * @param modelsDir base models directory (nullable — dev-mode contract==null path uses
   *     per-encoder config model path instead)
   * @param arbiter GPU arbitration callback
   */
  @io.justsearch.contracts.BuildContract(
      description =
          "Single-entry composition root: every InferenceSurface in production is produced"
              + " here. Ops classes eager-wire encoders (T2-E1) so missing models surface at"
              + " compose() time rather than on first use; the assembler is the only path to"
              + " SessionHandle construction (§14.28 U1 deleted OrtSessionAssembler fallbacks).",
      tempdoc = "397 §14.28 U4 / T2-C1 / T2-C2 / T2-E1",
      enforcer = "ClosurePropertyTest + InferenceSurfaceTest")
  public static InferenceSurface compose(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      GpuArbiter arbiter) {
    return compose(cfg, hardware, contract, modelsDir, arbiter, OrtSessionTelemetryEvents.NOOP);
  }

  /**
   * Tempdoc 414 overload. Threads an {@link OrtSessionTelemetryEvents} adapter through to every
   * {@link OrtSessionAssembler#buildManager} call so {@code ort.session.*} lifecycle metrics
   * reach the worker telemetry. {@link OrtSessionTelemetryEvents#NOOP} matches the legacy
   * behaviour (used by the 5-arg form for tests + benchmarks).
   */
  public static InferenceSurface compose(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      GpuArbiter arbiter,
      OrtSessionTelemetryEvents events) {

    List<SessionHandle> handles = new ArrayList<>();
    TreeMap<EncoderRole, ModelSessionPolicy> policies = new TreeMap<>();

    // BGE-M3 replaces the separate Embedding + SPLADE encoders when selected.
    String sparseModel = cfg.ai().sparseModel();
    boolean bgeM3Selected = "bge-m3".equalsIgnoreCase(sparseModel);

    Optional<EmbeddingAssembly> embedding =
        bgeM3Selected
            ? Optional.empty()
            : composeEmbeddingRole(
                cfg, hardware, contract, modelsDir, arbiter, handles, policies, events);

    Optional<NerAssembly> ner =
        composeNerRole(cfg, hardware, contract, modelsDir, arbiter, handles, policies, events);

    Optional<BgeM3Assembly> bgeM3 =
        bgeM3Selected
            ? composeBgeM3Role(
                cfg, hardware, contract, modelsDir, arbiter, handles, policies, events)
            : Optional.empty();

    // If BGE-M3 was selected but failed, fall back to SPLADE (matches today's KnowledgeServer
    // behaviour: line 765 `sparseModel = "splade"` when BGE-M3 initialisation throws).
    boolean spladeActive = !bgeM3Selected || bgeM3.isEmpty();
    Optional<SpladeAssembly> splade =
        spladeActive
            ? composeSpladeRole(
                cfg, hardware, contract, modelsDir, arbiter, handles, policies, events)
            : Optional.empty();

    Optional<RerankerAssembly> reranker =
        composeRerankerRole(
            cfg, hardware, contract, modelsDir, arbiter, handles, policies, events);

    Optional<RerankerAssembly> citation =
        composeCitationRole(cfg, hardware, contract, modelsDir, handles, policies, events);

    RuntimePolicy runtime = RuntimePolicyResolver.resolve(cfg, hardware);
    PolicySnapshot snapshot = new PolicySnapshot(runtime, policies);
    return new InferenceSurface(
        embedding, ner, reranker, citation, splade, bgeM3, snapshot, handles);
  }

  // -------- Per-role composition helpers (tempdoc 397 §14.26 T2-C1). --------

  private static Optional<EmbeddingAssembly> composeEmbeddingRole(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      GpuArbiter arbiter,
      List<SessionHandle> handles,
      java.util.Map<EncoderRole, ModelSessionPolicy> policies,
      OrtSessionTelemetryEvents events) {
    EmbeddingConfig embedCfg = EmbeddingConfig.fromEnv();
    if (!embedCfg.isReady()) {
      return Optional.empty();
    }
    VariantSelection variant =
        resolveVariant(
            "embedding", contract, hardware, modelsDir, embedCfg.modelPath(), embedCfg.gpuEnabled());
    if (variant == null) {
      log.warn(
          "Embedding: no variant resolved (dev mode without contract or model absent); vector"
              + " search disabled.");
      return Optional.empty();
    }
    try {
      SessionHandle sessions =
          compose(
              EncoderRole.EMBEDDING.consumerName(),
              EncoderRole.EMBEDDING,
              cfg,
              hardware,
              variant,
              arbiter,
              events);
      EmbeddingAssembly assembly =
          OnnxEmbeddingEncoder.buildAssembly(
              sessions,
              embedCfg.modelPath(),
              embedCfg.contextLength(),
              embedCfg.lateChunkingContextLength(),
              cfg.ai().capabilityContractStrict());
      handles.add(assembly.sessions());
      policies.put(
          EncoderRole.EMBEDDING,
          ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, cfg, hardware, variant));
      return Optional.of(assembly);
    } catch (Exception e) {
      // Tempdoc 710 Wave 2 Move 1: widened from OrtException — ModelCapabilityResolver throws
      // IllegalStateException under justsearch.models.capability_contract_strict, and that must
      // degrade this lane to Optional.empty() the same way a session-creation failure does, not
      // abort the whole composition.
      log.error(
          "Embedding composition failed — variant resolved but assembly/session creation threw."
              + " Embedding will be unavailable.",
          e);
      return Optional.empty();
    }
  }

  private static Optional<NerAssembly> composeNerRole(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      GpuArbiter arbiter,
      List<SessionHandle> handles,
      java.util.Map<EncoderRole, ModelSessionPolicy> policies,
      OrtSessionTelemetryEvents events) {
    NerConfig nerCfg = NerConfig.fromEnv();
    if (!nerCfg.isReady()) {
      return Optional.empty();
    }
    VariantSelection variant =
        resolveVariant("ner", contract, hardware, modelsDir, nerCfg.modelPath(), nerCfg.gpuEnabled());
    if (variant == null) {
      log.info("NER: no variant resolved; NER will be unavailable.");
      return Optional.empty();
    }
    try {
      SessionHandle sessions =
          compose(
              EncoderRole.NER.consumerName(), EncoderRole.NER, cfg, hardware, variant, arbiter, events);
      Path modelDir = variant.modelFile().getParent();
      NerAssembly assembly =
          io.justsearch.indexerworker.ner.BertNerInference.buildAssembly(
              sessions, modelDir, nerCfg.maxSequenceLength(), cfg.ai().capabilityContractStrict());
      handles.add(assembly.sessions());
      policies.put(
          EncoderRole.NER,
          ModelSessionPolicyResolver.resolve(EncoderRole.NER, cfg, hardware, variant));
      return Optional.of(assembly);
    } catch (Exception e) {
      log.error("NER composition failed — NER will be unavailable", e);
      return Optional.empty();
    }
  }

  private static Optional<BgeM3Assembly> composeBgeM3Role(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      GpuArbiter arbiter,
      List<SessionHandle> handles,
      java.util.Map<EncoderRole, ModelSessionPolicy> policies,
      OrtSessionTelemetryEvents events) {
    BgeM3Config bgeCfg = BgeM3Config.fromEnv();
    if (!bgeCfg.isReady()) {
      log.warn("BGE-M3 selected but model not found, falling back to SPLADE");
      return Optional.empty();
    }
    VariantSelection variant =
        resolveVariant(
            "embedding", contract, hardware, modelsDir, bgeCfg.modelPath(), bgeCfg.gpuEnabled());
    if (variant == null) {
      log.warn("BGE-M3: no variant resolved; falling back to SPLADE");
      return Optional.empty();
    }
    try {
      SessionHandle sessions =
          compose(
              EncoderRole.BGE_M3.consumerName(),
              EncoderRole.BGE_M3,
              cfg,
              hardware,
              variant,
              arbiter,
              events);
      BgeM3Assembly assembly = BgeM3Encoder.buildAssembly(sessions, bgeCfg);
      handles.add(assembly.sessions());
      policies.put(
          EncoderRole.BGE_M3,
          ModelSessionPolicyResolver.resolve(EncoderRole.BGE_M3, cfg, hardware, variant));
      return Optional.of(assembly);
    } catch (Exception e) {
      log.warn(
          "Failed to initialize BGE-M3 encoder, falling back to SPLADE: {}", e.getMessage());
      log.debug("Failed to initialize BGE-M3 encoder (stack trace)", e);
      return Optional.empty();
    }
  }

  private static Optional<SpladeAssembly> composeSpladeRole(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      GpuArbiter arbiter,
      List<SessionHandle> handles,
      java.util.Map<EncoderRole, ModelSessionPolicy> policies,
      OrtSessionTelemetryEvents events) {
    SpladeConfig spladeCfg = SpladeConfig.fromEnv();
    if (!spladeCfg.isReady()) {
      return Optional.empty();
    }
    VariantSelection variant =
        resolveVariant(
            "splade", contract, hardware, modelsDir, spladeCfg.modelPath(), spladeCfg.gpuEnabled());
    if (variant == null) {
      log.info("SPLADE: no variant resolved; sparse retrieval disabled.");
      return Optional.empty();
    }
    try {
      SessionHandle sessions =
          compose(
              EncoderRole.SPLADE.consumerName(),
              EncoderRole.SPLADE,
              cfg,
              hardware,
              variant,
              arbiter,
              events);
      SpladeAssembly assembly = SpladeEncoder.buildAssembly(sessions, spladeCfg);
      handles.add(assembly.sessions());
      policies.put(
          EncoderRole.SPLADE,
          ModelSessionPolicyResolver.resolve(EncoderRole.SPLADE, cfg, hardware, variant));
      return Optional.of(assembly);
    } catch (Exception e) {
      log.warn("Failed to initialize SPLADE encoder (non-fatal): {}", e.getMessage());
      log.debug("Failed to initialize SPLADE encoder (stack trace)", e);
      return Optional.empty();
    }
  }

  private static Optional<RerankerAssembly> composeRerankerRole(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      GpuArbiter arbiter,
      List<SessionHandle> handles,
      java.util.Map<EncoderRole, ModelSessionPolicy> policies,
      OrtSessionTelemetryEvents events) {
    RerankerConfig rerankCfg = RerankerConfig.fromEnv();
    if (!rerankCfg.isReady()) {
      return Optional.empty();
    }
    VariantSelection variant =
        resolveVariant(
            "reranker",
            contract,
            hardware,
            modelsDir,
            rerankCfg.modelPath(),
            rerankCfg.gpuEnabled());
    if (variant == null) {
      log.info("Search reranker: no variant resolved; reranking disabled.");
      return Optional.empty();
    }
    try {
      SessionHandle sessions =
          compose(
              EncoderRole.RERANKER.consumerName(),
              EncoderRole.RERANKER,
              cfg,
              hardware,
              variant,
              arbiter,
              events);
      // Tempdoc 710 Move 2: the reranker lane was structurally absent from observability (no
      // registerEncoder, S-B3). CrossEncoderReranker lives in the `reranker` module, which does
      // not depend on worker-core (where EncoderProfileAccumulator/OperationalMetrics live), so
      // registration + choke-point binding happens here at the composition root instead of in
      // the encoder's own constructor (the pattern the other four encoders use).
      io.justsearch.indexerworker.metrics.EncoderProfileAccumulator rerankerProfiler =
          new io.justsearch.indexerworker.metrics.EncoderProfileAccumulator("ort");
      io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance()
          .registerEncoder(EncoderRole.RERANKER.consumerName(), rerankerProfiler);
      sessions.setOrtRunRecorder(rerankerProfiler::recordOrtCall);
      RerankerAssembly assembly =
          CrossEncoderReranker.buildAssembly(
              sessions,
              rerankCfg.modelPath().resolve("tokenizer.json"),
              rerankCfg.maxSequenceLength());
      handles.add(assembly.sessions());
      policies.put(
          EncoderRole.RERANKER,
          ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, cfg, hardware, variant));
      return Optional.of(assembly);
    } catch (Exception e) {
      log.warn("Failed to initialize search reranker (non-fatal): {}", e.getMessage());
      log.debug("Failed to initialize search reranker (stack trace)", e);
      return Optional.empty();
    }
  }

  private static Optional<RerankerAssembly> composeCitationRole(
      ResolvedConfig cfg,
      HardwareProfile hardware,
      InstallContract contract,
      Path modelsDir,
      List<SessionHandle> handles,
      java.util.Map<EncoderRole, ModelSessionPolicy> policies,
      OrtSessionTelemetryEvents events) {
    CitationScorerConfig citationCfg = CitationScorerConfig.fromEnv();
    if (citationCfg == null || !citationCfg.isReady()) {
      return Optional.empty();
    }
    VariantSelection variant =
        resolveVariant(
            "citation-scorer",
            contract,
            hardware,
            modelsDir,
            citationCfg.modelPath(),
            /* gpuEnabled= */ false);
    if (variant == null) {
      log.info("Citation scorer: no variant resolved; citation scoring disabled.");
      return Optional.empty();
    }
    try {
      ModelSessionPolicy policy =
          ModelSessionPolicyResolver.resolve(EncoderRole.CITATION, cfg, hardware, variant);
      assertCitationIsCpuOnly(variant, policy);
      SessionHandle sessions =
          buildHandle(
              EncoderRole.CITATION.consumerName(),
              cfg,
              hardware,
              policy,
              variant,
              () -> false,
              events);
      // Tempdoc 710 Move 2: the citation lane was likewise structurally absent from
      // observability. Same reasoning as composeRerankerRole above — CitationScorer lives in
      // the `reranker` module, so registration + choke-point binding happens here.
      io.justsearch.indexerworker.metrics.EncoderProfileAccumulator citationProfiler =
          new io.justsearch.indexerworker.metrics.EncoderProfileAccumulator("ort");
      io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance()
          .registerEncoder(EncoderRole.CITATION.consumerName(), citationProfiler);
      sessions.setOrtRunRecorder(citationProfiler::recordOrtCall);
      RerankerAssembly assembly =
          CitationScorer.buildAssembly(
              sessions,
              citationCfg.modelPath().resolve("tokenizer.json"),
              citationCfg.maxSequenceLength());
      handles.add(assembly.sessions());
      policies.put(EncoderRole.CITATION, policy);
      return Optional.of(assembly);
    } catch (Exception e) {
      log.warn("Citation scorer init failed (non-fatal): {}", e.getMessage());
      log.debug("Citation scorer init failed (stack trace)", e);
      return Optional.empty();
    }
  }

  /**
   * Resolves a {@link VariantSelection} from the install contract if present, otherwise falls back
   * to the filesystem probe (tempdoc 397 §14.26 T2-A1). Package-private so per-role helpers
   * above consume it without re-exporting. Since §14.27 T2-C1/C2 this is the sole variant-
   * resolution site — {@code KnowledgeServer} no longer hosts a parallel copy.
   */
  static VariantSelection resolveVariant(
      String packageId,
      InstallContract contract,
      HardwareProfile hardware,
      Path modelsDir,
      Path configModelPath,
      boolean gpuEnabled) {
    VariantSelection selection;
    if (contract != null) {
      // Tempdoc 374 alpha.18 Bug I: forward gpuEnabled to VariantSelector so CPU-only
      // roles (citation) don't get promoted to CUDA when the host has CUDA. Pre-alpha.18
      // this parameter was dropped on the floor in the contract branch — the dev-mode
      // branch below already honored it via DevModeVariantProbe.probe.
      selection = VariantSelector.select(packageId, contract, hardware, modelsDir, gpuEnabled);
    } else {
      selection = DevModeVariantProbe.probe(configModelPath, gpuEnabled);
    }
    if (selection != null && selection.degraded()) {
      // Tempdoc 691 B-5: a silent degraded selection (INT8 CPU variant on CUDA for NER) cost
      // ~10× per-call for a week with no log line — surface every degraded selection at the
      // single resolution site so the engine log names the encoder and the reason.
      log.warn("{}: degraded model variant selected — {}", packageId, selection.degradationReason());
    }
    return selection;
  }

  /**
   * Composes a {@link SessionHandle} for the citation scorer with a fail-loud assertion that the
   * resolved policy is CPU-shaped. Public because {@code InferenceCompositionRootTest} exercises
   * the fail-loud path directly without building a full surface; production callers route through
   * {@link #composeCitationRole} which shares the same assertion via
   * {@link #assertCitationIsCpuOnly}.
   */
  public static SessionHandle composeCitation(
      ResolvedConfig cfg, HardwareProfile hardware, VariantSelection variant)
      throws OrtException {
    ModelSessionPolicy policy =
        ModelSessionPolicyResolver.resolve(EncoderRole.CITATION, cfg, hardware, variant);
    assertCitationIsCpuOnly(variant, policy);
    return buildHandle(
        EncoderRole.CITATION.consumerName(), cfg, hardware, policy, variant, () -> false);
  }

  /**
   * Tempdoc 414 v2 (C3): single source of truth for the citation-CPU-only invariant. Called
   * from both {@link #composeCitationRole} (production per-role helper) and
   * {@link #composeCitation} (public test entry point).
   */
  private static void assertCitationIsCpuOnly(VariantSelection variant, ModelSessionPolicy policy) {
    if (variant.executionProvider() == ExecutionProvider.CUDA
        || policy.gpu().arenaCapBytes() != 0L) {
      throw new IllegalStateException(
          "Citation received GPU-capable policy/variant (variant.executionProvider="
              + variant.executionProvider()
              + ", policy.gpu.arenaCapBytes="
              + policy.gpu().arenaCapBytes()
              + "). Citation is CPU-only by design; the citation resolver must produce CPU-shaped"
              + " policy. If a future hardware/role combination requires GPU citation scoring,"
              + " update ModelSessionPolicyResolver.resolveCitation and this assertion together.");
    }
  }

  // =========================================================================
  // Internal: single shared composition path.
  // =========================================================================

  private static SessionHandle compose(
      String consumerName,
      EncoderRole role,
      ResolvedConfig cfg,
      HardwareProfile hardware,
      VariantSelection variant,
      GpuArbiter arbiter,
      OrtSessionTelemetryEvents events)
      throws OrtException {
    ModelSessionPolicy policy = ModelSessionPolicyResolver.resolve(role, cfg, hardware, variant);
    return buildHandle(consumerName, cfg, hardware, policy, variant, arbiter, events);
  }

  private static SessionHandle buildHandle(
      String consumerName,
      ResolvedConfig cfg,
      HardwareProfile hardware,
      ModelSessionPolicy policy,
      VariantSelection variant,
      GpuArbiter arbiter)
      throws OrtException {
    return buildHandle(
        consumerName, cfg, hardware, policy, variant, arbiter, OrtSessionTelemetryEvents.NOOP);
  }

  private static SessionHandle buildHandle(
      String consumerName,
      ResolvedConfig cfg,
      HardwareProfile hardware,
      ModelSessionPolicy policy,
      VariantSelection variant,
      GpuArbiter arbiter,
      OrtSessionTelemetryEvents events)
      throws OrtException {
    RuntimePolicy runtime = RuntimePolicyResolver.resolve(cfg, hardware);
    // Artifacts: cpu = variant.modelFile(); gpu = same file when CUDA (the FP16/FP32 fallback in
    // the assembler treats equal paths as a no-op), else same file for degraded-variant symmetry.
    // OrtSessionAssembler.buildManager nulls out the GPU path internally when EP != CUDA.
    ModelArtifacts artifacts = new ModelArtifacts(variant.modelFile(), variant.modelFile());
    Composition comp = new Composition(runtime, policy, artifacts);
    return OrtSessionAssembler.buildManager(consumerName, comp, arbiter, events);
  }
}

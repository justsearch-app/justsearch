/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.TensorInfo;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.ort.telemetry.AssemblerEvent;
import io.justsearch.ort.telemetry.AssemblerFailureKind;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Translates an {@link io.justsearch.configuration.model.VariantSelection}-driven
 * {@link Composition} into a live {@link SessionHandle}. Tempdoc 397 §7.3.
 *
 * <p>Single external entry point for ORT session-manager construction; callers outside
 * {@code io.justsearch.ort} cannot reach {@link NativeSessionHandle.Builder} directly (it is
 * package-private since §14.19 Phase 4).
 *
 * <p><strong>Entry points (post-§14.28 U1):</strong>
 *
 * <ul>
 *   <li>{@link #buildManager(String, Composition, GpuArbiter)} — the one production entry.
 *       Used by the composition root ({@code InferenceCompositionRoot.compose}) and by the
 *       test-fixture helper
 *       {@code io.justsearch.indexerworker.testing.InferenceCompositionRootTestHelper}.
 *   <li>{@link #verifyModelSession(OrtEnvironment, Path, GpuSessionConfig)} — narrow dev-tool
 *       entry for the {@code verifyModel} Gradle task (§14.22 Phase A). Returns a raw
 *       {@link OrtSession} for ad-hoc inspection; caller owns lifecycle. Does not produce a
 *       {@link SessionHandle}.
 *   <li>{@link #probeModelNames(OrtEnvironment, Path)} — short-lived probe session for
 *       {@code buildAssembly} factories (§14.24 FD-ProbeDeletion). Internal use only.
 * </ul>
 *
 * <p>The pre-§14.28 {@code buildFallback}, {@code composeRerankFallback}, and
 * {@code composeCitationFallback} surfaces are deleted. Tests + benchmarks that need a
 * {@link SessionHandle} from a model directory route through
 * {@code InferenceCompositionRootTestHelper.sessionFor}.
 *
 * <p>FP16 → FP32 fallback (§7.3, Q2 resolution): runtime branching inside the manager based on
 * {@link OrtException} from the first {@code createSession} call. ORT Java does not expose a
 * cheap "can this model load?" check, so resolver-side pre-probing is infeasible. The manager
 * retains the legacy behaviour; the paths are policy-declared on {@link ModelArtifacts}.
 */
public final class OrtSessionAssembler {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(OrtSessionAssembler.class);

  private OrtSessionAssembler() {}

  /**
   * Builds a fully-configured {@link SessionHandle} from a {@link Composition} + ephemeral
   * inputs (consumer name for logs, live GPU arbiter). Tempdoc 397 §14.13 Stage 4d: single entry
   * point for session-manager construction in composeX. The handle walks the policy records via
   * {@link SessionOptionsApplier} (§14.24 FA); callers see only {@link SessionHandle}.
   *
   * @param consumerName short identifier for log lines (e.g., {@code "ner"}, {@code "embed"})
   * @param comp fully resolved composition
   * @param arbiter live GPU arbitration callback (usually
   *     {@code () -> !signalBus.isMainGpuActive()})
   * @throws OrtException if session creation fails
   */
  public static SessionHandle buildManager(
      String consumerName, Composition comp, GpuArbiter arbiter) throws OrtException {
    return buildManager(consumerName, comp, arbiter, OrtSessionTelemetryEvents.NOOP);
  }

  /**
   * Tempdoc 414 overload. Identical to the 3-arg form except the supplied
   * {@link OrtSessionTelemetryEvents} is propagated into the {@link NativeSessionHandle} for
   * lifecycle-event recording. {@link OrtSessionTelemetryEvents#NOOP} matches the legacy
   * behaviour. Production code routes the worker's {@code OrtSessionTelemetryAdapter} through
   * here; tests + benchmarks use the 3-arg form (which delegates with NOOP).
   *
   * <p>This overload also makes {@link AssemblerFailureKind#NULL_VARIANT} observable — when
   * {@code policy.variant() == null} (the inbox observation 2026-04-24 stress-test bug), the
   * counter fires before the NPE propagates so the stress-test telemetry surfaces the failure
   * class.
   */
  public static SessionHandle buildManager(
      String consumerName, Composition comp, GpuArbiter arbiter, OrtSessionTelemetryEvents events)
      throws OrtException {
    ModelSessionPolicy policy = comp.modelSession();
    Path nativePath = OrtCudaHelper.resolveOrtNativePath(comp.artifacts().cpuModelPath().getParent());
    // observations.md L68 fix: ModelSessionPolicy.forFallback(...) deliberately constructs
    // variant=null to signal "no specific variant, CPU-only fallback path". Pre-fix the
    // assembler emitted the NULL_VARIANT telemetry then NPE'd on the next line trying to
    // read variant().executionProvider(). Now we treat null variant as implicit-CPU
    // (gpuEp=false) — matches the forFallback contract and unblocks the stress lane.
    if (policy.variant() == null) {
      events.onAssemblerEvent(
          new AssemblerEvent.Failed(consumerName, AssemblerFailureKind.NULL_VARIANT));
    }
    boolean gpuEp =
        policy.variant() != null && policy.variant().executionProvider() == ExecutionProvider.CUDA;
    // gpuModelPath is null when not CUDA (pre-FP16 fallback contract).
    Path gpuModelPath = gpuEp ? comp.artifacts().gpuModelPath() : null;

    // Tempdoc 397 §14.24 FA + §14.26 T1-B: policy records drive the handle. Flat
    // .gpuConfig/.deferCpuSession/etc. setters on the Builder were deleted; the handle
    // derives those scalars from the policy record.
    return NativeSessionHandle.builder(consumerName, comp.artifacts().cpuModelPath())
        .gpuModelPath(gpuModelPath)
        .nativePath(nativePath)
        .shouldUseGpu(arbiter::shouldUseGpu)
        .runtime(comp.runtime())
        .policy(policy)
        .events(events)
        .build();
  }

  // ---------------------------------------------------------------------------
  // Model introspection helpers (tempdoc 397 §14.24 FD-ProbeDeletion). Used by per-encoder
  // buildAssembly factories to populate EncoderShape records without leaking probe-session
  // logic into the SessionHandle interface.
  // ---------------------------------------------------------------------------

  /**
   * Input/output names in graph order, excluding dense and sparse initializers from inputs.
   */
  public record ProbedNames(java.util.Set<String> inputs, java.util.Set<String> outputs) {}

  /**
   * Reads the ONNX graph without loading tensor payloads. An unreadable declaration falls back
   * to the previous single-threaded NO_OPT session probe. Used by every
   * {@code buildAssembly} helper (tempdoc 397 §14.24 FD-ProbeDeletion).
   *
   * @throws OrtException if the probe session cannot be created (e.g., model file missing)
   */
  public static ProbedNames probeModelNames(OrtEnvironment env, Path modelPath)
      throws OrtException {
    try {
      var metadata = OnnxExternalData.readMetadata(modelPath);
      return new ProbedNames(metadata.inputs(), metadata.outputs());
    } catch (IOException | IllegalArgumentException e) {
      log.debug("ONNX metadata unreadable for {}; falling back to session probe", modelPath, e);
    }
    try (SessionOptions probeOpts = new SessionOptions()) {
      probeOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
      probeOpts.setInterOpNumThreads(1);
      probeOpts.setIntraOpNumThreads(1);
      try (OrtSession probe = env.createSession(modelPath.toString(), probeOpts)) {
        return new ProbedNames(probe.getInputNames(), probe.getOutputNames());
      }
    }
  }

  /**
   * Static tensor shape + element type for a named output, read from the ONNX graph declaration
   * without running inference.
   *
   * @param shape declared shape; a dimension of {@code -1} means dynamic (batch/sequence axes are
   *     typically dynamic; the trailing embedding-dimension axis is typically static)
   * @param type ONNX Runtime's Java element type (e.g. {@code FLOAT}, {@code FLOAT16})
   */
  public record ProbedTensorInfo(long[] shape, OnnxJavaType type) {}

  /**
   * Reads a single output tensor's declared shape/dtype without loading weights. Missing dimensions
   * or unreadable declarations fall back to {@link OrtSession#getOutputInfo()} so graph inference
   * preserves the old resolved values. Used by {@link ModelCapabilityResolver}
   * (tempdoc 710 Wave 2 Move 1) as a boot-time probe for the embedding-dimension capability fact
   * (when the graph's trailing output axis is statically known) and as a sanity check for the
   * declared precision capability against the graph's actual output element type. Both uses are
   * best-effort: a dynamic shape or a non-tensor output yields {@link Optional#empty()} rather
   * than failing the caller.
   *
   * @param outputName name of the output to inspect; if absent from the graph, returns {@link
   *     Optional#empty()}
   * @throws OrtException if the probe session cannot be created (e.g., model file missing)
   */
  public static Optional<ProbedTensorInfo> probeOutputTensorInfo(
      OrtEnvironment env, Path modelPath, String outputName) throws OrtException {
    try {
      var metadata = OnnxExternalData.readMetadata(modelPath);
      if (!metadata.unresolvedOutputs().contains(outputName)) {
        return Optional.ofNullable(metadata.tensors().get(outputName));
      }
    } catch (IOException | IllegalArgumentException e) {
      log.debug("ONNX output declaration unresolved for {}; falling back to session probe", modelPath, e);
    }
    try (SessionOptions probeOpts = new SessionOptions()) {
      probeOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
      probeOpts.setInterOpNumThreads(1);
      probeOpts.setIntraOpNumThreads(1);
      try (OrtSession probe = env.createSession(modelPath.toString(), probeOpts)) {
        Map<String, NodeInfo> outputInfo = probe.getOutputInfo();
        NodeInfo node = outputInfo.get(outputName);
        if (node == null || !(node.getInfo() instanceof TensorInfo tensorInfo)) {
          return Optional.empty();
        }
        return Optional.of(new ProbedTensorInfo(tensorInfo.getShape(), tensorInfo.type));
      }
    }
  }

  /**
   * Reads ModelProto {@code metadata_props} without loading weights; GraphProto metadata is
   * distinct and is ignored. Unreadable files fall back to one session probe, as in
   * {@link #probeModelNames(OrtEnvironment, Path)}. Used by {@link
   * ModelCapabilityResolver} (tempdoc 711 Item 3) as the embedded-metadata rung, one session per
   * {@code resolve} call (not per fact) — every capability fact the resolver checks against
   * embedded metadata reads from the same returned map.
   *
   * @return the model's custom metadata (reverse-DNS keys, e.g. {@code io.justsearch.pooling_mode})
   *     stamped at model-build time by {@code scripts/models/_common.py stamp_capabilities}; empty
   *     if the model file declares none
   * @throws OrtException if the probe session cannot be created (e.g., model file missing)
   */
  public static Map<String, String> probeCustomMetadata(OrtEnvironment env, Path modelPath)
      throws OrtException {
    try {
      return OnnxExternalData.readMetadata(modelPath).properties();
    } catch (IOException | IllegalArgumentException e) {
      log.debug("ONNX metadata unreadable for {}; falling back to session probe", modelPath, e);
    }
    try (SessionOptions probeOpts = new SessionOptions()) {
      probeOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
      probeOpts.setInterOpNumThreads(1);
      probeOpts.setIntraOpNumThreads(1);
      try (OrtSession probe = env.createSession(modelPath.toString(), probeOpts)) {
        return probe.getMetadata().getCustomMetadata();
      }
    }
  }

  /**
   * Builds an {@link OrtSession} for model verification — the {@code verifyModel} Gradle task in
   * {@code worker-core}. Narrow public entry that routes through the same
   * {@link SessionOptionsApplier} apply path as production session construction (tempdoc 397
   * §14.24 FA). Replaces the pre-FA verifier which ran its own hardcoded CUDA-option list.
   *
   * <p>Caller owns the returned session's lifecycle and must close it.
   *
   * @param env shared ORT environment singleton
   * @param modelPath absolute path to the ONNX model file to verify
   * @param gpuConfig non-null for GPU verification (CUDA provider + GPU session options);
   *     {@code null} for CPU verification (production session options, cache bypass — models
   *     targeted for verification may not yet have a {@code .cuda.optimized} companion file)
   * @return a freshly-created session; caller closes it
   * @throws OrtException if session creation fails
   */
  public static OrtSession verifyModelSession(
      OrtEnvironment env, Path modelPath, GpuSessionConfig gpuConfig) throws OrtException {
    // Tempdoc 397 §14.24 FA: route through SessionOptionsApplier so the verifier shares the
    // single apply path with production session construction. No string literals here — every
    // setter value flows from RuntimePolicy.defaults() + a synthetic ModelSessionPolicy.
    RuntimePolicy runtime = RuntimePolicy.defaults();
    if (gpuConfig != null) {
      ModelSessionPolicy policy = ModelSessionPolicy.forVerification(gpuConfig);
      try (SessionOptions opts = new SessionOptions();
          ai.onnxruntime.providers.OrtCUDAProviderOptions cudaOpts =
              new ai.onnxruntime.providers.OrtCUDAProviderOptions(gpuConfig.gpuDeviceId())) {
        SessionOptionsApplier.applyCudaProviderOptions(runtime, policy, cudaOpts);
        opts.addCUDA(cudaOpts);
        SessionOptionsApplier.applyBase(runtime, opts);
        SessionOptionsApplier.applyGpuSessionOptions(runtime, opts);
        return OnnxSessionCache.createCachedGpuSession(env, modelPath, opts);
      }
    }
    try (SessionOptions opts = new SessionOptions()) {
      SessionOptionsApplier.applyBase(runtime, opts);
      // CPU verification: bypass OnnxSessionCache (models targeted for verification may not
      // have a cached graph; the caller wants to verify the raw file loads).
      return env.createSession(modelPath.toString(), opts);
    }
  }
}

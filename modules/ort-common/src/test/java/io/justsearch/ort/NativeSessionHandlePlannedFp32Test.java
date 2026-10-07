/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4, check E-MEM4 (invariant 4). Resolved GPU policies keep the weights in the
 * arena, with kSameAsRequested and memory-pattern optimisation off (guard). On a planned profile a
 * failed FP16 GPU creation does not load the FP32 file on the GPU: the role runs on its CPU session
 * with a status reason, and its policy and the other roles' sessions are unchanged (repaired;
 * before E7a the FP32 file loaded on CUDA). Outside planned profiles the FP32 fallback on the GPU
 * is unchanged (guard).
 */
final class NativeSessionHandlePlannedFp32Test {

  private static final Path FP32 = Path.of("missing", "model.onnx");
  private static final Path FP16 = Path.of("missing", "model_fp16.onnx");
  private static final int DEVICE = 151;
  private static final long CAP = 512L * 1024 * 1024;
  private static final String REFUSED = "planned_profile_fp32_gpu_refused";

  @Test
  void aFailedFp16CreationOnAPlannedProfileDoesNotLoadTheFp32FileOnTheGpu() throws Exception {
    ModelSessionPolicy planned = planned(fallbackPolicy());
    List<Path> loaded = new CopyOnWriteArrayList<>();
    OrtSession otherGpu = mock(OrtSession.class);
    try (NativeSessionHandle other = handle("other-role", fallbackPolicy(), FP32);
        NativeSessionHandle handle = handle("planned-role", planned, FP16);
        var cuda = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var _ = mockStatic(SessionOptionsApplier.class);
        var cache = mockStatic(OnnxSessionCache.class)) {
      cuda.when(() -> OrtCudaHelper.checkMissingCudaRuntimeDlls(any())).thenReturn(List.of());
      OrtSession fp32Gpu = mock(OrtSession.class);
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
          .thenAnswer(invocation -> {
            Path path = invocation.getArgument(1);
            loaded.add(path);
            if (path.equals(FP16)) throw new OrtException("FP16 GPU session creation failed");
            return path.equals(FP32) && loaded.size() == 1 ? otherGpu : fp32Gpu;
          });
      try (SessionHandle.Lease otherLease = other.acquire(bulk())) {
        assertSame(otherGpu, otherLease.session(), "the other role's GPU session is created");
      }
      loaded.clear();

      try (SessionHandle.Lease lease = handle.acquire(bulk())) {
        assertEquals(List.of(FP16), loaded, "the FP32 file was loaded on the GPU after FP16 failed");
        assertTrue(lease.isCpu(), "the role runs on its CPU session");
      }
      assertFalse(handle.isGpuAvailable());
      String reason = handle.status().failureReason();
      assertNotNull(reason);
      assertTrue(reason.contains(REFUSED), "the status gives the reason: " + reason);
      assertEquals(planned, policyOf(handle), "the role's policy is not re-resolved");

      assertTrue(other.isGpuAvailable(), "the other role keeps its GPU session");
      assertEquals(fallbackPolicy(), policyOf(other), "the other role's cap is unchanged");
      try (SessionHandle.Lease otherLease = other.acquire(bulk())) {
        assertSame(otherGpu, otherLease.session());
      }
    }
  }

  @Test
  void outsidePlannedProfilesTheFp32FallbackOnTheGpuIsUnchanged() throws Exception {
    List<Path> loaded = new CopyOnWriteArrayList<>();
    OrtSession fp32Gpu = mock(OrtSession.class);
    try (NativeSessionHandle handle = handle("unplanned-role", fallbackPolicy(), FP16);
        var cuda = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var _ = mockStatic(SessionOptionsApplier.class);
        var cache = mockStatic(OnnxSessionCache.class)) {
      cuda.when(() -> OrtCudaHelper.checkMissingCudaRuntimeDlls(any())).thenReturn(List.of());
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
          .thenAnswer(invocation -> {
            Path path = invocation.getArgument(1);
            loaded.add(path);
            if (path.equals(FP16)) throw new OrtException("FP16 GPU session creation failed");
            return fp32Gpu;
          });
      try (SessionHandle.Lease lease = handle.acquire(bulk())) {
        assertEquals(List.of(FP16, FP32), loaded);
        assertSame(fp32Gpu, lease.session(), "the FP32 file runs on the GPU as before");
      }
      assertTrue(handle.isGpuAvailable());
    }
  }

  @Test
  void resolvedGpuPoliciesKeepWeightsInTheArenaWithSameAsRequestedAndNoPatternOptimisation()
      throws Exception {
    ResolvedConfig cfg = TestResolvedConfigHelper.fromEntries(Map.of());
    HardwareProfile hardware = HardwareProfile.gpuFull(12_000_000_000L);
    RuntimePolicy runtime = RuntimePolicyResolver.resolve(cfg, hardware);
    assertTrue(runtime.session().useDeviceAllocatorForInitializers(),
        "weights are allocated in the arena");
    assertEquals("kSameAsRequested", runtime.arena().extendStrategy());
    assertFalse(runtime.arena().memoryPatternOptimization());
    VariantSelection cuda =
        VariantSelection.optimal(FP16, ModelPrecision.FP16, ExecutionProvider.CUDA);
    for (EncoderRole role : EncoderRole.values()) {
      ModelSessionPolicy policy = ModelSessionPolicyResolver.resolve(role, cfg, hardware, cuda);
      assertEquals(Optional.empty(), policy.gpu().arenaExtendStrategyOverride(),
          role + " overrides the arena strategy");
      assertTrue(policy.runOptions().arenaShrinkage(), role + " runs without shrinkage");
    }
    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(runtime, opts);
      SessionOptionsApplier.applyGpuSessionOptions(runtime, opts);
      assertEquals("1", opts.getConfigEntries().get("session.use_device_allocator_for_initializers"));
    }
  }

  /**
   * The policy with planned-profile facts. Built reflectively so the check compiles where the
   * policy has no planned-profile component; there the base policy is used, and the check fails by
   * its assertion on the files loaded.
   */
  static ModelSessionPolicy planned(ModelSessionPolicy base) throws Exception {
    Constructor<ModelSessionPolicy.Gpu> plannedGpu;
    try {
      plannedGpu =
          ModelSessionPolicy.Gpu.class.getConstructor(
              long.class, int.class, Optional.class, boolean.class);
    } catch (NoSuchMethodException noPlannedProfile) {
      return base;
    }
    ModelSessionPolicy.Gpu gpu =
        plannedGpu.newInstance(
            base.gpu().arenaCapBytes(),
            base.gpu().cudaDeviceId(),
            base.gpu().arenaExtendStrategyOverride(),
            true);
    return new ModelSessionPolicy(
        base.variant(), gpu, base.cpu(), base.lifecycle(), base.runOptions());
  }

  private static ModelSessionPolicy fallbackPolicy() {
    return ModelSessionPolicy.forFallback(
        new GpuSessionConfig(DEVICE, CAP),
        null,
        /* deferCpuSession= */ true,
        /* gpuRetryEnabled= */ false,
        NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
  }

  private static NativeSessionHandle handle(String name, ModelSessionPolicy policy, Path gpuPath)
      throws Exception {
    NativeSessionHandle handle =
        NativeSessionHandle.builder(name, FP32)
            .gpuModelPath(gpuPath)
            .shouldUseGpu(() -> true)
            .runtime(RuntimePolicy.defaults())
            .policy(policy)
            .build();
    set(handle, "gpuSessionAttempted", false);
    set(handle, "gpuAvailable", false);
    set(handle, "cpuSession", mock(OrtSession.class));
    return handle;
  }

  private static SessionAcquisitionRequest bulk() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(5));
  }

  private static ModelSessionPolicy policyOf(NativeSessionHandle handle) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField("policy");
    field.setAccessible(true);
    return (ModelSessionPolicy) field.get(handle);
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}

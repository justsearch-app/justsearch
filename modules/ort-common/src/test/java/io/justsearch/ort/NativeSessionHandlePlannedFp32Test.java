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
  void aCapBelowRetainedWeightsSelectsCpuBeforeGpuCreation() throws Exception {
    var plan = GpuArenaPlan.resolve(GpuArenaPlanTest.facts(12_282, GpuArenaPlanTest.STANDARD_ONLY),
        new GpuArenaPlan.Settings(Optional.empty(), java.util.OptionalInt.empty(),
            java.util.OptionalInt.empty(), Map.of(EncoderRole.SPLADE, 100.0), false));
    DeviceArenaPlans.publish(DEVICE, plan);
    try (NativeSessionHandle handle = handle(EncoderRole.SPLADE.consumerName(), planned(fallbackPolicy()), FP16);
        var cuda = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var _ = mockStatic(SessionOptionsApplier.class);
        var cache = mockStatic(OnnxSessionCache.class)) {
      cuda.when(() -> OrtCudaHelper.checkMissingCudaRuntimeDlls(any())).thenReturn(List.of());
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
          .thenReturn(mock(OrtSession.class));
      try (var lease = handle.acquire(bulk())) {
        assertTrue(lease.isCpu(), "a cap below the retained weights must not initialize CUDA");
      }
      cache.verify(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()),
          org.mockito.Mockito.never());
    } finally {
      DeviceArenaPlans.withdraw(DEVICE);
    }
  }

  @Test
  void aCpuSelectedHandleDoesNotApplyItsGpuPackingBudget() throws Exception {
    var plan = GpuArenaPlan.resolve(GpuArenaPlanTest.facts(12_282, GpuArenaPlanTest.STANDARD_ONLY),
        new GpuArenaPlan.Settings(Optional.empty(), java.util.OptionalInt.empty(),
            java.util.OptionalInt.empty(), Map.of(EncoderRole.SPLADE, 100.0), false));
    DeviceArenaPlans.publish(DEVICE, plan);
    try (var handle = handle(EncoderRole.SPLADE.consumerName(), planned(fallbackPolicy()), FP16)) {
      try (var lease = handle.acquire(bulk())) { assertTrue(lease.isCpu()); }
      int count = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> GpuCallPacker.itemsPerCall(
          handle.arenaPlan(), EncoderRole.SPLADE, 1, 1, ignored -> 512),
          "a CPU call must not fail against an unusable GPU packing budget");
      assertEquals(1, count);
    } finally { DeviceArenaPlans.withdraw(DEVICE); }
  }

  @Test
  void aChangingArbiterCannotPromoteAnUnboundedCpuShapeToGpu() throws Exception {
    var plan = GpuArenaPlan.resolve(GpuArenaPlanTest.facts(12_282, GpuArenaPlanTest.STANDARD_ONLY),
        GpuArenaPlan.Settings.none());
    DeviceArenaPlans.publish(DEVICE, plan);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var roomy = planned(ModelSessionPolicy.forFallback(new GpuSessionConfig(DEVICE, 4096L * 1024 * 1024),
        null, true, false, NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS));
    try (var handle = handle(EncoderRole.SPLADE.consumerName(), roomy, FP16,
            () -> calls.incrementAndGet() > 2);
        var cache = mockStatic(OnnxSessionCache.class)) {
      int items = GpuCallPacker.itemsPerCall(handle.arenaPlan(), EncoderRole.SPLADE, 64, 64,
          ignored -> 512);
      SessionHandle.Lease acquired;
      try {
        var method = GpuCallPacker.class.getMethod("acquireForCall", SessionHandle.class,
            SessionAcquisitionRequest.class, EncoderRole.class, int.class, int.class);
        acquired = (SessionHandle.Lease) method.invoke(null, handle, bulk(), EncoderRole.SPLADE,
            items, 512);
      } catch (NoSuchMethodException legacy) {
        acquired = handle.acquire(bulk());
      }
      try (var lease = acquired) {
        assertTrue(lease.isCpu(), "a CPU-shaped call must remain CPU across arbiter changes");
      }
      cache.verify(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()),
          org.mockito.Mockito.never());
    } finally { DeviceArenaPlans.withdraw(DEVICE); }
  }

  @Test
  void nonFp16CpuPlacementHasAStatusReasonAndLogsOnce(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    var cfg = ResolvedConfig.builder()
        .contributeAutoDetected(Map.of("justsearch.gpu.memory_plan", "p12")).build();
    var variant = VariantSelection.optimal(dir.resolve("model.onnx"), ModelPrecision.FP32,
        ExecutionProvider.CUDA);
    var logger = mock(org.slf4j.Logger.class);
    try (var factory = mockStatic(org.slf4j.LoggerFactory.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      factory.when(() -> org.slf4j.LoggerFactory.getLogger(ModelSessionPolicyResolver.class))
          .thenReturn(logger);
      var policy = ModelSessionPolicyResolver.resolve(EncoderRole.SPLADE, cfg,
          new HardwareProfile(true, true, 12L * 1024 * 1024 * 1024), variant);
      ModelSessionPolicyResolver.resolve(EncoderRole.SPLADE, cfg,
          new HardwareProfile(true, true, 12L * 1024 * 1024 * 1024), variant);
      var lazy = new ModelSessionPolicy(policy.variant(), policy.gpu(), policy.cpu(),
          new ModelSessionPolicy.Lifecycle(true, false, NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS),
          policy.runOptions());
      try (var handle = handle(EncoderRole.SPLADE.consumerName(), lazy, FP16)) {
        assertEquals(0, policy.gpu().arenaCapBytes());
        org.junit.jupiter.api.Assertions.assertAll(
            () -> org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> org.mockito.Mockito.verify(logger,
                org.mockito.Mockito.times(1)).warn(
                    "Encoder uses CPU: planned_profile_precision_unmeasured ({})", ModelPrecision.FP32)),
            () -> assertTrue(handle.status().failureReason() != null
                && handle.status().failureReason().contains("planned_profile_precision_unmeasured"),
                "CPU placement must expose the unmeasured-precision reason"));
      }
    }
  }

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
    return handle(name, policy, gpuPath, () -> true);
  }

  private static NativeSessionHandle handle(String name, ModelSessionPolicy policy, Path gpuPath,
      java.util.function.BooleanSupplier shouldUseGpu) throws Exception {
    NativeSessionHandle handle =
        NativeSessionHandle.builder(name, FP32)
            .gpuModelPath(gpuPath)
            .shouldUseGpu(shouldUseGpu)
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

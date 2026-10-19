/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.answering;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.holdBackground;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.hybridLeg;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.set;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.warmCpu;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4, checks E-MEM2 and E-MEM3 (a, b): guards on behaviour E7a keeps.
 *
 * <ul>
 *   <li>Invariant 2: every GPU role's policy has shrinkage on for its device, and the plain, pinned
 *       and deadline runs of a GPU lease all pass the handle's run options.
 *   <li>Invariant 3: CPU sessions are created without the CUDA provider and are never seated; with
 *       the device's breaker held open, query legs run on the warm CPU session with cause {@code
 *       stuck}, and without a warm CPU session they are turned away with the breaker as reason.
 * </ul>
 *
 * <p>Real handles through the production assembler, on their devices' real gates; the native
 * sessions are mocks. E-MEM3 (c), the Engine's dedicated memory across (b), is a real-environment
 * row and is not run here.
 */
final class NativeSessionHandleDeviceMemoryGuardTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  @Test
  void everyGpuRolesPolicyHasShrinkageOnForItsDevice() throws Exception {
    ResolvedConfig cfg = TestResolvedConfigHelper.fromEntries(Map.of());
    HardwareProfile hardware = HardwareProfile.gpuFull(12_000_000_000L);
    VariantSelection cuda =
        VariantSelection.optimal(MODEL, ModelPrecision.FP16, ExecutionProvider.CUDA);
    List<EncoderRole> gpuRoles =
        List.of(EncoderRole.EMBEDDING, EncoderRole.BGE_M3, EncoderRole.SPLADE, EncoderRole.NER,
            EncoderRole.RERANKER);
    try (var construction = mockConstruction(OrtSession.RunOptions.class)) {
      for (EncoderRole role : gpuRoles) {
        ModelSessionPolicy policy = ModelSessionPolicyResolver.resolve(role, cfg, hardware, cuda);
        assertTrue(policy.runOptions().arenaShrinkage(), role + " has shrinkage off");
        try (OrtSession.RunOptions options = SessionOptionsApplier.buildGpuRunOptions(policy)) {
          verify(options).addRunConfigEntry(
              "memory.enable_memory_arena_shrinkage", "gpu:" + policy.gpu().cudaDeviceId());
        }
      }
      assertEquals(gpuRoles.size(), construction.constructed().size());
    }
  }

  @Test
  void plainPinnedAndDeadlineGpuRunsPassTheHandlesRunOptions() throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap(), any(OrtSession.RunOptions.class)))
        .thenAnswer(invocation -> mock(OrtSession.Result.class));
    when(gpu.run(anyMap(), anySet(), anyMap(), any(OrtSession.RunOptions.class)))
        .thenAnswer(invocation -> mock(OrtSession.Result.class));
    OrtSession.RunOptions shrinking = mock(OrtSession.RunOptions.class);
    NativeSessionHandle handle = handle("mem2-run-options", 152);
    set(handle, "gpuSession", gpu);
    set(handle, "gpuRunOptions", shrinking);
    try {
      try (SessionHandle.Lease lease = handle.acquire(background())) {
        assertFalse(lease.isCpu());
        assertSame(shrinking, lease.runOptions());
        lease.run(Map.of()).close();
        verify(gpu).run(anyMap(), same(shrinking));
        lease.runPinned(Map.of(), Map.of());
        verify(gpu).run(anyMap(), anySet(), anyMap(), same(shrinking));
      }
      try (SessionHandle.Lease lease = handle.acquire(background())) {
        lease.runWithin(Map.of(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5)).close();
      }
      verify(gpu, org.mockito.Mockito.times(2)).run(anyMap(), same(shrinking));
      verify(gpu, never()).run(anyMap());
    } finally {
      set(handle, "gpuRunOptions", null);
      handle.close();
    }
  }

  @Test
  void cpuSessionsAreCreatedWithoutTheCudaProviderAndAreNeverSeated() throws Exception {
    NativeSessionHandle handle = handle("mem3-cpu-unseated", 153);
    set(handle, "cpuSession", null);
    OrtSession cpu = answering();
    try (var options = mockConstruction(OrtSession.SessionOptions.class);
        var cache = mockStatic(OnnxSessionCache.class)) {
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), any(), any(), any()))
          .thenReturn(cpu);
      try (SessionHandle.Lease seatHolder = holdBackground(handle)) {
        long start = System.nanoTime();
        try (SessionHandle.Lease lease = handle.acquireCpu(background())) {
          assertTrue(lease.isCpu());
          assertSame(cpu, lease.session());
          assertEquals(SessionHandle.Lease.NO_DEVICE, lease.cudaDevice(), "a CPU lease is seated");
          lease.run(Map.of()).close();
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000,
            "the CPU lease waited for the device seat");
        assertFalse(seatHolder.isCpu());
      }
      cache.verify(
          () -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()), never());
      assertFalse(options.constructed().isEmpty(), "the CPU session was created here");
      for (OrtSession.SessionOptions created : options.constructed()) {
        verify(created, never()).addCUDA(any(ai.onnxruntime.providers.OrtCUDAProviderOptions.class));
        verify(created, never()).addCUDA(org.mockito.ArgumentMatchers.anyInt());
        verify(created, never()).addCUDA();
      }
    } finally {
      handle.close();
    }
  }

  @Test
  void withTheBreakerHeldOpenQueryLegsRunOnTheWarmCpuWithCauseStuckOrAreTurnedAway()
      throws Exception {
    int device = 154;
    injectGate(
        device, new DeviceGatePolicy(1, 4, 2_000L, 10_000L, 300L, 5_000L));
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch mayReturn = new CountDownLatch(1);
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap()))
        .thenAnswer(invocation -> mock(OrtSession.Result.class))
        .thenAnswer(invocation -> {
          running.countDown();
          assertTrue(mayReturn.await(60, TimeUnit.SECONDS));
          return mock(OrtSession.Result.class);
        });
    NativeSessionHandle warm = handle("mem3-breaker-warm", device);
    NativeSessionHandle cold = handle("mem3-breaker-cold", device);
    set(warm, "gpuSession", gpu);
    set(cold, "cpuSession", null);
    DeviceEncoderGate gate = DeviceEncoderGates.forDevice(device);
    AtomicReference<Throwable> runFailure = new AtomicReference<>();
    Thread stuck = new Thread(() -> {
      try (SessionHandle.Lease lease = warm.acquire(background())) {
        lease.run(Map.of()).close();
      } catch (Throwable thrown) {
        runFailure.set(thrown);
      }
    }, "mem3-stuck-run");
    try {
      try (SessionHandle.Lease first = warm.acquire(background())) {
        first.run(Map.of()).close();
      }
      warmCpu(warm);
      stuck.start();
      assertTrue(running.await(5, TimeUnit.SECONDS), "the stuck run started");
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (gate.breakerOpenSinceNanos().isEmpty() && System.nanoTime() < until) {
        Thread.sleep(20);
      }
      assertTrue(gate.breakerOpenSinceNanos().isPresent(), "the stuck run opened the breaker");

      for (int search = 0; search < 200; search++) {
        RecordingSpan leg = new RecordingSpan();
        try (Scope ignored = leg.makeCurrent();
            SessionHandle.Lease lease = warm.acquire(hybridLeg(Duration.ofSeconds(2)))) {
          assertTrue(lease.isCpu(), "search " + search + ": the dense leg encodes on the CPU");
          assertEquals(SessionHandle.Lease.NO_DEVICE, lease.cudaDevice());
          lease.run(Map.of()).close();
        }
        assertEquals(List.of("device_stuck"), leg.causes, "search " + search);
      }

      SessionAcquireDeadlineExceededException turnedAway =
          assertThrows(SessionAcquireDeadlineExceededException.class,
              () -> cold.acquire(hybridLeg(Duration.ofSeconds(2))),
              "a leg without a warm CPU session ran");
      assertTrue(turnedAway.breakerOpen(), "the reason is the open breaker");
      assertTrue(gate.breakerOpenSinceNanos().isPresent(), "the breaker was held open throughout");
    } finally {
      mayReturn.countDown();
      stuck.join(5_000);
      warm.close();
      cold.close();
    }
    assertFalse(stuck.isAlive());
    assertNull(runFailure.get());
  }

  /** Gives {@code device} a gate with {@code policy}, before any handle of the device seats. */
  private static void injectGate(int device, DeviceGatePolicy policy) throws Exception {
    Field field = DeviceEncoderGates.class.getDeclaredField("GATES");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Integer, DeviceEncoderGate> gates = (Map<Integer, DeviceEncoderGate>) field.get(null);
    DeviceEncoderGate prior =
        gates.putIfAbsent(device, new DeviceEncoderGate(policy, System::nanoTime));
    assertTrue(prior == null || prior.policy().equals(policy),
        "device " + device + " already has a gate with another policy");
  }

  private static SessionAcquisitionRequest background() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(5));
  }

  private static NativeSessionHandle handle(String name, int device) throws Exception {
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(device, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
    NativeSessionHandle handle =
        (NativeSessionHandle)
            OrtSessionAssembler.buildManager(
                name,
                new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(MODEL, MODEL)),
                () -> true);
    set(handle, "gpuSession", answering());
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    set(handle, "cpuSession", answering());
    return handle;
  }

  /** A recording span that keeps the CPU-fallback causes added to it. */
  private static final class RecordingSpan implements Span {
    final List<String> causes = new CopyOnWriteArrayList<>();

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
      if (name.equals("cpu_fallback.triggered")) {
        causes.add(attributes.get(AttributeKey.stringKey("fallback.cause")));
      }
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
      return addEvent(name, attributes);
    }

    @Override
    public Span setStatus(StatusCode statusCode, String description) {
      return this;
    }

    @Override
    public Span recordException(Throwable exception, Attributes additionalAttributes) {
      return this;
    }

    @Override
    public Span updateName(String name) {
      return this;
    }

    @Override
    public void end() {}

    @Override
    public void end(long timestamp, TimeUnit unit) {}

    @Override
    public SpanContext getSpanContext() {
      return SpanContext.getInvalid();
    }

    @Override
    public boolean isRecording() {
      return true;
    }
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/** Delegated K2-K5, K9-K11, K15: CPU demand is a bounded exception to GPU no-create. */
final class NativeSessionHandleDeferredCpuTest {
  private static final Path MODEL = Path.of("missing", "deferred.onnx");
  @TempDir Path temp;

  @Test void onlyActuallyPlacedCudaBackupsAreDeferred() {
    var cfg = TestResolvedConfigHelper.withDefaults();
    for (var role : new EncoderRole[] {EncoderRole.SPLADE, EncoderRole.RERANKER}) {
      for (var provider : ExecutionProvider.values()) {
        var variant = VariantSelection.optimal(MODEL, ModelPrecision.FP16, provider);
        var policy = ModelSessionPolicyResolver.resolve(role, cfg,
            HardwareProfile.gpuFull(12_000_000_000L), variant);
        assertEquals(provider == ExecutionProvider.CUDA, policy.lifecycle().deferCpuSession());
        assertSame(variant, policy.variant());
      }
      var planned = io.justsearch.configuration.resolved.ResolvedConfig.builder()
          .contributeAutoDetected(Map.of("justsearch.gpu.memory_plan", "p12")).build();
      var fp32 = VariantSelection.optimal(MODEL, ModelPrecision.FP32, ExecutionProvider.CUDA);
      var effectiveCpu = ModelSessionPolicyResolver.resolve(role, planned,
          HardwareProfile.gpuFull(12_000_000_000L), fp32);
      assertEquals(0, effectiveCpu.gpu().arenaCapBytes());
      assertFalse(effectiveCpu.lifecycle().deferCpuSession(), "effective CPU must be eager");
    }
  }

  @Test void servingWarmupCannotBecomeColdCpuDemandAfterArbitrationOrGpuFailure() throws Exception {
    var creates = new AtomicInteger();
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return cpu; })) {
      var ordinary = creating(Duration.ofSeconds(2), () -> false);
      SessionAcquisitionRequest maintenance;
      try {
        maintenance = SessionAcquisitionRequest.class.getDeclaredConstructor(
            SessionAcquisitionRequest.Urgency.class, long.class,
            java.util.function.BooleanSupplier.class, boolean.class, boolean.class, boolean.class)
            .newInstance(ordinary.urgency(), ordinary.deadlineNanos(),
                ordinary.cancellationRequested(), true, false, false);
      } catch (NoSuchMethodException beforeState) { maintenance = ordinary; }
      final var prewarm = maintenance;
      for (int state = 0; state < 3; state++) {
        set(fixture.handle, "gpuSessionAttempted", state > 0);
        set(fixture.handle, "gpuSessionReleasing", state == 2);
        var failure = assertThrows(SessionAcquireDeadlineExceededException.class,
            () -> fixture.acquire(prewarm));
        assertEquals(SessionAcquireDeadlineExceededException.Phase.LOADING, failure.phase());
        assertEquals(0, creates.get());
        assertNull(fixture.handle.peekCpuSession());
        assertFalse(fixture.handle.isQueryWarm());
      }
      assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      assertEquals(1, creates.get());
    }
    verify(cpu).close();
  }

  @Test void realCpuFirstUseRunsAndReusesWithoutQueryGpuCreation() throws Exception {
    // Tiny Identity graph: [2,3] float tensor, intentionally no CUDA construction or model download.
    Path model = Files.write(temp.resolve("identity.onnx"), Base64.getDecoder().decode(
        "CAk6aRIIdmVyaWZpZXIKGQoFaW5wdXQSBm91dHB1dCIISWRlbnRpdHlaFwoFaW5wdXQSDgoMCAESCAoCCAIKAggDYhgKBm91dHB1dBIOCgwIARIICgIIAgoCCAOCAQ4KBXNjb3BlEgVncmFwaEICEBFyDgoFc2NvcGUSBW1vZGVs"));
    for (var role : new EncoderRole[] {EncoderRole.SPLADE, EncoderRole.RERANKER}) {
      try (var handle = owner(role, model)) {
        assertNull(handle.peekCpuSession());
        assertFalse(handle.prewarmCpuForQueries(query(Duration.ofSeconds(5), () -> false)));
        assertNull(handle.peekCpuSession());
        OrtSession first = null;
        for (int i = 0; i < 2; i++) {
          try (var lease = assertDoesNotThrow(() -> handle.acquire(query(Duration.ofSeconds(5), () -> false)));
              var input = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                  new float[][] {{1, 2, 3}, {4, 5, 6}});
              var output = lease.run(Map.of("input", input))) {
            assertTrue(lease.isCpu());
            if (first == null) first = lease.session();
            else assertSame(first, lease.session());
            assertArrayEquals(new float[] {4, 5, 6}, ((float[][]) output.get(0).getValue())[1]);
          }
        }
        assertTrue(handle.isQueryWarm());
        assertFalse(handle.isGpuAvailable());
        assertFalse((boolean) field(handle, "gpuSessionAttempted"));
      }
    }
  }

  @Test void slowNativeConstructionReleasesCallerAndRetainsLateOwnedCompletion() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cpu = mock(OrtSession.class);
    var creates = new AtomicInteger();
    try (var fixture = new Fixture(() -> {
      creates.incrementAndGet(); entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS)); return cpu;
    })) {
      try {
        long start = System.nanoTime();
        var caller = fixture.async(creating(Duration.ofMillis(250), () -> false));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertInstanceOf(SessionAcquireDeadlineExceededException.class,
            assertDoesNotThrow(() -> caller.get(1, TimeUnit.SECONDS),
                "blocked native construction must release its caller by the original deadline"));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos(),
            "deadline must release the caller while the constructor is still blocked");
        assertEquals(1, field(fixture.handle, "nativeActionsInProgress"));
        assertEquals(1, threadsInUse(fixture.handle));
        assertNull(fixture.handle.peekCpuSession());
        release.countDown();
        awaitIdle(fixture.handle);
        assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
        assertEquals(1, creates.get());
      } finally { release.countDown(); }
    }
    verify(cpu).close();
  }

  @Test void concurrentWaitersKeepIndependentDeadlinesAndCancellation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    var creates = new AtomicInteger();
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> {
      creates.incrementAndGet(); entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS)); return cpu;
    })) {
      try {
        var initiator = fixture.async(query(Duration.ofSeconds(3), cancelled::get));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        var healthy = fixture.async(query(Duration.ofSeconds(3), () -> false));
        var shortCaller = fixture.async(query(Duration.ofMillis(100), () -> false));
        cancelled.set(true);
        assertInstanceOf(CancellationException.class, initiator.get(1, TimeUnit.SECONDS));
        assertInstanceOf(SessionAcquireDeadlineExceededException.class, shortCaller.get(1, TimeUnit.SECONDS));
        assertFalse(healthy.isDone());
        release.countDown();
        assertSame(cpu, healthy.get(2, TimeUnit.SECONDS));
        assertEquals(1, creates.get());
      } finally { release.countDown(); }
    }
    verify(cpu).close();
  }

  @Test void expiredOrCancelledDemandStartsNoNativeWork() throws Exception {
    var creates = new AtomicInteger();
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return mock(OrtSession.class); })) {
      assertThrows(SessionAcquireDeadlineExceededException.class,
          () -> fixture.acquire(new SessionAcquisitionRequest(SessionAcquisitionRequest.Urgency.FOREGROUND,
              System.nanoTime() - 1, () -> false, false, true)));
      assertThrows(CancellationException.class,
          () -> fixture.acquire(query(Duration.ofSeconds(1), () -> true)));
      assertEquals(0, creates.get());
      assertEquals(0, field(fixture.handle, "nativeActionsInProgress"));
    }
  }

  @Test void queuedNativeAdmissionCancellationStartsNothingAndReturnsCapacity() throws Exception {
    var creates = new AtomicInteger();
    var cancelled = new AtomicBoolean();
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return mock(OrtSession.class); })) {
      var device = (DetachableRun.Device) field(fixture.handle, "cpuRunThreads");
      var authority = query(Duration.ofSeconds(5), () -> false);
      device.enter(authority, authority.deadlineNanos());
      device.enter(authority, authority.deadlineNanos());
      try {
        var caller = fixture.async(query(Duration.ofSeconds(3), cancelled::get));
        cancelled.set(true);
        assertInstanceOf(CancellationException.class, caller.get(1, TimeUnit.SECONDS));
        assertEquals(0, creates.get());
        assertEquals(0, field(fixture.handle, "nativeActionsInProgress"));
        assertEquals(2, threadsInUse(fixture.handle));
      } finally { device.leave(); device.leave(); }
    }
  }

  @Test void retirementDuringNativeCreationRefusesThenCleansExactlyOnce() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> {
      entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return cpu;
    })) {
      try {
        var caller = fixture.async(query(Duration.ofSeconds(3), () -> false));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> fixture.handle.retire(Duration.ZERO));
        assertInstanceOf(SessionRetiredException.class, caller.get(1, TimeUnit.SECONDS));
        release.countDown();
        awaitIdle(fixture.handle);
        assertNull(fixture.handle.peekCpuSession());
        fixture.handle.close();
        assertEquals(SessionHandle.RetirementStatus.RETIRED, fixture.handle.retirementStatus());
      } finally { release.countDown(); }
    }
    verify(cpu).close();
  }

  @Test void failedConstructionIsRetryableWithoutDuplicateOwnership() throws Exception {
    var creates = new AtomicInteger();
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> {
      if (creates.incrementAndGet() == 1) throw new OrtException("first construction failed");
      return cpu;
    })) {
      assertThrows(SessionTemporarilyUnavailableException.class,
          () -> fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      assertNull(fixture.handle.peekCpuSession());
      assertFalse(fixture.handle.isQueryWarm());
      assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      assertEquals(2, creates.get());
    }
    verify(cpu).close();
  }

  @Test void repairWaitsExactOldLeaseAndConfirmedCloseBeforeReplacement() throws Exception {
    var old = mock(OrtSession.class);
    var replacement = mock(OrtSession.class);
    var creates = new AtomicInteger();
    try (var fixture = new Fixture(() -> creates.incrementAndGet() == 1 ? old : replacement)) {
      fixture.acquire(query(Duration.ofSeconds(2), () -> false));
      var held = fixture.handle.acquireCpu(query(Duration.ofSeconds(2), () -> false));
      fixture.handle.reportCpuSessionFailure(io.justsearch.ort.telemetry.CpuRecreateCause.UNKNOWN);
      try {
        var shortCaller = fixture.async(query(Duration.ofMillis(100), () -> false));
        assertInstanceOf(SessionAcquireDeadlineExceededException.class, shortCaller.get(1, TimeUnit.SECONDS));
        verify(old, never()).close();
        assertEquals(1, creates.get());
        awaitIdle(fixture.handle);
      } finally { held.close(); }
      doThrow(new OrtException("close refused")).doNothing().when(old).close();
      assertThrows(SessionTemporarilyUnavailableException.class,
          () -> fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      assertSame(old, fixture.handle.peekCpuSession());
      assertEquals(1, creates.get());
      assertSame(replacement, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      assertEquals(2, creates.get());
      verify(old, times(2)).close();
    }
    verify(replacement).close();
  }

  @Test void interruptedCallerLeavesRunningPreparationOwnedForAnotherCaller() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> {
      entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return cpu;
    })) {
      try {
        var caller = fixture.async(creating(Duration.ofSeconds(3), () -> false));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        fixture.callers.get(0).interrupt();
        assertInstanceOf(CancellationException.class, caller.get(1, TimeUnit.SECONDS));
        assertEquals(1, field(fixture.handle, "nativeActionsInProgress"));
        release.countDown();
        awaitIdle(fixture.handle);
        assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      } finally { release.countDown(); }
    }
    verify(cpu).close();
  }

  @Test void demandAbandonedBeforeNativeEntryStartsNothingAndHealthyWaiterCanRetry() throws Exception {
    var workerReady = new CountDownLatch(1);
    var startWorker = new CountDownLatch(1);
    var creates = new AtomicInteger();
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return cpu; })) {
      fixture.beforeNative = () -> {
        workerReady.countDown(); assertTrue(startWorker.await(5, TimeUnit.SECONDS)); return null;
      };
      try {
        var first = fixture.async(creating(Duration.ofMillis(200), () -> false));
        assertTrue(workerReady.await(1, TimeUnit.SECONDS));
        assertInstanceOf(SessionAcquireDeadlineExceededException.class, first.get(1, TimeUnit.SECONDS));
        assertEquals(0, creates.get());
        var healthy = fixture.async(query(Duration.ofSeconds(3), () -> false));
        startWorker.countDown();
        assertSame(cpu, healthy.get(2, TimeUnit.SECONDS));
        assertEquals(1, creates.get());
      } finally { startWorker.countDown(); }
    }
    verify(cpu).close();
  }

  @Test void retirementBeforeNativeEntryStartsNothingAndReclaimsAdmission() throws Exception {
    var workerReady = new CountDownLatch(1);
    var startWorker = new CountDownLatch(1);
    var creates = new AtomicInteger();
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return mock(OrtSession.class); })) {
      fixture.beforeNative = () -> {
        workerReady.countDown(); assertTrue(startWorker.await(5, TimeUnit.SECONDS)); return null;
      };
      try {
        var caller = fixture.async(query(Duration.ofSeconds(3), () -> false));
        assertTrue(workerReady.await(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> fixture.handle.retire(Duration.ZERO));
        assertInstanceOf(SessionRetiredException.class, caller.get(1, TimeUnit.SECONDS));
        startWorker.countDown();
        awaitIdle(fixture.handle);
        fixture.handle.close();
        assertEquals(0, creates.get());
        assertEquals(SessionHandle.RetirementStatus.RETIRED, fixture.handle.retirementStatus());
      } finally { startWorker.countDown(); }
    }
  }

  @Test void failedPrivateCandidateCloseRetainsCpuIdentityForRetirementRetry() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cpu = mock(OrtSession.class);
    doThrow(new OrtException("private close refused")).doNothing().when(cpu).close();
    try (var fixture = new Fixture(() -> {
      entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return cpu;
    })) {
      try {
        var caller = fixture.async(creating(Duration.ofSeconds(3), () -> false));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> fixture.handle.retire(Duration.ZERO));
        release.countDown();
        assertInstanceOf(RuntimeException.class, caller.get(2, TimeUnit.SECONDS));
        awaitIdle(fixture.handle);
        assertNull(fixture.handle.peekCpuSession());
        assertEquals(1, ((java.util.IdentityHashMap<?, ?>) field(fixture.handle, "sessionInstances")).size());
        // Exercise the real device-loss owner callback without changing physical/shared hardware.
        fixture.handle.onDeviceLost(719);
        assertEquals(1, ((java.util.IdentityHashMap<?, ?>) field(fixture.handle, "sessionInstances")).size(),
            "a failed private CPU candidate is not a lost GPU allocation");
        fixture.handle.close();
        assertEquals(SessionHandle.RetirementStatus.RETIRED, fixture.handle.retirementStatus());
      } finally { release.countDown(); }
    }
    verify(cpu, times(2)).close();
  }

  @Test void freeGpuStillWinsAndColdSemanticRecourseCanPrepareAfterSeatWait() throws Exception {
    var creates = new AtomicInteger();
    var cpu = mock(OrtSession.class);
    var gpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return cpu; })) {
      set(fixture.handle, "shouldUseGpu", (java.util.function.BooleanSupplier) () -> true);
      set(fixture.handle, "gpuSession", gpu);
      set(fixture.handle, "gpuSessionAttempted", true);
      set(fixture.handle, "gpuAvailable", true);
      set(fixture.handle, "gpuGeneration", 1L);
      set(fixture.handle, "gpuWarmGeneration", 1L);
      try (var lease = fixture.handle.acquire(query(Duration.ofSeconds(3), () -> false))) {
        assertFalse(lease.isCpu());
        assertEquals(0, creates.get());
      }
      try (var held = fixture.handle.acquire(SessionAcquisitionRequest.within(
          SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(5)));
          var observation = QueryCpuRecourse.observe()) {
        assertFalse(held.isCpu());
        var before = QueryCpuRecourse.lastTakenNanos();
        assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(3), () -> false)));
        assertEquals(new QueryCpuRecourse.Served("cpu", "contended"), observation.served());
        assertEquals(before, QueryCpuRecourse.lastTakenNanos(), "preparation is not successful output");
        assertEquals(1, creates.get());
      }
    }
    verify(cpu).close();
    verify(gpu).close();
  }

  @Test void preparationSharesTheExistingTwoNativeThreadLimitAcrossOwners() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var creates = new AtomicInteger();
    Callable<OrtSession> slow = () -> {
      creates.incrementAndGet(); entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS)); return mock(OrtSession.class);
    };
    try (var first = new Fixture(slow); var second = new Fixture(slow);
        var third = new Fixture(() -> { creates.incrementAndGet(); return mock(OrtSession.class); })) {
      try {
        var one = first.async(query(Duration.ofSeconds(3), () -> false));
        var two = second.async(query(Duration.ofSeconds(3), () -> false));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(2, threadsInUse(third.handle));
        assertThrows(SessionAcquireDeadlineExceededException.class,
            () -> third.acquire(query(Duration.ofMillis(100), () -> false)));
        assertEquals(2, creates.get(), "saturation must not start an unbounded third constructor");
        assertEquals(0, field(third.handle, "nativeActionsInProgress"));
        release.countDown();
        assertInstanceOf(OrtSession.class, one.get(2, TimeUnit.SECONDS));
        assertInstanceOf(OrtSession.class, two.get(2, TimeUnit.SECONDS));
      } finally { release.countDown(); }
    }
  }

  @Test void retirementCancelsCapacityWaitWithoutStartingNativeWork() throws Exception {
    var creates = new AtomicInteger();
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return mock(OrtSession.class); })) {
      var device = (DetachableRun.Device) field(fixture.handle, "cpuRunThreads");
      var authority = creating(Duration.ofSeconds(5), () -> false);
      device.enter(authority, authority.deadlineNanos());
      device.enter(authority, authority.deadlineNanos());
      try {
        var caller = fixture.async(query(Duration.ofSeconds(3), () -> false));
        var ownerLock = (java.util.concurrent.locks.ReentrantLock) field(fixture.handle, "cpuSessionLock");
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!ownerLock.isLocked() && System.nanoTime() < waitUntil) Thread.sleep(2);
        assertTrue(ownerLock.isLocked(), "caller must reach queued native capacity admission");
        fixture.handle.retire(Duration.ZERO);
        assertInstanceOf(SessionRetiredException.class, caller.get(1, TimeUnit.SECONDS));
        assertEquals(0, creates.get());
        assertEquals(0, field(fixture.handle, "nativeActionsInProgress"));
      } finally { device.leave(); device.leave(); }
    }
  }

  @Test void nativeThreadStartFailureReturnsTypedOutcomeAndCanRetryWithoutLeakingCapacity() throws Exception {
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> cpu)) {
      Object workingThreads = field(fixture.handle, "cpuPreparationThreads");
      set(fixture.handle, "cpuPreparationThreads", (ThreadFactory) action -> {
        throw new java.util.concurrent.RejectedExecutionException("injected thread refusal");
      });
      assertThrows(SessionAcquireDeadlineExceededException.class,
          () -> fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
      assertEquals(0, field(fixture.handle, "nativeActionsInProgress"));
      assertEquals(0, threadsInUse(fixture.handle));
      set(fixture.handle, "cpuPreparationThreads", workingThreads);
      assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
    }
    verify(cpu).close();
  }

  @Test void interruptionBeforeNativeEntryRemainsCancelledAfterCallerThreadTerminates() throws Exception {
    var ready = new CountDownLatch(1);
    var start = new CountDownLatch(1);
    var creates = new AtomicInteger();
    var cpu = mock(OrtSession.class);
    try (var fixture = new Fixture(() -> { creates.incrementAndGet(); return cpu; })) {
      fixture.beforeNative = () -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return null; };
      try {
        var caller = fixture.async(creating(Duration.ofSeconds(3), () -> false));
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        Thread initiator = fixture.callers.get(0);
        initiator.interrupt();
        assertInstanceOf(CancellationException.class, caller.get(1, TimeUnit.SECONDS));
        initiator.join(1_000);
        assertFalse(initiator.isAlive());
        start.countDown();
        awaitIdle(fixture.handle);
        assertEquals(0, creates.get(), "interrupted demand must not reappear after thread exit");
        assertSame(cpu, fixture.acquire(query(Duration.ofSeconds(2), () -> false)));
        assertEquals(1, creates.get());
      } finally { start.countDown(); }
    }
    verify(cpu).close();
  }

  private static NativeSessionHandle owner(EncoderRole role, Path model) throws OrtException {
    return NativeSessionHandle.builder(role.consumerName(), model)
        .runtime(RuntimePolicy.defaults())
        .policy(ModelSessionPolicy.forFallback(new GpuSessionConfig(0, 1024 * 1024), null, true, true, 60_000))
        .shouldUseGpu(() -> false).build();
  }

  private static SessionAcquisitionRequest query(Duration budget, java.util.function.BooleanSupplier cancel) {
    return new SessionAcquisitionRequest(SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + budget.toNanos(), cancel, false, true);
  }

  private static SessionAcquisitionRequest creating(Duration budget, java.util.function.BooleanSupplier cancel) {
    return new SessionAcquisitionRequest(SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + budget.toNanos(), cancel, true, true);
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true); field.set(owner, value);
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true); return field.get(owner);
  }

  private static int threadsInUse(NativeSessionHandle handle) throws Exception {
    Object device = field(handle, "cpuRunThreads");
    return (int) field(device, "live") + (int) field(device, "detached");
  }

  private static void awaitIdle(NativeSessionHandle handle) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while ((int) field(handle, "nativeActionsInProgress") != 0 && System.nanoTime() < end) Thread.sleep(5);
    assertEquals(0, field(handle, "nativeActionsInProgress"));
    assertEquals(0, threadsInUse(handle));
  }

  /** Static mocks belong to the native worker's thread, not the acquiring thread. */
  private static final class Fixture implements AutoCloseable {
    private final NativeSessionHandle handle;
    private final Callable<OrtSession> creator;
    private volatile Callable<Void> beforeNative = () -> null;
    private final java.util.List<Thread> callers = new java.util.ArrayList<>();

    Fixture(Callable<OrtSession> creator) throws Exception {
      this.creator = creator;
      handle = owner(EncoderRole.SPLADE, MODEL);
      try (var ignored = cache()) { /* Initialize instrumentation before any short request budget. */ }
      try {
        Field threads = NativeSessionHandle.class.getDeclaredField("cpuPreparationThreads");
        threads.setAccessible(true);
        threads.set(handle, (ThreadFactory) action -> Thread.ofPlatform().daemon(true).unstarted(() -> {
          try (var ignored = cache()) {
            beforeNative.call(); action.run();
          } catch (Exception failure) { throw new IllegalStateException(failure); }
        }));
      } catch (NoSuchFieldException beforeState) {
        // Before-state uses the same mocked native constructor synchronously on the caller.
      }
    }

    private MockedStatic<OnnxSessionCache> cache() {
      var cache = mockStatic(OnnxSessionCache.class);
      cache.when(() -> OnnxSessionCache.createCachedSession(any(), eq(MODEL), any(), any()))
          .thenAnswer(ignored -> creator.call());
      return cache;
    }

    OrtSession acquire(SessionAcquisitionRequest request) {
      try (var ignored = cache(); var lease = handle.acquire(request)) { return lease.session(); }
    }

    CompletableFuture<Object> async(SessionAcquisitionRequest request) {
      var answer = new CompletableFuture<Object>();
      Thread caller = Thread.ofPlatform().name("owned-cpu-demand").unstarted(() -> {
        try { answer.complete(acquire(request)); }
        catch (Throwable failure) { answer.complete(failure); }
      });
      callers.add(caller); caller.start(); return answer;
    }

    @Override public void close() throws Exception {
      for (Thread caller : callers) { caller.join(6_000); assertFalse(caller.isAlive()); }
      awaitIdle(handle);
      handle.close();
    }
  }
}

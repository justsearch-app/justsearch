package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NativeSessionHandle}.
 *
 * <p>Tests static utilities and state-machine behavior that don't require real ORT sessions. The
 * full lifecycle (selectSession, tryCreateGpuSession, releaseGpuSession) is tested indirectly
 * through consumer integration tests in worker-core and reranker modules.
 *
 * <p>Tempdoc 397 §14.26 T1-B: migrated off the flat Builder setters ({@code .gpuConfig},
 * {@code .deferCpuSession}, etc.) to the policy-record surface
 * ({@link ModelSessionPolicy#forFallback}).
 */
@DisplayName("NativeSessionHandle")
class NativeSessionHandleTest {

  private static final RuntimePolicy DEFAULT_RUNTIME = RuntimePolicy.defaults();

  private static ModelSessionPolicy cpuOnlyDeferred() {
    return ModelSessionPolicy.forFallback(
        /* gpuConfig= */ null,
        /* cpuOptLevel= */ null,
        /* deferCpuSession= */ true,
        /* gpuRetryEnabled= */ true,
        NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
  }

  private static ModelSessionPolicy gpuDeferred() {
    return ModelSessionPolicy.forFallback(
        new GpuSessionConfig(0, 512L * 1024 * 1024),
        /* cpuOptLevel= */ null,
        /* deferCpuSession= */ true,
        /* gpuRetryEnabled= */ true,
        NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
  }

  @Nested
  @DisplayName("isBfcArenaFailure — BFC arena allocation failure detection")
  class BfcArenaFailureDetection {

    @Test
    void detectsArenaOom() {
      var e =
          new OrtException(
              "BFCArena::AllocateRawInternal: Available memory of 536870912"
                  + " is smaller than requested bytes of 1073741824");
      assertTrue(NativeSessionHandle.isBfcArenaFailure(e));
    }

    @Test
    void rejectsGenericOrtException() {
      var e = new OrtException("Session creation failed: CUDA driver version is insufficient");
      assertFalse(NativeSessionHandle.isBfcArenaFailure(e));
    }

    @Test
    void handlesNullMessage() {
      var e = new OrtException((String) null);
      assertFalse(NativeSessionHandle.isBfcArenaFailure(e));
    }

    @Test
    void rejectsPartialMatch() {
      // Only "AllocateRawInternal" without the memory message — not a BFC failure
      var e = new OrtException("AllocateRawInternal: some other error");
      assertFalse(NativeSessionHandle.isBfcArenaFailure(e));
    }

    /**
     * Canary (tempdoc 710 Move 3): pins {@link NativeSessionHandle#isBfcArenaFailure} against the
     * VERBATIM ORT message format observed in production forensics (tempdoc 691 §J-4 / tempdoc
     * 686), not just a synthetic paraphrase like {@link #detectsArenaOom}. If a future ORT version
     * changes this message's wording (e.g. drops "AllocateRawInternal" or rewords "is smaller
     * than"), this test fails loudly instead of every GPU-OOM fallback path silently going dark.
     */
    @Test
    void detectsVerbatimObservedOrtMessage() {
      var e =
          new OrtException(
              "Error in execution: Non-zero status code returned while running Add node."
                  + " Name:'/encoder/layer.0/attention/self/Add' Status Message:"
                  + " D:\\a\\_work\\1\\s\\onnxruntime\\core\\framework\\bfc_arena.cc:358"
                  + " onnxruntime::BFCArena::AllocateRawInternal Available memory of 41473280 is"
                  + " smaller than requested bytes of 86175744");
      assertTrue(NativeSessionHandle.isBfcArenaFailure(e));
    }
  }

  @Nested
  @DisplayName("Builder — configuration validation")
  class BuilderConfiguration {

    @Test
    void builderRejectsNullConsumerName() {
      // NPE fires at the factory entry via requireNonNull (§14.28 U5 restored the
      // NPE-specific contract that T1-B weakened to Exception.class).
      assertThrows(
          NullPointerException.class,
          () -> NativeSessionHandle.builder(null, Path.of("model.onnx")));
    }

    @Test
    void builderRejectsNullModelPath() {
      assertThrows(
          NullPointerException.class, () -> NativeSessionHandle.builder("test", null));
    }

    @Test
    void buildFailsGracefullyOnMissingModel() {
      // Building with a non-existent model path should throw OrtException
      // (from OnnxSessionCache trying to load the model), not NPE or other errors
      Path nonexistent = Path.of("nonexistent/model.onnx");
      var builder =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(
                  ModelSessionPolicy.forFallback(
                      null,
                      null,
                      /* deferCpuSession= */ false,
                      true,
                      NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS));
      assertThrows(Exception.class, () -> builder.build());
    }

    @Test
    void buildWithDeferredCpuDoesNotFailOnMissingModel() throws OrtException {
      // When CPU session is deferred and GPU is configured (but won't actually load),
      // the build itself should succeed — no session is created at construction
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(gpuDeferred())
              .build()) {
        // Build succeeds — no sessions created yet
        assertNull(manager.peekCpuSession());
        assertTrue(manager.isGpuConfigured());
        assertFalse(manager.isGpuAvailable());
      }
    }

    @Test
    void builderRejectsMissingRuntime() {
      // T1-B: runtime is a required Builder input.
      Path nonexistent = Path.of("nonexistent/model.onnx");
      assertThrows(
          NullPointerException.class,
          () ->
              NativeSessionHandle.builder("test", nonexistent)
                  .policy(cpuOnlyDeferred())
                  .build());
    }

    @Test
    void builderRejectsMissingPolicy() {
      // T1-B: policy is a required Builder input.
      Path nonexistent = Path.of("nonexistent/model.onnx");
      assertThrows(
          NullPointerException.class,
          () ->
              NativeSessionHandle.builder("test", nonexistent)
                  .runtime(DEFAULT_RUNTIME)
                  .build());
    }
  }

  @Nested
  @DisplayName("OrtCudaStatus — initial state")
  class CudaStatusInitialState {

    @Test
    void cpuOnlyReturnsNotConfigured() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        OrtCudaStatus status = manager.status();
        assertFalse(status.configured());
        assertFalse(status.attempted());
        assertFalse(status.available());
        assertEquals("GPU not configured", status.failureReason());
      }
    }

    @Test
    void gpuConfiguredReturnsPending() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(gpuDeferred())
              .build()) {
        OrtCudaStatus status = manager.status();
        assertTrue(status.configured());
        assertFalse(status.attempted());
        assertFalse(status.available());
      }
    }
  }

  @Nested
  @DisplayName("Observability — state queries")
  class ObservabilityState {

    @Test
    void isGpuConfiguredFalseByDefault() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        assertFalse(manager.isGpuConfigured());
        assertFalse(manager.isGpuAvailable());
      }
    }

    @Test
    void isGpuConfiguredTrueWhenSet() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(gpuDeferred())
              .build()) {
        assertTrue(manager.isGpuConfigured());
        // GPU is not yet available (lazy init, no real CUDA)
        assertFalse(manager.isGpuAvailable());
      }
    }

    @Test
    void peekCpuSessionNullWhenDeferred() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        assertNull(manager.peekCpuSession());
      }
    }
  }

  @Nested
  @DisplayName("reportCpuSessionFailure — deferred recreation")
  class CpuSessionFailureRecovery {

    @Test
    void reportDoesNotThrow() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        // Calling reportCpuSessionFailure when cpuSession is null should not throw
        assertDoesNotThrow(
            () ->
                manager.reportCpuSessionFailure(
                    io.justsearch.ort.telemetry.CpuRecreateCause.UNKNOWN));
      }
    }

    @Test
    void closeIsIdempotent() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build();
      manager.close();
      assertDoesNotThrow(manager::close); // second close is safe
    }

    @Test
    void heldCpuLeasePreventsExactSessionCloseAndRetirementRefusesNewLeases() throws Exception {
      OrtSession cpu = mock(OrtSession.class);
      NativeSessionHandle manager = deferredCpuHandle();
      set(manager, "cpuSession", cpu);
      SessionHandle.Lease held = manager.acquireCpu(request());
      CountDownLatch closeFinished = new CountDownLatch(1);
      Thread closer = new Thread(() -> {
        manager.close();
        closeFinished.countDown();
      }, "native-cpu-retire-test");
      closer.start();
      awaitRetired(manager);

      assertEquals(SessionHandle.RetirementStatus.RETIRING, manager.retirementStatus());
      verify(cpu, never()).close();
      assertThrows(SessionRetiredException.class, () -> manager.acquireCpu(request()));
      held.close();

      assertTrue(closeFinished.await(1, TimeUnit.SECONDS));
      assertEquals(SessionHandle.RetirementStatus.RETIRED, manager.retirementStatus());
      verify(cpu, timeout(1_000).times(1)).close();
      held.close(); // release is idempotent and cannot underflow the exact-instance count
      verify(cpu).close();
    }

    @Test
    void heldGpuLeasePreventsExactSessionCloseAndRetirementRefusesNewLeases() throws Exception {
      OrtSession gpu = mock(OrtSession.class);
      NativeSessionHandle manager = NativeSessionHandle.builder("gpu-retirement-test",
              Path.of("nonexistent/model.onnx"))
          .runtime(DEFAULT_RUNTIME)
          .policy(gpuDeferred())
          .shouldUseGpu(() -> true)
          .build();
      set(manager, "gpuSession", gpu);
      set(manager, "gpuSessionAttempted", true);
      set(manager, "gpuAvailable", true);
      SessionHandle.Lease held = manager.acquire(request());
      assertFalse(held.isCpu(), "the held lease must name the injected GPU instance");
      CountDownLatch closeFinished = new CountDownLatch(1);
      Thread closer = new Thread(() -> {
        manager.close();
        closeFinished.countDown();
      }, "native-gpu-retire-test");
      closer.start();
      awaitRetired(manager);

      assertEquals(SessionHandle.RetirementStatus.RETIRING, manager.retirementStatus());
      verify(gpu, never()).close();
      assertThrows(SessionRetiredException.class, () -> manager.acquire(request()));
      held.close();

      assertTrue(closeFinished.await(1, TimeUnit.SECONDS));
      assertEquals(SessionHandle.RetirementStatus.RETIRED, manager.retirementStatus());
      verify(gpu, timeout(1_000).times(1)).close();
      held.close();
      verify(gpu).close();
    }

    @Test
    void cpuRecreationWaitsForHeldOldInstanceBeforeClosingIt() throws Exception {
      OrtSession oldCpu = mock(OrtSession.class);
      NativeSessionHandle manager = deferredCpuHandle();
      set(manager, "cpuSession", oldCpu);
      SessionHandle.Lease held = manager.acquireCpu(request());
      manager.reportCpuSessionFailure(
          io.justsearch.ort.telemetry.CpuRecreateCause.BFC_ARENA_FAILURE);

      CountDownLatch attempted = new CountDownLatch(1);
      AtomicReference<Throwable> outcome = new AtomicReference<>();
      Thread recreator = new Thread(() -> {
        attempted.countDown();
        try {
          manager.acquireCpu(request());
        } catch (Throwable thrown) {
          outcome.set(thrown);
        }
      }, "native-cpu-recreate-test");
      recreator.start();
      assertTrue(attempted.await(1, TimeUnit.SECONDS));
      Thread.sleep(50);
      verify(oldCpu, never()).close();

      held.close();
      recreator.join(1_000);
      assertFalse(recreator.isAlive());
      verify(oldCpu).close();
      assertInstanceOf(SessionTemporarilyUnavailableException.class, outcome.get());
      manager.close();
    }

    @Test
    void cpuRecreationWaitHonorsAcquisitionDeadlineWithoutClosingHeldInstance() throws Exception {
      OrtSession oldCpu = mock(OrtSession.class);
      NativeSessionHandle manager = deferredCpuHandle();
      set(manager, "cpuSession", oldCpu);
      SessionHandle.Lease held = manager.acquireCpu(request());
      manager.reportCpuSessionFailure(
          io.justsearch.ort.telemetry.CpuRecreateCause.BFC_ARENA_FAILURE);

      assertThrows(
          SessionAcquireDeadlineExceededException.class,
          () ->
              manager.acquireCpu(
                  SessionAcquisitionRequest.within(
                      SessionAcquisitionRequest.Urgency.FOREGROUND,
                      Duration.ofMillis(25))));
      verify(oldCpu, never()).close();

      held.close();
      manager.close();
      verify(oldCpu).close();
    }

    @Test
    void failedNativeCloseRetainsSessionAndLaterCloseRetries() throws Exception {
      OrtSession cpu = mock(OrtSession.class);
      doThrow(new OrtException("first close refused")).doNothing().when(cpu).close();
      NativeSessionHandle manager = deferredCpuHandle();
      set(manager, "cpuSession", cpu);
      try (SessionHandle.Lease ignored = manager.acquireCpu(request())) {
        // Register the injected native identity through the production acquisition seam.
      }

      manager.close();
      verify(cpu).close();
      assertEquals(SessionHandle.RetirementStatus.REFUSED, manager.retirementStatus());
      assertSame(cpu, manager.peekCpuSession());
      assertThrows(SessionRetiredException.class, () -> manager.acquireCpu(request()));

      manager.close();
      verify(cpu, org.mockito.Mockito.times(2)).close();
      assertNull(manager.peekCpuSession());
      assertEquals(SessionHandle.RetirementStatus.RETIRED, manager.retirementStatus());
    }

    @Test
    void failedRunOptionsCloseIsReportedAsRefusedAndRetryable() throws Exception {
      OrtSession.RunOptions runOptions = mock(OrtSession.RunOptions.class);
      doThrow(new RuntimeException("first close refused")).doNothing().when(runOptions).close();
      NativeSessionHandle manager = deferredCpuHandle();
      set(manager, "gpuRunOptions", runOptions);

      manager.close();
      assertEquals(SessionHandle.RetirementStatus.REFUSED, manager.retirementStatus());
      verify(runOptions).close();

      manager.close();
      assertEquals(SessionHandle.RetirementStatus.RETIRED, manager.retirementStatus());
      verify(runOptions, org.mockito.Mockito.times(2)).close();
    }
  }

  private static NativeSessionHandle deferredCpuHandle() throws OrtException {
    return NativeSessionHandle.builder("native-lifetime-test", Path.of("missing", "model.onnx"))
        .runtime(DEFAULT_RUNTIME)
        .policy(cpuOnlyDeferred())
        .build();
  }

  private static SessionAcquisitionRequest request() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(2));
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }

  private static void awaitRetired(NativeSessionHandle handle) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField("retired");
    field.setAccessible(true);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!(boolean) field.get(handle) && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue((boolean) field.get(handle), "handle did not enter monotonic retirement");
  }
}

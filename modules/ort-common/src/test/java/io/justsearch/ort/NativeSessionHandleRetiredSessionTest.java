/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4, check E-MEM5 (invariant 5): a recomposition whose GPU run is stuck past {@code
 * recompositionWaitMs} retires the old session to its run. Until that session closes, no GPU
 * session is created for the role, by the old handle or by the handle the recomposition builds.
 * Meanwhile the role's query leg takes the CPU recourse when the CPU session is warm, otherwise
 * keyword search with cause {@code LOADING}, and its bulk work runs on the CPU session. Once the
 * retired session has closed, the role's GPU session is created again and its handles are told.
 *
 * <p>Real handles through the production assembler, on their device's real gate (with a short
 * {@code recompositionWaitMs}); the native sessions and GPU session creation are mocks.
 */
final class NativeSessionHandleRetiredSessionTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");
  private static final String ROLE = "mem5-embed";
  private static final int DEVICE = 150;

  @Test
  void noGpuSessionForTheRoleUntilItsRetiredSessionCloses() throws Exception {
    injectGate(DEVICE, new DeviceGatePolicy(1, 4, 2_000L, 200L, 60_000L, 5_000L));
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch mayReturn = new CountDownLatch(1);
    OrtSession stuckGpu = mock(OrtSession.class);
    when(stuckGpu.run(anyMap())).thenAnswer(invocation -> {
      running.countDown();
      assertTrue(mayReturn.await(60, TimeUnit.SECONDS));
      return mock(OrtSession.Result.class);
    });
    NativeSessionHandle old = handle(stuckGpu);
    set(old, "cpuSession", answering());
    AtomicReference<Throwable> runFailure = new AtomicReference<>();
    Thread run = new Thread(() -> {
      try (SessionHandle.Lease lease = old.acquire(bulk(Duration.ofSeconds(30)))) {
        lease.run(Map.of()).close();
      } catch (Throwable thrown) {
        runFailure.set(thrown);
      }
    }, "mem5-stuck-run");
    NativeSessionHandle recomposed = null;
    AtomicInteger created = new AtomicInteger();
    List<String> told = new CopyOnWriteArrayList<>();
    OrtSession newGpu = answering();
    try (var cuda = mockStatic(OrtCudaHelper.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var _ = mockStatic(SessionOptionsApplier.class);
        var cache = mockStatic(OnnxSessionCache.class)) {
      cuda.when(() -> OrtCudaHelper.checkMissingCudaRuntimeDlls(any())).thenReturn(List.of());
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
          .thenAnswer(ignored -> {
            created.incrementAndGet();
            return newGpu;
          });

      run.start();
      assertTrue(running.await(5, TimeUnit.SECONDS), "the GPU run started");
      assertTimeoutPreemptively(Duration.ofSeconds(5), old::releaseGpu,
          "the release returns after recompositionWaitMs");
      verify(stuckGpu, org.mockito.Mockito.never()).close();

      // The recomposition builds the role's handle again; its GPU session is not created yet.
      recomposed = handle(null);
      set(recomposed, "gpuSessionAttempted", false);
      set(recomposed, "gpuAvailable", false);
      set(recomposed, "cpuSession", answering());
      recomposed.addGenerationListener(() -> told.add("generation"));
      NativeSessionHandle fresh = recomposed;

      // A query leg without a warm CPU session: keyword search with cause LOADING.
      var loading =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () -> fresh.acquire(hybridLeg(Duration.ofSeconds(2))),
              "a query leg ran without a warm session while the retired session was open");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.LOADING, loading.phase());

      // Bulk work runs on the CPU session meanwhile; it is not held.
      SessionHandle.Lease bulkLease =
          assertDoesNotThrow(
              () -> fresh.acquire(bulk(Duration.ofMillis(500))),
              "bulk work was held behind the retired run instead of running on the CPU");
      try (bulkLease) {
        assertTrue(bulkLease.isCpu(), "bulk work runs on the CPU session");
      }
      SessionHandle.Lease oldBulk =
          assertDoesNotThrow(() -> old.acquire(bulk(Duration.ofMillis(500))));
      try (oldBulk) {
        assertTrue(oldBulk.isCpu(), "the retiring handle creates no GPU session either");
      }
      assertEquals(0, created.get(), "no GPU session was created while the retired one was open");

      // With the CPU session warm, the query leg takes the CPU recourse.
      warmCpu(fresh);
      try (SessionHandle.Lease leg =
          assertDoesNotThrow(() -> fresh.acquire(hybridLeg(Duration.ofSeconds(2))))) {
        assertTrue(leg.isCpu(), "the query leg takes the warm CPU session");
      }
      assertEquals(0, created.get());

      // The retired run returns and closes its session; the role's handles are told.
      told.clear();
      mayReturn.countDown();
      run.join(5_000);
      assertFalse(run.isAlive());
      assertNull(runFailure.get());
      verify(stuckGpu, timeout(2_000)).close();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (told.isEmpty() && System.nanoTime() < until) Thread.sleep(5);
      assertFalse(told.isEmpty(), "the recomposed handle was told the retired session closed");

      try (SessionHandle.Lease next = fresh.acquire(bulk(Duration.ofSeconds(5)))) {
        assertFalse(next.isCpu(), "the GPU session is created once the retired one has closed");
        assertTrue(next.session() == newGpu);
      }
      assertEquals(1, created.get());
    } finally {
      mayReturn.countDown();
      run.join(5_000);
      old.close();
      if (recomposed != null) recomposed.close();
    }
  }

  private static void injectGate(int device, DeviceGatePolicy policy) throws Exception {
    Field field = DeviceEncoderGates.class.getDeclaredField("GATES");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Integer, DeviceEncoderGate> gates = (Map<Integer, DeviceEncoderGate>) field.get(null);
    DeviceEncoderGate prior = gates.putIfAbsent(device, new DeviceEncoderGate(policy, System::nanoTime));
    assertTrue(prior == null || prior.policy().equals(policy),
        "device " + device + " already has a gate with another policy");
  }

  private static SessionAcquisitionRequest bulk(Duration budget) {
    return SessionAcquisitionRequest.within(SessionAcquisitionRequest.Urgency.BACKGROUND, budget);
  }

  private static SessionAcquisitionRequest hybridLeg(Duration budget) {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + budget.toNanos(),
        () -> false,
        /* mayCreateGpuSession= */ false,
        /* releaseCallerAtDeadline= */ true);
  }

  private static void warmCpu(SessionHandle handle) throws Exception {
    try (SessionHandle.Lease lease =
            handle.acquireCpu(
                SessionAcquisitionRequest.within(
                    SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30)));
        OrtSession.Result ignored = lease.run(Map.of())) {
      assertTrue(lease.isCpu());
    }
  }

  private static NativeSessionHandle handle(OrtSession gpuSession) throws Exception {
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(DEVICE, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
    NativeSessionHandle handle =
        (NativeSessionHandle)
            OrtSessionAssembler.buildManager(
                ROLE,
                new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(MODEL, MODEL)),
                () -> true);
    if (gpuSession != null) {
      set(handle, "gpuSession", gpuSession);
      set(handle, "gpuSessionAttempted", true);
      set(handle, "gpuAvailable", true);
    }
    return handle;
  }

  private static OrtSession answering() throws Exception {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> mock(OrtSession.Result.class));
    return session;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}

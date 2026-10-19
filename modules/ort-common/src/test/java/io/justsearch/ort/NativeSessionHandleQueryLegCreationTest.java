/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Spec D step 7 (§4.2 item 5; S21 and P2): a query leg that has a keyword fallback (an acquisition
 * that may not create sessions) never creates or retries the GPU session on the query thread. It
 * runs on a warm GPU session or the existing CPU session, and reports {@code LOADING} when neither
 * exists. Creation belongs to the prewarm, before the app reports ready.
 *
 * <p>Real seam: the production {@link NativeSessionHandle} built as the assembler builds it; only
 * the native sessions are mocks installed in its fields. Uses only API that exists before the
 * change, so each check runs on the before-state.
 */
final class NativeSessionHandleQueryLegCreationTest {

  @Test
  void aFallbackQueryDoesNotCloseAFailedCpuSession() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    java.util.concurrent.CountDownLatch closeEntered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch closeMayReturn = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(call -> {
      closeEntered.countDown();
      closeMayReturn.await(5, java.util.concurrent.TimeUnit.SECONDS);
      return null;
    }).when(cpu).close();
    NativeSessionHandle handle = gpuConfiguredHandle("query-leg-failed-cpu");
    var completed = new java.util.concurrent.CompletableFuture<Throwable>();
    Thread caller = new Thread(() -> {
      try (var ignored = handle.acquire(queryLeg())) {
        completed.complete(null);
      } catch (Throwable failure) {
        completed.complete(failure);
      }
    });
    try {
      set(handle, "cpuSession", cpu);
      set(handle, "cpuSessionFailed", true);
      caller.start();
      long end = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      while (!completed.isDone() && closeEntered.getCount() != 0 && System.nanoTime() < end) {
        Thread.sleep(5);
      }
      assertEquals(1, closeEntered.getCount(), "fallback query entered native close");
      assertTrue(completed.isDone(), "fallback query retained its caller");
      var failure = org.junit.jupiter.api.Assertions.assertInstanceOf(
          SessionAcquireDeadlineExceededException.class, completed.get());
      assertEquals(SessionAcquireDeadlineExceededException.Phase.LOADING, failure.phase());
    } finally {
      closeMayReturn.countDown();
      caller.join(5_000);
      handle.close();
    }
  }

  @Test
  // S21: before the GPU session exists, a query leg runs on the CPU session and creates nothing
  void aQueryLegBeforeTheGpuSessionExistsRunsOnTheCpuSessionWithoutCreatingIt() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    NativeSessionHandle handle = gpuConfiguredHandle("query-leg-before-gpu");
    try {
      set(handle, "cpuSession", cpu);
      OrtCudaStatus before = handle.status();

      try (SessionHandle.Lease lease = handle.acquire(queryLeg())) {
        assertTrue(lease.isCpu(), "the query leg must not wait for or select a new GPU session");
        assertSame(cpu, lease.session());
      }

      assertFalse((boolean) get(handle, "gpuSessionAttempted"),
          "the query leg tried to create the GPU session on the query thread");
      assertEquals(before, handle.status(), "the query leg changed the GPU session status");
    } finally {
      handle.close();
    }
  }

  @Test
  // S21: after a GPU release, a query leg runs on the CPU session and does not re-create the GPU one
  void aQueryLegAfterAGpuReleaseDoesNotRecreateTheGpuSession() throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    OrtSession cpu = mock(OrtSession.class);
    NativeSessionHandle handle = gpuConfiguredHandle("query-leg-after-release");
    try {
      set(handle, "gpuSession", gpu);
      set(handle, "gpuSessionAttempted", true);
      set(handle, "gpuAvailable", true);
      set(handle, "cpuSession", cpu);
      handle.releaseGpu();

      try (SessionHandle.Lease lease = handle.acquire(queryLeg())) {
        assertTrue(lease.isCpu());
        assertSame(cpu, lease.session());
      }

      assertFalse((boolean) get(handle, "gpuSessionAttempted"),
          "the query leg re-created the released GPU session on the query thread");
      assertNull(get(handle, "gpuSession"));
    } finally {
      handle.close();
    }
  }

  @Test
  // S21: with no usable session yet, a query leg reports LOADING instead of creating one
  void aQueryLegWithNoUsableSessionReportsLoadingAndCreatesNothing() throws Exception {
    NativeSessionHandle handle = gpuConfiguredHandle("query-leg-loading");
    try {
      var loading =
          assertThrows(SessionAcquireDeadlineExceededException.class,
              () -> handle.acquire(queryLeg()));

      assertEquals(SessionAcquireDeadlineExceededException.Phase.LOADING, loading.phase());
      assertFalse((boolean) get(handle, "gpuSessionAttempted"),
          "the query leg tried to create the GPU session on the query thread");
      assertNull(get(handle, "cpuSession"), "the query leg created the deferred CPU session");
    } finally {
      handle.close();
    }
  }

  @Test
  // P2: without a GPU, a query leg runs on the CPU session
  void aCpuOnlyQueryLegRunsOnTheCpuSession() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    NativeSessionHandle handle = handle("query-leg-cpu-only", null);
    try {
      set(handle, "cpuSession", cpu);

      try (SessionHandle.Lease lease = handle.acquire(queryLeg())) {
        assertTrue(lease.isCpu());
        assertSame(cpu, lease.session());
      }
    } finally {
      handle.close();
    }
  }

  @Test
  // P2: when the GPU session failed, a query leg runs on the CPU session and does not retry the GPU
  void aQueryLegAfterAFailedGpuRunsOnTheCpuSession() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    NativeSessionHandle handle = gpuConfiguredHandle("query-leg-gpu-failed");
    try {
      set(handle, "gpuSessionAttempted", true);
      set(handle, "gpuAvailable", false);
      set(handle, "cpuSession", cpu);

      try (SessionHandle.Lease lease = handle.acquire(queryLeg())) {
        assertTrue(lease.isCpu());
        assertSame(cpu, lease.session());
      }
      assertNull(get(handle, "gpuSession"));
    } finally {
      handle.close();
    }
  }

  /** A query leg with a keyword fallback: foreground, may not create sessions. */
  private static SessionAcquisitionRequest queryLeg() {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + Duration.ofSeconds(5).toNanos(),
        () -> false,
        /* mayCreateGpuSession= */ false,
        /* releaseCallerAtDeadline= */ true);
  }

  /** The embedding role's CUDA shape: GPU configured, CPU session deferred, no retry. */
  private static NativeSessionHandle gpuConfiguredHandle(String name) throws Exception {
    return handle(name, new GpuSessionConfig(0, 512L * 1024 * 1024));
  }

  private static NativeSessionHandle handle(String name, GpuSessionConfig gpuConfig)
      throws Exception {
    Path model = Path.of("missing", "model.onnx");
    return NativeSessionHandle.builder(name, model)
        .gpuModelPath(gpuConfig != null ? model : null)
        .shouldUseGpu(() -> true)
        .runtime(RuntimePolicy.defaults())
        .policy(
            ModelSessionPolicy.forFallback(
                gpuConfig,
                null,
                /* deferCpuSession= */ true,
                /* gpuRetryEnabled= */ false,
                NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS))
        .build();
  }

  private static Object get(NativeSessionHandle handle, String name) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(handle);
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}

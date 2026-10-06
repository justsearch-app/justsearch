/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec D test L1 (§1.5 safety): a query run released at its deadline. The caller gets {@code RUN}
 * by its deadline; its {@code close()} is then a no-op; the run thread releases the lease exactly
 * once when the native call returns; and {@code releaseGpu} during the run never closes the session
 * mid-run. The native session is a mock whose {@code run} blocks until the test lets it return.
 */
final class NativeSessionHandleDetachedRunTest {

  private static final Duration DEADLINE = Duration.ofMillis(300);

  @Test
  void aRunPastTheDeadlineReleasesTheCallerAndItsRunThreadReleasesOnce() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch mayReturn = new CountDownLatch(1);
    OrtSession gpu = mock(OrtSession.class);
    OrtSession.Result dropped = mock(OrtSession.Result.class);
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      running.countDown();
      assertTrue(mayReturn.await(30, TimeUnit.SECONDS));
      return dropped;
    });
    NativeSessionHandle handle = gpuHandle(gpu);
    Thread releaser = null;
    try {
      SessionHandle.Lease lease = handle.acquire(queryLeg(DEADLINE));
      long start = System.nanoTime();
      var missed =
          assertThrows(SessionAcquireDeadlineExceededException.class, () -> lease.run(Map.of()));
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertEquals(SessionAcquireDeadlineExceededException.Phase.RUN, missed.phase());
      assertTrue(elapsedMs < DEADLINE.toMillis() + 1_000, "caller held " + elapsedMs + " ms");
      assertTrue(running.await(1, TimeUnit.SECONDS));

      lease.close();
      lease.close();
      assertEquals(0, freeSeats(handle),
          "the caller's close must not release a lease whose run is still in flight");
      var waiting =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () -> handle.acquire(queryLeg(Duration.ofMillis(100))));
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, waiting.phase());

      AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
      releaser = new Thread(() -> {
        try {
          handle.releaseGpu();
        } catch (Throwable t) {
          releaseFailure.set(t);
        }
      }, "detached-run-release-gpu-test");
      releaser.start();
      releaser.join(300);
      assertTrue(releaser.isAlive(), "releaseGpu must wait for the detached run");
      verify(gpu, never()).close();

      mayReturn.countDown();
      releaser.join(5_000);
      assertFalse(releaser.isAlive());
      assertNull(releaseFailure.get());
      var order = inOrder(gpu, dropped);
      order.verify(gpu).run(anyMap());
      order.verify(dropped).close();
      order.verify(gpu).close();
      assertEquals(1, freeSeats(handle), "released exactly once");
      assertEquals(0, detached(handle, "gpuRunThreads"));
    } finally {
      mayReturn.countDown();
      if (releaser != null) releaser.join(5_000);
      handle.close();
    }
  }

  @Test
  void aRunThatReturnsInTimeIsTheCallersOnAPlatformRunThread() throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    OrtSession.Result result = mock(OrtSession.Result.class);
    AtomicReference<Thread> runThread = new AtomicReference<>();
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      runThread.set(Thread.currentThread());
      return result;
    });
    NativeSessionHandle handle = gpuHandle(gpu);
    try {
      try (SessionHandle.Lease lease = handle.acquire(queryLeg(Duration.ofSeconds(5)))) {
        assertSame(result, lease.run(Map.of()));
        assertEquals(0, freeSeats(handle), "the caller still holds its seat");
      }
      assertEquals(1, freeSeats(handle));
      verify(result, never()).close();
      assertNotSame(Thread.currentThread(), runThread.get());
      assertFalse(runThread.get().isVirtual(), "a native call must not pin a virtual carrier");
      assertTrue(runThread.get().getName().startsWith("ort-query-run-"));
    } finally {
      handle.close();
    }
  }

  @Test
  void aCallerWithoutASemanticDeadlineRunsOnItsOwnThreadAsBefore() throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    AtomicReference<Thread> runThread = new AtomicReference<>();
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      runThread.set(Thread.currentThread());
      return mock(OrtSession.Result.class);
    });
    NativeSessionHandle handle = gpuHandle(gpu);
    try (SessionHandle.Lease lease =
        handle.acquire(
            SessionAcquisitionRequest.within(
                SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(5)))) {
      lease.run(Map.of()).close();
      assertSame(Thread.currentThread(), runThread.get());
    } finally {
      handle.close();
    }
  }

  @Test
  void aDetachedRunThatFailsIsStillReleasedOnce() throws Exception {
    CountDownLatch mayFail = new CountDownLatch(1);
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      assertTrue(mayFail.await(30, TimeUnit.SECONDS));
      throw new OrtException("native failure after the caller left");
    });
    NativeSessionHandle handle = gpuHandle(gpu);
    try {
      SessionHandle.Lease lease = handle.acquire(queryLeg(DEADLINE));
      assertThrows(SessionAcquireDeadlineExceededException.class, () -> lease.run(Map.of()));
      lease.close();
      mayFail.countDown();
      awaitPermits(handle, 1);
      lease.close();
      assertEquals(1, freeSeats(handle), "released exactly once");
    } finally {
      mayFail.countDown();
      handle.close();
    }
  }

  @Test
  void detachedRunsFillingTheDeviceFailTheNextRunAtOnce() throws Exception {
    CountDownLatch mayReturn = new CountDownLatch(1);
    OrtSession cpu = mock(OrtSession.class);
    when(cpu.run(anyMap())).thenAnswer(invocation -> {
      assertTrue(mayReturn.await(30, TimeUnit.SECONDS));
      return mock(OrtSession.Result.class);
    });
    NativeSessionHandle handle = gpuHandle(mock(OrtSession.class));
    set(handle, "cpuSession", cpu);
    try {
      for (int i = 0; i < DetachableRun.RUN_THREADS_PER_DEVICE; i++) {
        SessionHandle.Lease lease = handle.acquireCpu(queryLeg(Duration.ofMillis(100)));
        assertThrows(SessionAcquireDeadlineExceededException.class, () -> lease.run(Map.of()));
        lease.close();
      }
      SessionHandle.Lease next = handle.acquireCpu(queryLeg(Duration.ofSeconds(5)));
      long start = System.nanoTime();
      var full = assertThrows(SessionAcquireDeadlineExceededException.class, () -> next.run(Map.of()));
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "it did not fail at once");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.RUN, full.phase());
      next.close();
      verify(cpu, times(DetachableRun.RUN_THREADS_PER_DEVICE)).run(anyMap());

      mayReturn.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (detached(handle, "cpuRunThreads") > 0 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertEquals(0, detached(handle, "cpuRunThreads"));
    } finally {
      mayReturn.countDown();
      handle.close();
    }
  }

  @Test
  void theRunReadsItsOwnCopyOfTheInputsAfterTheCallerClosedThem() throws Exception {
    CountDownLatch mayReturn = new CountDownLatch(1);
    AtomicReference<long[]> seen = new AtomicReference<>();
    AtomicReference<OnnxTensor> runInput = new AtomicReference<>();
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      Map<String, OnnxTensor> inputs = invocation.getArgument(0);
      runInput.set(inputs.get("input_ids"));
      assertTrue(mayReturn.await(30, TimeUnit.SECONDS));
      LongBuffer values = inputs.get("input_ids").getLongBuffer();
      long[] copy = new long[values.remaining()];
      values.get(copy);
      seen.set(copy);
      return mock(OrtSession.Result.class);
    });
    NativeSessionHandle handle = gpuHandle(gpu);
    try {
      long[] ids = {101L, 7592L, 102L};
      SessionHandle.Lease lease = handle.acquire(queryLeg(DEADLINE));
      try (OnnxTensor callerInput =
          OnnxTensor.createTensor(
              OrtEnvironment.getEnvironment(), LongBuffer.wrap(ids), new long[] {1, 3})) {
        assertThrows(
            SessionAcquireDeadlineExceededException.class,
            () -> lease.run(Map.of("input_ids", callerInput)));
        assertNotSame(callerInput, runInput.get(), "the run must not read the caller's tensor");
      }
      lease.close();
      mayReturn.countDown();
      awaitPermits(handle, 1);
      assertArrayEquals(ids, seen.get());
    } finally {
      mayReturn.countDown();
      handle.close();
    }
  }

  /** A query leg under a semantic sub-budget: it asks to be released at its deadline. */
  private static SessionAcquisitionRequest queryLeg(Duration budget) {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + budget.toNanos(),
        () -> false,
        /* mayCreateGpuSession= */ false,
        /* releaseCallerAtDeadline= */ true);
  }

  private static NativeSessionHandle gpuHandle(OrtSession gpuSession) throws Exception {
    Path model = Path.of("missing", "model.onnx");
    NativeSessionHandle handle =
        NativeSessionHandle.builder("detached-run-test", model)
            .gpuModelPath(model)
            .shouldUseGpu(() -> true)
            .runtime(RuntimePolicy.defaults())
            .policy(
                ModelSessionPolicy.forFallback(
                    new GpuSessionConfig(0, 512L * 1024 * 1024),
                    null,
                    /* deferCpuSession= */ true,
                    /* gpuRetryEnabled= */ false,
                    NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS))
            .build();
    set(handle, "gpuSession", gpuSession);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    return handle;
  }

  private static void awaitPermits(NativeSessionHandle handle, int permits) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (freeSeats(handle) != permits && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals(permits, freeSeats(handle));
  }

  /** Detached runs on the device behind the handle's {@code field} (its run-thread accounting). */
  private static int detached(NativeSessionHandle handle, String field) throws Exception {
    Field f = NativeSessionHandle.class.getDeclaredField(field);
    f.setAccessible(true);
    Field count = DetachableRun.Device.class.getDeclaredField("detached");
    count.setAccessible(true);
    return count.getInt(f.get(handle));
  }

  /**
   * Free seats of the handle's device gate, the GPU permit since Spec D §3.2 (it was the
   * handle's semaphore's available permits).
   */
  private static int freeSeats(NativeSessionHandle handle) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField("gpuGate");
    field.setAccessible(true);
    DeviceEncoderGate gate = (DeviceEncoderGate) field.get(handle);
    Field seated = DeviceEncoderGate.class.getDeclaredField("seated");
    seated.setAccessible(true);
    return gate.policy().seats() - seated.getInt(gate);
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}

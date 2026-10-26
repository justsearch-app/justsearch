/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec D tests G1 and K5 (§2.3, §3.2): GPU runs are ordered per device, across the session handles
 * built for it, and a GPU release never waits without a bound for a run that does not return.
 *
 * <p>Both use only the handle's public acquire and release surface, through {@link
 * OrtSessionAssembler#buildManager}, with mocked native sessions. Each test uses a device id of its
 * own, so the gates of other tests' devices are never involved.
 */
final class NativeSessionHandleDeviceGateTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  /**
   * G1 (S4): while a bulk run holds the device, three bulk requests queue, then a query from
   * another handle on the same device. Nothing else runs on the device meanwhile; when the bulk
   * seat is released, the query is seated next, before the bulk requests queued earlier.
   */
  @Test
  void aQueryFromAnotherHandleIsSeatedBeforeQueuedBulkOnTheSameDevice() throws Exception {
    int device = 41;
    NativeSessionHandle bulk = handle("g1-bulk", device, mock(OrtSession.class));
    NativeSessionHandle query = handle("g1-query", device, mock(OrtSession.class));
    Queue<String> seated = new ConcurrentLinkedQueue<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> waiters = new ArrayList<>();
    SessionHandle.Lease held = bulk.acquire(request(SessionAcquisitionRequest.Urgency.BACKGROUND));
    try {
      assertFalse(held.isCpu(), "the bulk run holds the GPU");
      waiters.add(queue("bulk-1", bulk, SessionAcquisitionRequest.Urgency.BACKGROUND, seated, failure));
      awaitQueued(DeviceEncoderGates.forDevice(device), waiters.size());
      waiters.add(queue("bulk-2", query, SessionAcquisitionRequest.Urgency.BACKGROUND, seated, failure));
      awaitQueued(DeviceEncoderGates.forDevice(device), waiters.size());
      waiters.add(queue("bulk-3", bulk, SessionAcquisitionRequest.Urgency.BACKGROUND, seated, failure));
      awaitQueued(DeviceEncoderGates.forDevice(device), waiters.size());
      waiters.add(queue("query", query, SessionAcquisitionRequest.Urgency.FOREGROUND, seated, failure));
      awaitQueued(DeviceEncoderGates.forDevice(device), waiters.size());
      assertEquals(4, DeviceEncoderGates.forDevice(device).waiting(),
          "all four requests reached the shared device gate before release");
      assertEquals(
          List.of(),
          List.copyOf(seated),
          "no other GPU run may be seated on the device while the bulk seat is held");

      held.close();
      held = null;
      for (Thread waiter : waiters) waiter.join(5_000);
      assertEquals(null, failure.get());
      assertEquals(4, seated.size(), "every queued request is seated in turn: " + seated);
      assertEquals("query", seated.peek(), "the query is seated next: " + seated);
    } finally {
      if (held != null) held.close();
      for (Thread waiter : waiters) waiter.interrupt();
      for (Thread waiter : waiters) {
        waiter.join(5_000);
        assertFalse(waiter.isAlive(), "owned waiter terminated: " + waiter.getName());
      }
      bulk.close();
      query.close();
    }
  }

  /**
   * K5 (D2, defect source "no hang"): {@code releaseGpu} while a GPU run never returns comes back
   * within {@code recompositionWaitMs} instead of blocking its thread forever; the session is not
   * closed during the run, and is closed once the run returns; a second handle on the same device
   * still waits behind the stuck run.
   */
  @Test
  void releaseGpuWithAStuckRunReturnsAndTheRunClosesTheSessionLater() throws Exception {
    int device = 42;
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch mayReturn = new CountDownLatch(1);
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      running.countDown();
      assertTrue(mayReturn.await(120, TimeUnit.SECONDS));
      return mock(OrtSession.Result.class);
    });
    NativeSessionHandle handle = handle("k5-stuck", device, gpu);
    NativeSessionHandle second = handle("k5-second", device, mock(OrtSession.class));
    AtomicReference<Throwable> runFailure = new AtomicReference<>();
    Thread run = new Thread(() -> {
      try (SessionHandle.Lease lease =
          handle.acquire(request(SessionAcquisitionRequest.Urgency.BACKGROUND))) {
        assertFalse(lease.isCpu());
        lease.run(Map.of()).close();
      } catch (Throwable thrown) {
        runFailure.set(thrown);
      }
    }, "k5-stuck-run");
    try {
      run.start();
      assertTrue(running.await(5, TimeUnit.SECONDS), "the GPU run started");

      assertTimeoutPreemptively(Duration.ofSeconds(15), handle::releaseGpu,
          "releaseGpu must return within recompositionWaitMs while a run is stuck");
      assertFalse(handle.isGpuAvailable());
      verify(gpu, never()).close();

      var waited =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () -> second.acquire(
                  SessionAcquisitionRequest.within(
                      SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofMillis(200))),
              "the second handle on the device waits behind the stuck run");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, waited.phase());

      mayReturn.countDown();
      run.join(5_000);
      assertFalse(run.isAlive());
      assertEquals(null, runFailure.get());
      verify(gpu, timeout(2_000)).close();
      try (SessionHandle.Lease next =
          second.acquire(request(SessionAcquisitionRequest.Urgency.FOREGROUND))) {
        assertFalse(next.isCpu(), "the device is free once the stuck run returned");
      }
    } finally {
      // Lets the before-state's uninterruptible release finish, so a failing run cannot hang.
      mayReturn.countDown();
      run.join(5_000);
      handle.close();
      second.close();
    }
  }

  private static Thread queue(
      String name,
      NativeSessionHandle handle,
      SessionAcquisitionRequest.Urgency urgency,
      Queue<String> seated,
      AtomicReference<Throwable> failure) {
    Thread waiter = new Thread(() -> {
      try (SessionHandle.Lease lease = handle.acquire(request(urgency))) {
        assertFalse(lease.isCpu(), name + " was seated on the GPU");
        seated.add(name);
      } catch (Throwable thrown) {
        failure.compareAndSet(null, thrown);
      }
    }, "g1-" + name);
    waiter.start();
    return waiter;
  }

  private static void awaitQueued(DeviceEncoderGate gate, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (gate.waiting() != expected && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(expected, gate.waiting(), "every started request is queued before proceeding");
  }

  private static SessionAcquisitionRequest request(SessionAcquisitionRequest.Urgency urgency) {
    return SessionAcquisitionRequest.within(urgency, Duration.ofSeconds(30));
  }

  private static NativeSessionHandle handle(String name, int device, OrtSession gpuSession)
      throws Exception {
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
    set(handle, "gpuSession", gpuSession);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    return handle;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}

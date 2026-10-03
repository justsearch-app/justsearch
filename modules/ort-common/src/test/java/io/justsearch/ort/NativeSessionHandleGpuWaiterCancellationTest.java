/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.onnxruntime.OrtSession;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Focused semaphore-ownership tests using a mocked native ORT session boundary. */
final class NativeSessionHandleGpuWaiterCancellationTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cooperativeCancellationBeforeRunRetainsLeaseUntilOwnerCloses(boolean pinned)
      throws Exception {
    var gpu = mock(OrtSession.class);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    try (var handle = gpuHandle(gpu)) {
      var request = SessionAcquisitionRequest.within(
          SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(2), cancelled::get);
      try (var lease = handle.acquire(request)) {
        cancelled.set(true);
        assertThrows(CancellationException.class, () -> {
          if (pinned) lease.runPinned(java.util.Map.of(), java.util.Map.of());
          else lease.run(java.util.Map.of());
        });
        org.mockito.Mockito.verifyNoInteractions(gpu);
        assertEquals(0, semaphore(handle).availablePermits(),
            "cancellation does not release an issued lease");
      }
      assertEquals(1, semaphore(handle).availablePermits());
    }
  }

  @Test
  void gpuReleaseWaitsForCreationAndClosesPublishedSession() throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    OrtSession cpu = mock(OrtSession.class);
    NativeSessionHandle handle = gpuHandle(null);
    set(handle, "gpuSessionAttempted", false);
    set(handle, "gpuAvailable", false);
    set(handle, "cpuSession", cpu);
    CountDownLatch creating = new CountDownLatch(1);
    CountDownLatch publish = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread creator = new Thread(() -> {
      try (var cuda = mockStatic(OrtCudaHelper.class);
          var _ = mockConstruction(OrtSession.SessionOptions.class);
          var _ = mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
          var _ = mockStatic(SessionOptionsApplier.class);
          var cache = mockStatic(OnnxSessionCache.class)) {
        cuda.when(() -> OrtCudaHelper.checkMissingCudaRuntimeDlls(any()))
            .thenReturn(java.util.List.of());
        cache.when(() -> OnnxSessionCache.createCachedGpuSession(any(), any(), any()))
            .thenAnswer(ignored -> {
              creating.countDown();
              assertTrue(publish.await(2, TimeUnit.SECONDS));
              return gpu;
            });
        try (var lease = handle.acquire(request())) {
          // Release may win the inference semaphore after creation publishes.
          assertTrue(lease.session() == gpu || lease.session() == cpu);
        }
      } catch (Throwable thrown) {
        failure.compareAndSet(null, thrown);
      }
    }, "native-gpu-creator-test");
    Thread releaser = new Thread(() -> {
      try {
        handle.releaseGpu();
      } catch (Throwable thrown) {
        failure.compareAndSet(null, thrown);
      } finally {
        released.countDown();
      }
    }, "native-gpu-release-test");
    try {
      creator.start();
      assertTrue(creating.await(2, TimeUnit.SECONDS), String.valueOf(failure.get()));
      releaser.start();
      awaitOwnerQueued(handle, releaser, released);
      assertEquals(1L, released.getCount(), "handoff cannot finish before native publication");
      verify(gpu, never()).close();
      publish.countDown();
      creator.join(2000);
      releaser.join(2000);
      assertFalse(creator.isAlive());
      assertFalse(releaser.isAlive());
      assertNull(failure.get());
      assertFalse(handle.isGpuAvailable());
      verify(gpu).close();
    } finally {
      publish.countDown();
      creator.join(2000);
      if (releaser.getState() != Thread.State.NEW) releaser.join(2000);
      handle.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void creatorQueuedBeforeCompletedReleaseCannotRecreateGpu(boolean retry) throws Exception {
    NativeSessionHandle handle = gpuHandle(null);
    set(handle, "gpuAvailable", false);
    set(handle, "gpuSessionAttempted", retry);
    if (retry) set(handle, "gpuFailedAtMs", 1L);
    set(handle, "cpuSession", mock(OrtSession.class));
    ReentrantLock owner = ownerLock(handle);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    Thread creator = new Thread(() -> {
      try (var cuda = mockStatic(OrtCudaHelper.class)) {
        try (var lease = handle.acquire(request())) {
          assertTrue(lease.isCpu(), "the acquisition began before the handoff");
        }
        cuda.verifyNoInteractions();
      } catch (Throwable thrown) {
        failure.set(thrown);
      } finally {
        finished.countDown();
      }
    }, "native-gpu-stale-creator-test");
    owner.lock();
    try {
      creator.start();
      awaitOwnerQueued(handle, creator, finished);
      handle.releaseGpu();
    } finally {
      owner.unlock();
    }
    try {
      assertTrue(finished.await(2, TimeUnit.SECONDS));
      assertNull(failure.get());
      assertFalse(handle.isGpuAvailable());
    } finally {
      creator.join(2000);
      handle.close();
    }
  }

  private static ReentrantLock ownerLock(NativeSessionHandle handle) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField("gpuSessionLock");
    field.setAccessible(true);
    return (ReentrantLock) field.get(handle);
  }

  private static void awaitOwnerQueued(NativeSessionHandle handle, Thread thread,
      CountDownLatch finished) throws Exception {
    ReentrantLock lock = ownerLock(handle);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!lock.hasQueuedThread(thread) && finished.getCount() != 0
        && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(lock.hasQueuedThread(thread), "native action did not wait for GPU creation owner");
  }

  @Test
  void interruptedWaiterExitsWithoutReleasingActiveGpuLease() throws Exception {
    OrtSession gpuSession = mock(OrtSession.class);
    NativeSessionHandle handle = gpuHandle(gpuSession);
    SessionHandle.Lease active = handle.acquire(request());
    Thread waiter = null;
    try {
      Semaphore semaphore = semaphore(handle);
      assertEquals(0, semaphore.availablePermits(), "the first lease owns the GPU permit");

      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      waiter =
          new Thread(
              () -> {
                entered.countDown();
                try {
                  handle.acquire(request());
                  failure.set(new AssertionError("interrupted waiter acquired a GPU lease"));
                } catch (Throwable thrown) {
                  failure.set(thrown);
                } finally {
                  finished.countDown();
                }
              },
              "native-gpu-waiter-test");
      waiter.start();
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      awaitQueued(semaphore);

      waiter.interrupt();
      assertTrue(finished.await(1, TimeUnit.SECONDS), "interrupt must release the blocked waiter");
      assertInstanceOf(CancellationException.class, failure.get());
      assertInstanceOf(InterruptedException.class, failure.get().getCause());
      assertEquals(0, semaphore.availablePermits(), "waiter cancellation must not release holder permit");
      verify(gpuSession, never()).close();

      active.close();
      active = null;
      assertEquals(1, semaphore.availablePermits(), "active lease still owns release");
      try (SessionHandle.Lease next = handle.acquire(request())) {
        assertFalse(next.isCpu(), "the next caller can acquire GPU after the holder releases");
      }
    } finally {
      if (waiter != null) waiter.interrupt();
      if (active != null) active.close();
      handle.close();
    }
  }

  @Test
  void gpuWaitHonorsDeadlineWithoutReleasingActiveLease() throws Exception {
    OrtSession gpuSession = mock(OrtSession.class);
    NativeSessionHandle handle = gpuHandle(gpuSession);
    SessionHandle.Lease active = handle.acquire(request());
    try {
      assertThrows(
          SessionAcquireDeadlineExceededException.class,
          () ->
              handle.acquire(
                  SessionAcquisitionRequest.within(
                      SessionAcquisitionRequest.Urgency.BACKGROUND,
                      Duration.ofMillis(25))));
      assertEquals(0, semaphore(handle).availablePermits());
      verify(gpuSession, never()).close();
    } finally {
      active.close();
      handle.close();
    }
  }

  @Test
  void preInterruptedCallerGetsCancellationWithCauseAndNoPermitLeak() throws Exception {
    OrtSession gpuSession = mock(OrtSession.class);
    NativeSessionHandle handle = gpuHandle(gpuSession);
    try {
      Semaphore semaphore = semaphore(handle);
      CountDownLatch finished = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicReference<Boolean> interruptRestored = new AtomicReference<>();
      Thread caller =
          new Thread(
              () -> {
                Thread.currentThread().interrupt();
                try {
                  handle.acquire(request());
                  failure.set(new AssertionError("pre-interrupted caller acquired a GPU lease"));
                } catch (Throwable thrown) {
                  failure.set(thrown);
                  interruptRestored.set(Thread.currentThread().isInterrupted());
                } finally {
                  finished.countDown();
                }
              },
              "native-gpu-pre-interrupted-test");
      caller.start();
      assertTrue(finished.await(1, TimeUnit.SECONDS));
      assertInstanceOf(CancellationException.class, failure.get());
      assertInstanceOf(InterruptedException.class, failure.get().getCause());
      assertTrue(interruptRestored.get(), "the acquire contract restores the interrupt flag");
      assertEquals(1, semaphore.availablePermits(), "pre-interrupted acquire never owned a permit");
    } finally {
      handle.close();
    }
  }

  @Test
  void interruptAfterPermitBeforeLeaseConstructionReleasesExactlyOnce() throws Exception {
    OrtSession gpuSession = mock(OrtSession.class);
    OrtSessionTelemetryEvents interruptingEvents =
        new OrtSessionTelemetryEvents() {
          @Override
          public void onSemaphoreWait(String consumer, long waitUs) {
            Thread.currentThread().interrupt();
          }
        };
    NativeSessionHandle handle = gpuHandle(gpuSession, interruptingEvents);
    try {
      Semaphore semaphore = semaphore(handle);
      CountDownLatch finished = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread caller =
          new Thread(
              () -> {
                try {
                  handle.acquire(request());
                  failure.set(new AssertionError("interrupted caller acquired a GPU lease"));
                } catch (Throwable thrown) {
                  failure.set(thrown);
                } finally {
                  finished.countDown();
                }
              },
              "native-gpu-post-acquire-interrupted-test");
      caller.start();
      assertTrue(finished.await(1, TimeUnit.SECONDS));
      assertInstanceOf(CancellationException.class, failure.get());
      assertEquals(
          1,
          semaphore.availablePermits(),
          "the unissued lease must not retain or duplicate the permit");
      verify(gpuSession, never()).close();
    } finally {
      handle.close();
    }
  }

  @Test
  void heldGpuLeasePreventsExactSessionCloseAndQueuedWaiterIsRefusedAfterRetirement()
      throws Exception {
    OrtSession gpuSession = mock(OrtSession.class);
    NativeSessionHandle handle = gpuHandle(gpuSession);
    SessionHandle.Lease held = handle.acquire(request());
    AtomicReference<Throwable> waiterOutcome = new AtomicReference<>();
    CountDownLatch waiterFinished = new CountDownLatch(1);
    Thread waiter = new Thread(() -> {
      try {
        handle.acquire(request());
        waiterOutcome.set(new AssertionError("queued waiter acquired after retirement"));
      } catch (Throwable thrown) {
        waiterOutcome.set(thrown);
      } finally {
        waiterFinished.countDown();
      }
    }, "native-gpu-retiring-waiter-test");
    waiter.start();
    awaitQueued(semaphore(handle));
    CountDownLatch closeFinished = new CountDownLatch(1);
    Thread closer = new Thread(() -> {
      handle.close();
      closeFinished.countDown();
    }, "native-gpu-retire-test");
    closer.start();
    awaitRetired(handle);

    verify(gpuSession, never()).close();
    held.close();

    assertTrue(waiterFinished.await(1, TimeUnit.SECONDS));
    assertInstanceOf(SessionRetiredException.class, waiterOutcome.get());
    assertTrue(closeFinished.await(1, TimeUnit.SECONDS));
    verify(gpuSession).close();
    assertThrows(SessionRetiredException.class, () -> handle.acquire(request()));
  }

  private static NativeSessionHandle gpuHandle(OrtSession gpuSession) throws Exception {
    return gpuHandle(gpuSession, OrtSessionTelemetryEvents.NOOP);
  }

  private static SessionAcquisitionRequest request() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(2));
  }

  private static NativeSessionHandle gpuHandle(
      OrtSession gpuSession, OrtSessionTelemetryEvents events) throws Exception {
    Path model = Path.of("missing", "model.onnx");
    NativeSessionHandle handle =
        NativeSessionHandle.builder("gpu-waiter-test", model)
            .gpuModelPath(model)
            .shouldUseGpu(() -> true)
            .runtime(RuntimePolicy.defaults())
            .policy(
                ModelSessionPolicy.forFallback(
                    new GpuSessionConfig(0, 512L * 1024 * 1024),
                    null,
                    /* deferCpuSession= */ true,
                    /* gpuRetryEnabled= */ true,
                    NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS))
            .events(events)
            .build();
    set(handle, "gpuSession", gpuSession);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    return handle;
  }

  private static Semaphore semaphore(NativeSessionHandle handle) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField("gpuInferenceSemaphore");
    field.setAccessible(true);
    return (Semaphore) field.get(handle);
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }

  private static void awaitQueued(Semaphore semaphore) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (semaphore.getQueueLength() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(semaphore.getQueueLength() > 0, "waiter did not reach the semaphore queue");
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

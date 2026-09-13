/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.onnxruntime.OrtSession;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Focused semaphore-ownership tests using a mocked native ORT session boundary. */
final class NativeSessionHandleGpuWaiterCancellationTest {

  @Test
  void interruptedWaiterExitsWithoutReleasingActiveGpuLease() throws Exception {
    OrtSession gpuSession = mock(OrtSession.class);
    NativeSessionHandle handle = gpuHandle(gpuSession);
    SessionHandle.Lease active = handle.acquire();
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
                  handle.acquire();
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
      try (SessionHandle.Lease next = handle.acquire()) {
        assertFalse(next.isCpu(), "the next caller can acquire GPU after the holder releases");
      }
    } finally {
      if (waiter != null) waiter.interrupt();
      if (active != null) active.close();
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
                  handle.acquire();
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
                  handle.acquire();
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

  private static NativeSessionHandle gpuHandle(OrtSession gpuSession) throws Exception {
    return gpuHandle(gpuSession, OrtSessionTelemetryEvents.NOOP);
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
}

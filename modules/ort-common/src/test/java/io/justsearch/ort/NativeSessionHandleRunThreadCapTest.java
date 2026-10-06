/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Spec D §1.5 item 4 under concurrency: the cap on native runs that may outlive their caller holds
 * per device however many callers arrive at once. Eight query callers on two handles of one device
 * (the CPU) run a native call that stalls. At most two of those calls are ever started, so at most
 * two runs are left detached, and every caller, started or not, gets its answer by its deadline.
 *
 * <p>The native sessions are mocks whose {@code run} blocks until the test lets it return; the
 * check counts the native calls that were entered.
 */
final class NativeSessionHandleRunThreadCapTest {

  private static final int CALLERS = 8;
  private static final int MAX_RUNS_THAT_MAY_OUTLIVE_THEIR_CALLER = 2;
  private static final Duration DEADLINE = Duration.ofMillis(300);
  private static final long SLACK_MS = 1_000;

  @Test
  void eightConcurrentStalledQueryRunsLeaveAtMostTwoRunsOnTheDevice() throws Exception {
    CountDownLatch mayReturn = new CountDownLatch(1);
    AtomicInteger entered = new AtomicInteger();
    AtomicInteger inNative = new AtomicInteger();
    AtomicInteger maxInNative = new AtomicInteger();
    NativeSessionHandle first = cpuHandle("run-cap-a", stalling(mayReturn, entered, inNative, maxInNative));
    NativeSessionHandle second = cpuHandle("run-cap-b", stalling(mayReturn, entered, inNative, maxInNative));
    List<Thread> callers = new ArrayList<>();
    ConcurrentLinkedQueue<Long> answeredAfterMs = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Throwable> outcomes = new ConcurrentLinkedQueue<>();
    CountDownLatch go = new CountDownLatch(1);
    try {
      for (int i = 0; i < CALLERS; i++) {
        NativeSessionHandle handle = i % 2 == 0 ? first : second;
        Thread caller = Thread.ofPlatform().name("run-cap-caller-" + i).start(() -> {
          try {
            assertTrue(go.await(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            SessionHandle.Lease lease = handle.acquireCpu(queryLeg(DEADLINE));
            try {
              lease.run(Map.of()).close();
              outcomes.add(new AssertionError("a stalled run returned a result"));
            } catch (Throwable failure) {
              outcomes.add(failure);
            } finally {
              answeredAfterMs.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
              lease.close();
            }
          } catch (Throwable t) {
            outcomes.add(t);
          }
        });
        callers.add(caller);
      }
      go.countDown();
      for (Thread caller : callers) caller.join(DEADLINE.toMillis() + 10_000);

      assertEquals(CALLERS, answeredAfterMs.size(), "every caller answered: " + outcomes);
      for (long ms : answeredAfterMs) {
        assertTrue(ms <= DEADLINE.toMillis() + SLACK_MS,
            "a caller answered after " + ms + " ms, past its " + DEADLINE.toMillis() + " ms deadline");
      }
      for (Throwable outcome : outcomes) {
        assertTrue(outcome instanceof SessionAcquireDeadlineExceededException,
            "a stalled or never-started run answers its caller with a deadline miss: " + outcome);
      }
      assertTrue(entered.get() <= MAX_RUNS_THAT_MAY_OUTLIVE_THEIR_CALLER,
          entered.get() + " stalled native runs were started for " + CALLERS
              + " concurrent callers; the device allows at most "
              + MAX_RUNS_THAT_MAY_OUTLIVE_THEIR_CALLER + " runs that may outlive their caller");
      assertTrue(maxInNative.get() <= MAX_RUNS_THAT_MAY_OUTLIVE_THEIR_CALLER,
          maxInNative.get() + " native runs were in flight at once on one device");
    } finally {
      mayReturn.countDown();
      for (Thread caller : callers) caller.join(10_000);
      awaitQuiet(inNative, first);
      first.close();
      second.close();
    }
  }

  private static OrtSession stalling(
      CountDownLatch mayReturn, AtomicInteger entered, AtomicInteger inNative,
      AtomicInteger maxInNative) throws Exception {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> {
      entered.incrementAndGet();
      maxInNative.accumulateAndGet(inNative.incrementAndGet(), Math::max);
      try {
        assertTrue(mayReturn.await(30, TimeUnit.SECONDS));
        return mock(OrtSession.Result.class);
      } finally {
        inNative.decrementAndGet();
      }
    });
    return session;
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

  /** A CPU-only handle whose CPU session is {@code cpuSession}. */
  private static NativeSessionHandle cpuHandle(String name, OrtSession cpuSession) throws Exception {
    Path model = Path.of("missing", "model.onnx");
    NativeSessionHandle handle =
        NativeSessionHandle.builder(name, model)
            .runtime(RuntimePolicy.defaults())
            .policy(
                ModelSessionPolicy.forFallback(
                    null,
                    null,
                    /* deferCpuSession= */ true,
                    /* gpuRetryEnabled= */ false,
                    NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS))
            .build();
    Field field = NativeSessionHandle.class.getDeclaredField("cpuSession");
    field.setAccessible(true);
    field.set(handle, cpuSession);
    return handle;
  }

  /**
   * Waits until every stalled run has returned and its run thread has given back the device, so
   * the next test on this device starts from an empty device.
   */
  private static void awaitQuiet(AtomicInteger inNative, NativeSessionHandle handle)
      throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until && (inNative.get() > 0 || runThreadsInUse(handle) > 0)) {
      Thread.sleep(5);
    }
  }

  /** Run threads in use on the handle's CPU device, or 0 where the handle has no such count. */
  private static int runThreadsInUse(NativeSessionHandle handle) throws Exception {
    Field device;
    try {
      device = NativeSessionHandle.class.getDeclaredField("cpuRunThreads");
    } catch (NoSuchFieldException absent) {
      return 0;
    }
    device.setAccessible(true);
    Object counts = device.get(handle);
    int inUse = 0;
    for (String name : new String[] {"live", "detached"}) {
      Field count = counts.getClass().getDeclaredField(name);
      count.setAccessible(true);
      inUse += count.getInt(counts);
    }
    return inUse;
  }
}

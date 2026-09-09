/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Urgency is explicit; balance on errors and concurrent use still pins ADR-0048's pacing feed. */
final class ForegroundLoadGateTest {
  private static EngineContext context(EngineContext.Survival survival, EngineContext.Urgency urgency) {
    return new EngineContext(EngineContext.ClientKind.INTERNAL, "gate-test", Optional.empty(),
        Optional.empty(), "UNTRUSTED", "HTTP", survival, urgency);
  }

  @Test
  void urgencyAloneCountsAcrossBothSurvivalAxesAndBothForms() {
    var admission = new EngineAdmissionController(10, 10, 1);
    for (var survival : EngineContext.Survival.values()) {
      for (var urgency : EngineContext.Urgency.values()) {
        var load = new ForegroundLoad();
        var gate = new ForegroundLoadGate(load);
        int expected = urgency == EngineContext.Urgency.FOREGROUND ? 1 : 0;
        try (var work = admission.admit(context(survival, urgency), false)) {
          assertEquals(expected, gate.call(work, load::inFlight));
          gate.run(work, () -> assertEquals(expected, load.inFlight()));
          assertEquals(survival == EngineContext.Survival.DURABLE ? expected : 0, load.inFlight());
        }
        assertEquals(0, load.inFlight());
        assertEquals(expected * (survival == EngineContext.Survival.DURABLE ? 1 : 2), load.startedTotal());
        if (expected == 0) assertEquals(0, load.lastForegroundAtMs());
      }
    }
  }

  @Test
  void normalExceptionCancellationAndErrorAlwaysBalanceInteractiveWork() {
    var admission = new EngineAdmissionController(10, 10, 1);
    var load = new ForegroundLoad();
    var gate = new ForegroundLoadGate(load);
    try (var work = admission.admit(context(EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND), false)) {
      assertEquals("ok", gate.call(work, () -> "ok"));
      assertThrows(IllegalStateException.class, () -> gate.call(work, () -> {
        throw new IllegalStateException("failure");
      }));
      assertEquals(0, load.inFlight());
      assertThrows(IllegalStateException.class, () -> gate.run(work, () -> {
        throw new IllegalStateException("failure");
      }));
      assertEquals(0, load.inFlight());
      assertThrows(CancellationException.class, () -> gate.call(work, () -> {
        throw new CancellationException("cancelled");
      }));
      assertEquals(0, load.inFlight());
      assertThrows(StackOverflowError.class, () -> gate.call(work, () -> {
        throw new StackOverflowError("deep");
      }));
      assertEquals(0, load.inFlight());
      assertEquals(5, load.startedTotal());
    }
  }

  @Test
  void durableWorkHoldsExactlyOnceUntilDetachOrLastOwnerCompletes() {
    var admission = new EngineAdmissionController(10, 10, 1);
    var load = new ForegroundLoad();
    var gate = new ForegroundLoadGate(load);
    for (boolean detach : new boolean[] {false, true}) {
      var front = admission.admit(context(EngineContext.Survival.DURABLE,
          EngineContext.Urgency.FOREGROUND), false);
      var worker = front.retain();
      gate.run(worker, () -> assertEquals(1, load.inFlight()));
      gate.run(worker, () -> assertEquals(1, load.inFlight()));
      front.close();
      assertEquals(1, load.inFlight(), "the asynchronous owner still holds work");
      if (detach) {
        worker.waitingClientGone();
        worker.waitingClientGone();
        assertEquals(0, load.inFlight());
        assertEquals(EngineContext.Urgency.BACKGROUND, worker.context().urgency());
        gate.run(worker, () -> assertEquals(0, load.inFlight()));
      }
      worker.close();
      worker.close();
      assertEquals(0, load.inFlight());
    }
    assertEquals(2, load.startedTotal());
  }

  @Test
  void nestedWrapFailsWithoutUnbalancingOrPoisoningTheNextCall() {
    var admission = new EngineAdmissionController(10, 10, 1);
    var load = new ForegroundLoad();
    var gate = new ForegroundLoadGate(load);
    try (var work = admission.admit(context(EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND), false)) {
      assertThrows(IllegalStateException.class, () -> gate.run(work, () -> gate.run(work, () -> {})));
      assertEquals(0, load.inFlight());
      assertEquals(1, load.startedTotal());
      gate.run(work, () -> assertEquals(1, load.inFlight()));
      assertEquals(0, load.inFlight());
      assertEquals(2, load.startedTotal());
    }
  }

  @Test
  void concurrentUseNeverGoesNegativeAndCountsEveryExecutingCall() throws InterruptedException {
    var admission = new EngineAdmissionController(10, 10, 1);
    var load = new ForegroundLoad();
    var gate = new ForegroundLoadGate(load);
    int threads = 8;
    int callsPerThread = 200;
    var negativeReadings = new AtomicInteger();
    var zeroWhileInFlight = new AtomicInteger();
    var failures = new AtomicInteger();
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    var pool = Executors.newFixedThreadPool(threads);
    try (var work = admission.admit(context(EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND), false)) {
      for (int t = 0; t < threads; t++) {
        final int offset = t;
        pool.execute(() -> {
          try {
            start.await();
            for (int i = 0; i < callsPerThread; i++) {
              boolean fail = (offset + i) % 3 == 0;
              try {
                gate.run(work, () -> {
                  int seen = load.inFlight();
                  if (seen < 0) negativeReadings.incrementAndGet();
                  if (seen == 0) zeroWhileInFlight.incrementAndGet();
                  if (fail) throw new CancellationException("cancelled");
                });
              } catch (CancellationException expected) {
                // Deliberately failing work must balance exactly like successful work.
              }
              if (load.inFlight() < 0) negativeReadings.incrementAndGet();
            }
          } catch (Throwable failure) {
            failures.incrementAndGet();
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS), "workers must finish");
    } finally {
      pool.shutdownNow();
    }
    assertEquals(0, failures.get());
    assertEquals(0, negativeReadings.get());
    assertEquals(0, zeroWhileInFlight.get());
    assertEquals(0, load.inFlight());
    assertEquals((long) threads * callsPerThread, load.startedTotal());
  }
}

package io.justsearch.app.services.observability.health;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 876 §B.2a. */
@DisplayName("ReadinessReconciliationTrigger")
final class ReadinessReconciliationTriggerTest {

  private ReadinessReconciliationTrigger trigger;
  private TestEngineExecutors processExecutors;

  @BeforeEach
  void setUp() {
    processExecutors = new TestEngineExecutors();
    trigger = new ReadinessReconciliationTrigger(processExecutors);
  }

  @AfterEach
  void tearDown() {
    trigger.close();
    processExecutors.close();
  }

  @Test
  @DisplayName("attach self-seeds: the thunk runs once with no capability transition at all")
  void attachSelfSeeds() throws InterruptedException {
    CountDownLatch ran = new CountDownLatch(1);
    trigger.attach(ran::countDown);
    assertTrue(ran.await(5, SECONDS), "attach() must request an immediate reconcile");
  }

  @Test
  @DisplayName("request before attach is a no-op, and does not consume the later self-seed")
  void requestBeforeAttachIsNoOp() throws InterruptedException {
    trigger.request();
    trigger.request();
    CountDownLatch ran = new CountDownLatch(1);
    trigger.attach(ran::countDown);
    assertTrue(ran.await(5, SECONDS), "a pre-attach request must not latch the coalescing flag");
  }

  @Test
  @DisplayName("a worker capability transition after attach runs the thunk")
  void workerTransitionRunsThunk() throws InterruptedException {
    WorkerCapability worker = new WorkerCapability();
    trigger.wireTo(worker, null);

    CountDownLatch seeded = new CountDownLatch(1);
    CountDownLatch seedPlusTransition = new CountDownLatch(2);
    trigger.attach(
        () -> {
          seeded.countDown();
          seedPlusTransition.countDown();
        });

    // Wait for the self-seed to complete before transitioning, so the two reconciles cannot
    // legitimately coalesce into one.
    assertTrue(seeded.await(5, SECONDS), "self-seed did not run");

    worker.transition(CapabilityHealth.READY, "worker.ready");

    assertTrue(
        seedPlusTransition.await(5, SECONDS),
        "a worker transition must trigger a reconcile with no /api/status call");
  }

  @Test
  @DisplayName("an inference capability transition after attach runs the thunk")
  void inferenceTransitionRunsThunk() throws InterruptedException {
    InferenceCapability inference = new InferenceCapability(true);
    trigger.wireTo(null, inference);

    CountDownLatch seeded = new CountDownLatch(1);
    CountDownLatch seedPlusTransition = new CountDownLatch(2);
    trigger.attach(
        () -> {
          seeded.countDown();
          seedPlusTransition.countDown();
        });
    assertTrue(seeded.await(5, SECONDS), "self-seed did not run");

    inference.transition(CapabilityHealth.READY, "inference.ready");

    assertTrue(seedPlusTransition.await(5, SECONDS), "an inference transition must reconcile");
  }

  @Test
  @DisplayName("wireTo tolerates null capabilities")
  void wireToToleratesNulls() throws InterruptedException {
    trigger.wireTo(null, null);
    CountDownLatch ran = new CountDownLatch(1);
    trigger.attach(ran::countDown);
    assertTrue(ran.await(5, SECONDS));
  }

  @Test
  @DisplayName("a burst of requests coalesces into far fewer reconciles than requests")
  void burstCoalesces() throws InterruptedException {
    int burst = 200;
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch settled = new CountDownLatch(2);

    trigger.attach(
        () -> {
          runs.incrementAndGet();
          firstStarted.countDown();
          try {
            // Hold the single reconcile thread so the whole burst arrives while one is in flight.
            release.await(5, SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          settled.countDown();
        });

    assertTrue(firstStarted.await(5, SECONDS), "self-seed reconcile did not start");
    for (int i = 0; i < burst; i++) {
      trigger.request();
    }
    release.countDown();

    assertTrue(settled.await(5, SECONDS), "the coalesced follow-up reconcile did not run");
    int observed = runs.get();
    assertTrue(observed >= 1, "at least one reconcile must run; observed " + observed);
    assertTrue(
        observed < burst,
        "a burst of " + burst + " requests must coalesce; observed " + observed + " reconciles");
  }

  @Test
  @DisplayName("a throwing thunk neither propagates into a capability transition nor wedges it")
  void throwingThunkDoesNotWedge() throws InterruptedException {
    WorkerCapability worker = new WorkerCapability();
    trigger.wireTo(worker, null);

    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch firstAttempt = new CountDownLatch(1);
    CountDownLatch secondAttempt = new CountDownLatch(2);
    trigger.attach(
        () -> {
          attempts.incrementAndGet();
          firstAttempt.countDown();
          secondAttempt.countDown();
          throw new IllegalStateException("reconcile blew up");
        });

    assertTrue(firstAttempt.await(5, SECONDS), "self-seed did not run");

    // The transition must return normally even though the thunk it triggers throws, and the
    // trigger must still be live afterwards (the coalescing flag was released before the throw).
    worker.transition(CapabilityHealth.READY, "worker.ready");

    assertTrue(
        secondAttempt.await(5, SECONDS),
        "a throwing thunk must not wedge the trigger; attempts=" + attempts.get());
  }

  @Test
  @DisplayName("close() is idempotent and stops further reconciles, including from a transition")
  void closeIsIdempotentAndStopsReconciles() throws InterruptedException {
    WorkerCapability worker = new WorkerCapability();
    trigger.wireTo(worker, null);

    AtomicInteger runs = new AtomicInteger();
    CountDownLatch seeded = new CountDownLatch(1);
    CountDownLatch anySecondRun = new CountDownLatch(2);
    trigger.attach(
        () -> {
          runs.incrementAndGet();
          seeded.countDown();
          anySecondRun.countDown();
        });
    assertTrue(seeded.await(5, SECONDS));

    trigger.close();
    trigger.close();

    int afterClose = runs.get();
    trigger.request();
    trigger.request();
    // The listener is still registered; the trigger itself must swallow the request.
    worker.transition(CapabilityHealth.READY, "worker.ready");

    assertFalse(
        anySecondRun.await(300, MILLISECONDS),
        "no reconcile may run after close(), from request() or a transition");
    assertEquals(afterClose, runs.get(), "no reconcile may run after close()");
  }
}

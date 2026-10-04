package io.justsearch.app.services.observability.health;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
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
  private TestEngineComponents components;

  @BeforeEach
  void setUp() {
    components = TestEngineComponents.fourComponents();
    processExecutors = new TestEngineExecutors();
    trigger = new ReadinessReconciliationTrigger(processExecutors);
  }

  @AfterEach
  void tearDown() {
    trigger.close();
    processExecutors.close();
    components.close();
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
    var worker = components.handle("index");
    trigger.wireTo(components);

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

    worker.transition(ComponentState.READY, null, null);

    assertTrue(
        seedPlusTransition.await(5, SECONDS),
        "a worker transition must trigger a reconcile with no /api/status call");
  }

  @Test
  @DisplayName("an inference capability transition after attach runs the thunk")
  void inferenceTransitionRunsThunk() throws InterruptedException {
    var inference = components.handle("generative");
    trigger.wireTo(components);

    CountDownLatch seeded = new CountDownLatch(1);
    CountDownLatch seedPlusTransition = new CountDownLatch(2);
    trigger.attach(
        () -> {
          seeded.countDown();
          seedPlusTransition.countDown();
        });
    assertTrue(seeded.await(5, SECONDS), "self-seed did not run");

    inference.transition(ComponentState.READY, null, null);

    assertTrue(seedPlusTransition.await(5, SECONDS), "an inference transition must reconcile");
  }

  @Test
  @DisplayName("subscribing before any observations still permits the self-seed")
  void unobservedRegistryStillPermitsSelfSeed() throws InterruptedException {
    trigger.wireTo(components);
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
    var worker = components.handle("index");
    trigger.wireTo(components);

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
    worker.transition(ComponentState.READY, null, null);

    assertTrue(
        secondAttempt.await(5, SECONDS),
        "a throwing thunk must not wedge the trigger; attempts=" + attempts.get());
  }

  @Test
  @DisplayName("close() is idempotent and stops further reconciles, including from a transition")
  void closeIsIdempotentAndStopsReconciles() throws InterruptedException {
    var worker = components.handle("index");
    trigger.wireTo(components);

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
    // The owned registry subscription is closed; direct requests also remain harmless.
    worker.transition(ComponentState.READY, null, null);

    assertFalse(
        anySecondRun.await(300, MILLISECONDS),
        "no reconcile may run after close(), from request() or a transition");
    assertEquals(afterClose, runs.get(), "no reconcile may run after close()");
  }
}

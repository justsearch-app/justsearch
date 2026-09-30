package io.justsearch.app.services.observability.health;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.ReadinessDimension;
import io.justsearch.app.api.status.ReadinessComponentView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 876 §B.2a — the trigger's own mechanics, over a stand-in envelope supplier.
 *
 * <p><b>Scope, precisely.</b> What this file proves is that a {@link WorkerCapability} transition
 * drives {@link LifecycleSnapshotTap#accept} through a {@link ReadinessReconciliationTrigger}, on a
 * graph that contains no request handler: the trigger's subscription, its {@code attach} self-seed,
 * and the resulting assert/clear of {@code index.unavailable} in the {@link ConditionStore}. The
 * envelope, however, comes from a test-owned {@link AtomicReference} that stands in for {@code
 * StatusLifecycleHandler.buildReadinessEnvelope}. So "no {@code /api/status} call happened" is true
 * here <em>by fixture construction</em> — there is no {@code StatusLifecycleHandler} in this graph
 * to call.
 *
 * <p><b>What binds the production thunk.</b> {@code CoreApiAssembly}'s {@code
 * readinessTrigger.attach(statusLifecycleHandler::buildStatusSnapshot)} is asserted by {@code
 * modules/ui}'s {@code ReadinessTriggerCompositionTest}, which wires this same trigger to a real
 * {@code StatusLifecycleHandler} + real tap and observes the same clear. Read the two together: this
 * file is the trigger's contract, that one is the composition.
 *
 * <p><b>This test fails on {@code main}.</b> There, {@link LifecycleSnapshotTap#accept} — the only
 * writer that asserts or clears {@code index.unavailable} — has exactly one production caller,
 * {@code StatusLifecycleHandler.buildStatusMap()}, i.e. {@code GET /api/status}. Nothing subscribes
 * a reconcile to a capability transition, so a worker coming back READY cannot clear the condition;
 * only the next status request can.
 */
@DisplayName("The reconciliation trigger drives the tap off a capability transition")
final class ReadinessReconciledWithoutRequestTest {

  private static final Instant T0 = Instant.parse("2026-08-26T09:00:00Z");
  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");

  private static ReadinessEnvelopeView indexServing(String state, String reasonCode) {
    return new ReadinessEnvelopeView(
        1,
        T0.toString(),
        Map.of(
            ReadinessDimension.INDEX_SERVING.key(),
            new ReadinessComponentView(state, reasonCode, "test", T0.toString(), false, 0L)),
        Map.of());
  }

  @Test
  @DisplayName(
      "a worker READY transition clears index.unavailable via the trigger's thunk"
          + " (stand-in envelope supplier; the real handler is bound in modules/ui's"
          + " ReadinessTriggerCompositionTest)")
  void workerReadyTransitionClearsIndexUnavailableWithoutStatusRequest()
      throws InterruptedException {
    ConditionStore conditions = new ConditionStore();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry();
    LifecycleSnapshotTap tap =
        new LifecycleSnapshotTap(conditions, changes, HEAD_SRC, Clock.systemUTC());

    // Stands in for StatusLifecycleHandler.buildReadinessEnvelope: the one authority for the
    // envelope. The trigger re-runs the SAME computation; it never computes a second one.
    AtomicReference<ReadinessEnvelopeView> envelope =
        new AtomicReference<>(indexServing("NOT_READY", "worker.starting"));

    AtomicInteger reconciles = new AtomicInteger();
    CountDownLatch seeded = new CountDownLatch(1);
    CountDownLatch seedPlusTransition = new CountDownLatch(2);

    WorkerCapability worker = new WorkerCapability();
    try (TestEngineExecutors processExecutors = new TestEngineExecutors();
        ReadinessReconciliationTrigger trigger =
            new ReadinessReconciliationTrigger(processExecutors)) {
      trigger.wireTo(worker, null);
      trigger.attach(
          () -> {
            reconciles.incrementAndGet();
            tap.accept(envelope.get());
            seeded.countDown();
            seedPlusTransition.countDown();
          });

      assertTrue(seeded.await(5, SECONDS), "the attach self-seed did not reconcile");
      assertTrue(
          conditions.find("index.unavailable", "worker").isPresent(),
          "a NOT_READY/worker.starting envelope must assert index.unavailable");

      // The worker comes back. Nothing calls /api/status — there is no handler in this graph.
      envelope.set(indexServing("READY", null));
      worker.transition(CapabilityHealth.READY, "worker.ready");

      assertTrue(
          seedPlusTransition.await(5, SECONDS),
          "the capability transition did not drive a reconcile");
      assertEquals(2, reconciles.get(), "expected exactly the self-seed plus the transition");
      assertTrue(
          conditions.find("index.unavailable", "worker").isEmpty(),
          "index.unavailable must be cleared by the transition, not by the next status request");
    }
  }
}

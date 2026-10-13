package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.atrest.AtRestProtection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 629 (FLOOR) — the at-rest-protection legibility tap. The {@code at-rest.unprotected}
 * condition is asserted iff the data-dir volume is known-unencrypted, and cleared for ENCRYPTED /
 * ENCRYPTING / UNKNOWN ("unknown ≠ unhealthy" — a can't-determine state must not raise a warning).
 */
final class AtRestHealthTapTest {

  private static final Source HEAD = Source.forProcess("head", "i", "1.0");

  private AtRestHealthTap tap(ConditionStore store) {
    return new AtRestHealthTap(
        store,
        new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()),
        HEAD,
        Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC),
        AtRestProtection::unknown); // unused — tests drive reconcile(...) directly
  }

  @Test
  void notEncryptedAssertsCondition() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(AtRestProtection.State.NOT_ENCRYPTED);

    List<HealthEvent> snap = store.currentSnapshot();
    assertEquals(1, snap.size(), "an unencrypted volume must assert one condition");
    HealthEvent e = snap.get(0);
    assertEquals("at-rest.unprotected", e.id());
    AssertedCondition c = (AssertedCondition) e.body();
    assertEquals(ConditionStatus.TRUE, c.status(), "presence of the condition = the unprotected state");
    assertEquals("data-dir", c.subject());
    assertTrue(c.message().isPresent(), "must carry honest guidance copy");
  }

  @Test
  void encryptedAssertsNothing() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(AtRestProtection.State.ENCRYPTED);
    assertTrue(store.currentSnapshot().isEmpty(), "an encrypted volume is healthy = condition absent");
  }

  @Test
  void encryptingAssertsNothing() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(AtRestProtection.State.ENCRYPTING);
    assertTrue(store.currentSnapshot().isEmpty(), "in-progress encryption is not the unprotected state");
  }

  @Test
  void unknownAssertsNothing() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(AtRestProtection.State.UNKNOWN);
    assertTrue(
        store.currentSnapshot().isEmpty(),
        "unknown ≠ unhealthy: a can't-determine state must not raise a warning");
  }

  @Test
  void becomingEncryptedClearsTheCondition() {
    ConditionStore store = new ConditionStore();
    AtRestHealthTap t = tap(store);
    t.reconcile(AtRestProtection.State.NOT_ENCRYPTED);
    assertEquals(1, store.currentSnapshot().size(), "precondition: condition asserted");
    t.reconcile(AtRestProtection.State.ENCRYPTED);
    assertTrue(
        store.currentSnapshot().isEmpty(), "encrypting the volume must clear the unprotected condition");
  }
}

package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 629 (#2, retuned) — the LAYER legibility tap. The {@code at-rest.authored} condition is asserted
 * iff the AUTHORED stores are genuinely unprotected (app-encryption not configured AND disk not
 * OS-encrypted), and cleared otherwise — in particular it clears the moment app-encryption is configured,
 * even with FDE off. A merely-locked store raises nothing here.
 */
final class ConversationProtectionHealthTapTest {

  private static final Source HEAD = Source.forProcess("head", "i", "1.0");

  private ConversationProtectionHealthTap tap(ConditionStore store) {
    return new ConversationProtectionHealthTap(
        store,
        new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()),
        HEAD,
        Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneOffset.UTC),
        () -> false); // unused — tests drive reconcile(...) directly
  }

  @Test
  void unprotectedAssertsCondition() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(true);

    List<HealthEvent> snap = store.currentSnapshot();
    assertEquals(1, snap.size(), "unprotected authored data must assert one condition");
    HealthEvent e = snap.get(0);
    assertEquals("at-rest.authored", e.id());
    AssertedCondition c = (AssertedCondition) e.body();
    assertEquals(ConditionStatus.TRUE, c.status());
    assertEquals("conversations", c.subject());
    assertTrue(c.message().isPresent(), "must carry honest two-remedy guidance");
  }

  @Test
  void protectedAssertsNothing() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(false);
    assertTrue(
        store.currentSnapshot().isEmpty(),
        "protected authored data (app-encrypted OR FDE on, or merely locked) raises no condition");
  }

  @Test
  void becomingProtectedClearsTheCondition() {
    ConditionStore store = new ConditionStore();
    ConversationProtectionHealthTap t = tap(store);
    t.reconcile(true);
    assertEquals(1, store.currentSnapshot().size(), "precondition: condition asserted while unprotected");
    t.reconcile(false);
    assertTrue(
        store.currentSnapshot().isEmpty(),
        "configuring encryption (or enabling FDE) must clear the at-rest.authored condition");
  }
}

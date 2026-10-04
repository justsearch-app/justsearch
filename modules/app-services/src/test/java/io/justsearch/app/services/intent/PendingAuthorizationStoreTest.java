package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 550 C3/WA-5: the PendingAuthorization registry is the hardening mechanism — a
 * capsule can only be minted against a backend-created, single-use, unexpired pending.
 * These tests pin the three properties the security argument rests on: create→consume
 * round-trips the stored (op, args); consume is single-use; and expired/unknown ids fail
 * closed.
 */
class PendingAuthorizationStoreTest {

  private static String create(PendingAuthorizationStore store) {
    return store.create(
        "core.bulk-reindex",
        "{\"corpusIds\":[\"default\"]}",
        SourceTier.UNTRUSTED,
        RiskTier.HIGH,
        GateBehavior.TYPED_CONFIRM,
        "LLM requested a destructive reindex",
        io.justsearch.app.services.TestEngineContexts.agent(),
        io.justsearch.agent.api.registry.InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.agent(),
            io.justsearch.agent.api.registry.ExecutorTag.AGENT,
            Instant.now(), Optional.empty()));
  }

  @Test
  void createThenConsumeRoundTripsTheStoredOpAndArgs() {
    PendingAuthorizationStore store = new PendingAuthorizationStore();
    String id = create(store);

    Optional<PendingAuthorization> consumed = store.consume(id);
    assertTrue(consumed.isPresent(), "a freshly created pending is consumable");
    PendingAuthorization p = consumed.get();
    assertEquals("core.bulk-reindex", p.operationId());
    assertEquals("{\"corpusIds\":[\"default\"]}", p.argsJson(), "the capsule binds to the STORED args");
    assertEquals(SourceTier.UNTRUSTED, p.sourceTier());
    assertEquals(GateBehavior.TYPED_CONFIRM, p.gateBehavior());
  }

  @Test
  void consumeIsSingleUse() {
    PendingAuthorizationStore store = new PendingAuthorizationStore();
    String id = create(store);

    assertTrue(store.consume(id).isPresent(), "first consume succeeds");
    assertTrue(
        store.consume(id).isEmpty(),
        "a consumed pending cannot be replayed into a second approval");
  }

  @Test
  void unknownIdFailsClosed() {
    PendingAuthorizationStore store = new PendingAuthorizationStore();
    assertTrue(store.consume("pa-does-not-exist").isEmpty());
    assertTrue(store.consume(null).isEmpty());
    assertTrue(store.peek("pa-nope").isEmpty());
  }

  @Test
  void expiredPendingIsNotConsumable() {
    // A clock we advance past the TTL between create and consume.
    Instant t0 = Instant.parse("2026-05-26T00:00:00Z");
    MutableClock clock = new MutableClock(t0);
    PendingAuthorizationStore store = new PendingAuthorizationStore(clock, Duration.ofMinutes(5));
    String id = create(store);

    clock.set(t0.plus(Duration.ofMinutes(5))); // exactly at expiry — already stale
    assertTrue(store.peek(id).isEmpty(), "a pending at/after its expiry is not peekable");
    assertTrue(store.consume(id).isEmpty(), "a stale approval fails closed");
  }

  @Test
  void peekDoesNotConsume() {
    PendingAuthorizationStore store = new PendingAuthorizationStore();
    String id = create(store);
    assertTrue(store.peek(id).isPresent());
    assertEquals(1, store.size(), "peek leaves the entry in place");
    assertTrue(store.consume(id).isPresent(), "still consumable after a peek");
    assertFalse(store.peek(id).isPresent(), "gone after consume");
  }

  @Test
  void createEvictsExpiredEntries() {
    // The leak guard: expired pendings that are never peeked/consumed (the user ignored the
    // 428) are swept on the next create, so the map stays bounded to the live set.
    Instant t0 = Instant.parse("2026-05-26T00:00:00Z");
    MutableClock clock = new MutableClock(t0);
    PendingAuthorizationStore store = new PendingAuthorizationStore(clock, Duration.ofMinutes(5));
    for (int i = 0; i < 5; i++) {
      create(store); // five gated-then-abandoned pendings at t0
    }
    assertEquals(5, store.size());

    clock.set(t0.plus(Duration.ofMinutes(6))); // all five now expired
    create(store); // the create sweeps the expired five before inserting

    assertEquals(1, store.size(), "expired pendings are evicted on create — no unbounded growth");
  }

  /** Minimal mutable Clock for deterministic expiry testing. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void set(Instant t) {
      this.now = t;
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}

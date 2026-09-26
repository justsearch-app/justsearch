package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import io.justsearch.agent.api.registry.ConsentCapsuleAuthority;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.observability.ledger.ActionEvent;
import org.junit.jupiter.api.Test;

/** Security-critical unit coverage for {@link ConsentCapsuleService} (tempdoc 550 A1). */
final class ConsentCapsuleServiceTest {

  private static final String OP = "core.restart-worker";
  private static final String ARGS = "{\"force\":true}";

  private static ConsentCapsuleService svc() {
    return new ConsentCapsuleService(Clock.systemUTC(), Duration.ofMinutes(5));
  }

  @Test
  void validCapsuleVerifiesForBoundActionAndArgs() {
    ConsentCapsuleService svc = svc();
    String capsule = svc.mint(OP, ARGS);
    assertTrue(svc.verifyAndConsume(capsule, OP, ARGS), "freshly minted capsule verifies");
  }

  @Test
  void capsuleIsSingleUse() {
    ConsentCapsuleService svc = svc();
    String capsule = svc.mint(OP, ARGS);
    assertTrue(svc.verifyAndConsume(capsule, OP, ARGS));
    assertFalse(svc.verifyAndConsume(capsule, OP, ARGS), "second use is rejected (single-use)");
  }

  @Test
  void capsuleIsBoundToOperationId() {
    ConsentCapsuleService svc = svc();
    String capsule = svc.mint(OP, ARGS);
    assertFalse(
        svc.verifyAndConsume(capsule, "core.delete-everything", ARGS),
        "capsule for one op does not authorize another");
  }

  @Test
  void revokeNonUserFailsClosedAndIsRecorded() {
    // Tempdoc 550 thesis IV / F3 — revokeNonUser (the hard-stop's revoke) cancels a pending
    // non-user grant before it is consumed; verification then fails closed. (2-arg mint defaults
    // to UNTRUSTED, so the grant is non-user.)
    List<ActionEvent> events =
        new java.util.ArrayList<>();
    ConsentCapsuleService svc = svc();
    svc.setGrantEventSink(events::add);
    String capsule = svc.mint(OP, ARGS);
    svc.revokeNonUser();
    assertFalse(svc.verifyAndConsume(capsule, OP, ARGS), "a revoked non-user capsule fails closed");
    boolean revoked =
        events.stream()
            .anyMatch(
                e ->
                    e instanceof ActionEvent.Grant g
                        && "REVOKED".equals(g.action()));
    assertTrue(revoked, "REVOKED is recorded in the one action-event log");
  }

  @Test
  void revokeNonUserLeavesUserGrantsIntactButRevokesUntrusted() {
    // Tempdoc 550 F3 + independent-review SHOULD-FIX: revokeNonUser matches the gate's hard-stop
    // scope EXACTLY (UNTRUSTED only). A user's TRUSTED approval AND a user-mediated MEDIUM
    // (url/clipboard) approval both survive; only the UNTRUSTED (agent/MCP/plugin) grant is revoked.
    ConsentCapsuleService svc = svc();
    String trusted = svc.mint(OP, ARGS, io.justsearch.agent.api.registry.SourceTier.TRUSTED);
    String medium = svc.mint("core.other", ARGS, io.justsearch.agent.api.registry.SourceTier.MEDIUM);
    String untrusted =
        svc.mint("core.agent", ARGS, io.justsearch.agent.api.registry.SourceTier.UNTRUSTED);

    svc.revokeNonUser();

    assertTrue(svc.verifyAndConsume(trusted, OP, ARGS), "TRUSTED user grant survives");
    assertTrue(svc.verifyAndConsume(medium, "core.other", ARGS), "MEDIUM user-mediated grant survives");
    assertFalse(
        svc.verifyAndConsume(untrusted, "core.agent", ARGS),
        "UNTRUSTED (non-user) grant is revoked — matches the gate's hard-stop scope");
  }

  @Test
  void grantLifecycleIssuedAndConsumedAreRecorded() {
    // Tempdoc 550 thesis IV — one audit. The capsule's lifecycle is recorded as Grant ActionEvents.
    List<ActionEvent> events =
        new java.util.ArrayList<>();
    ConsentCapsuleService svc = svc();
    svc.setGrantEventSink(events::add);
    String capsule = svc.mint(OP, ARGS);
    assertTrue(svc.verifyAndConsume(capsule, OP, ARGS));
    List<String> actions =
        events.stream()
            .filter(e -> e instanceof ActionEvent.Grant)
            .map(e -> ((ActionEvent.Grant) e).action())
            .toList();
    assertTrue(actions.contains("ISSUED"), "mint records ISSUED");
    assertTrue(actions.contains("CONSUMED"), "verify records CONSUMED");
  }

  @Test
  void deferredConsumptionSpendsBeforePublishingAndPublishesOnceAtConsumptionTime() {
    Instant t0 = Instant.parse("2026-09-20T00:00:00Z");
    MutableClock clock = new MutableClock(t0);
    List<ActionEvent> events = new java.util.ArrayList<>();
    ConsentCapsuleService svc = new ConsentCapsuleService(clock, Duration.ofMinutes(5));
    svc.setGrantEventSink(events::add);
    String capsule = svc.mint(OP, ARGS);
    events.clear();

    clock.advance(Duration.ofMinutes(1));
    var pendingAudit = svc.consumeDeferred(capsule, OP, ARGS);
    assertTrue(pendingAudit.isPresent(), "valid capsule is atomically consumed");
    assertTrue(events.isEmpty(), "deferred consumption publishes no event before acceptance");
    assertFalse(svc.verifyAndConsume(capsule, OP, ARGS), "capsule is spent before publication");
    assertTrue(events.isEmpty(), "a rejected second use publishes nothing");

    clock.advance(Duration.ofMinutes(1));
    var consumption = pendingAudit.orElseThrow();
    consumption.publish();
    consumption.publish();

    assertEquals(1, events.size(), "repeated publication is idempotent");
    assertTrue(events.get(0) instanceof ActionEvent.Grant);
    ActionEvent.Grant event = (ActionEvent.Grant) events.get(0);
    assertEquals("CONSUMED", event.action());
    assertEquals(OP, event.subject());
    assertEquals(t0.plus(Duration.ofMinutes(1)), event.occurredAt(),
        "event retains the time the capsule was consumed");
  }

  @Test
  void deferredConsumptionAllowsOnlyOneConcurrentSpend() throws Exception {
    List<ActionEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    ConsentCapsuleService svc = svc();
    svc.setGrantEventSink(events::add);
    String capsule = svc.mint(OP, ARGS);
    events.clear();

    var executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var first = executor.submit(() -> {
        start.await();
        return svc.consumeDeferred(capsule, OP, ARGS);
      });
      var second = executor.submit(() -> {
        start.await();
        return svc.consumeDeferred(capsule, OP, ARGS);
      });
      start.countDown();
      var firstConsumption = first.get();
      var secondConsumption = second.get();

      assertEquals(1, List.of(firstConsumption, secondConsumption).stream().filter(java.util.Optional::isPresent).count(),
          "atomic removal permits exactly one concurrent winner");
      assertTrue(events.isEmpty(), "the winning consumption remains unpublished");
      firstConsumption.or(() -> secondConsumption).orElseThrow().publish();
      assertEquals(1, events.size());
      assertTrue(events.get(0) instanceof ActionEvent.Grant grant && "CONSUMED".equals(grant.action()));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void preparedAndPublicDeferredConsentRemainSeparateBindings() {
    ConsentCapsuleService svc = svc();
    String key = OperationKeys.generate(Clock.fixed(
        Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));
    UUID nonce = UUID.randomUUID();

    String publicCapsule = svc.mint(OP, ARGS);
    assertTrue(svc.consumePreparedDeferred(publicCapsule, OP, ARGS, key, nonce).isEmpty(),
        "ordinary public consent cannot authorize a frozen preparation");
    svc.consumeDeferred(publicCapsule, OP, ARGS).orElseThrow().publish();

    String preparedCapsule = svc.mintPrepared(OP, ARGS,
        io.justsearch.agent.api.registry.SourceTier.TRUSTED, key, nonce);
    assertTrue(svc.consumeDeferred(preparedCapsule, OP, ARGS).isEmpty(),
        "prepared consent cannot authorize public arguments");
    assertTrue(svc.consumePreparedDeferred(preparedCapsule, OP, ARGS, key, UUID.randomUUID()).isEmpty(),
        "prepared consent is bound to its preparation nonce");
    assertTrue(svc.consumePreparedDeferred(preparedCapsule, OP, ARGS, key + "x", nonce).isEmpty(),
        "prepared consent is bound to its operation key");
    svc.consumePreparedDeferred(preparedCapsule, OP, ARGS, key, nonce).orElseThrow().publish();
    assertFalse(svc.verifyPreparedAndConsume(preparedCapsule, OP, ARGS, key, nonce),
        "successful deferred prepared consumption is single-use");
  }

  @Test
  void deferredConsumptionRefusesNullAndMalformedInputsAndUnsupportedAuthoritiesFailClosed() {
    ConsentCapsuleService svc = svc();
    String publicCapsule = svc.mint(OP, ARGS);
    assertTrue(svc.consumeDeferred(null, OP, ARGS).isEmpty());
    assertTrue(svc.consumeDeferred("malformed", OP, ARGS).isEmpty());
    assertTrue(svc.consumeDeferred(publicCapsule, null, ARGS).isEmpty());
    assertTrue(svc.consumeDeferred(publicCapsule, OP, null).isEmpty());
    var publicConsumption = svc.consumeDeferred(publicCapsule, OP, ARGS);
    assertTrue(publicConsumption.isPresent(),
        "invalid attempts leave the valid capsule available");
    publicConsumption.orElseThrow().publish();

    String key = OperationKeys.generate(Clock.systemUTC());
    UUID nonce = UUID.randomUUID();
    String preparedCapsule = svc.mintPrepared(OP, ARGS,
        io.justsearch.agent.api.registry.SourceTier.TRUSTED, key, nonce);
    assertTrue(svc.consumePreparedDeferred(null, OP, ARGS, key, nonce).isEmpty());
    assertTrue(svc.consumePreparedDeferred(preparedCapsule, null, ARGS, key, nonce).isEmpty());
    assertTrue(svc.consumePreparedDeferred(preparedCapsule, OP, null, key, nonce).isEmpty());
    assertTrue(svc.consumePreparedDeferred(preparedCapsule, OP, ARGS, null, nonce).isEmpty());
    assertTrue(svc.consumePreparedDeferred(preparedCapsule, OP, ARGS, key, null).isEmpty());
    assertTrue(svc.consumePreparedDeferred(preparedCapsule, OP, ARGS, "malformed-key", nonce).isEmpty());
    svc.consumePreparedDeferred(preparedCapsule, OP, ARGS, key, nonce).orElseThrow().publish();

    AtomicInteger synchronousChecks = new AtomicInteger();
    ConsentCapsuleAuthority unsupported = new ConsentCapsuleAuthority() {
      @Override
      public String mint(String operationId, String argumentsJson,
          io.justsearch.agent.api.registry.SourceTier sourceTier) {
        return "unused";
      }

      @Override
      public boolean verifyAndConsume(String token, String operationId, String argumentsJson) {
        synchronousChecks.incrementAndGet();
        return true;
      }
    };
    assertTrue(unsupported.consumeDeferred("token", OP, ARGS).isEmpty());
    assertTrue(unsupported.consumePreparedDeferred("token", OP, ARGS, key, nonce).isEmpty());
    assertEquals(0, synchronousChecks.get(), "default deferred methods never fall back to synchronous checks");
  }

  @Test
  void capsuleIsBoundToArguments() {
    ConsentCapsuleService svc = svc();
    String capsule = svc.mint(OP, ARGS);
    assertFalse(
        svc.verifyAndConsume(capsule, OP, "{\"force\":false}"),
        "capsule bound to exact args rejects a different payload");
  }

  @Test
  void tamperedSignatureIsRejected() {
    ConsentCapsuleService svc = svc();
    String capsule = svc.mint(OP, ARGS);
    // Flip the FIRST char of the MAC segment (after the '.'), not the last. The MAC is base64url
    // WITHOUT padding, so its last char encodes only the final 4 significant bits — flipping it
    // (e.g. 'A'→'B', differing only in a don't-care LSB) can decode to the SAME bytes, leaving the
    // MAC unchanged (a ~1/N flake). The first MAC char always encodes a full byte, so flipping it
    // deterministically changes the decoded MAC.
    int dot = capsule.indexOf('.');
    char macFirst = capsule.charAt(dot + 1);
    String tampered =
        capsule.substring(0, dot + 1) + (macFirst == 'A' ? 'B' : 'A') + capsule.substring(dot + 2);
    assertFalse(svc.verifyAndConsume(tampered, OP, ARGS), "tampered MAC is rejected");
  }

  @Test
  void capsuleFromAnotherSessionKeyIsRejected() {
    // A capsule minted by a different service instance (different per-process key) must
    // not verify here — proves unforgeability without the key.
    String foreign = svc().mint(OP, ARGS);
    assertFalse(svc().verifyAndConsume(foreign, OP, ARGS), "cross-key capsule is rejected");
  }

  @Test
  void expiredCapsuleIsRejected() {
    Instant t0 = Instant.parse("2026-05-25T00:00:00Z");
    MutableClock clock = new MutableClock(t0);
    ConsentCapsuleService svc = new ConsentCapsuleService(clock, Duration.ofMinutes(5));
    String capsule = svc.mint(OP, ARGS);
    clock.advance(Duration.ofMinutes(6));
    assertFalse(svc.verifyAndConsume(capsule, OP, ARGS), "capsule past TTL is rejected");
  }

  @Test
  void malformedTokensAreRejectedNotThrown() {
    ConsentCapsuleService svc = svc();
    assertFalse(svc.verifyAndConsume(null, OP, ARGS));
    assertFalse(svc.verifyAndConsume("", OP, ARGS));
    assertFalse(svc.verifyAndConsume("no-dot-segment", OP, ARGS));
    assertFalse(svc.verifyAndConsume(".", OP, ARGS));
    assertFalse(svc.verifyAndConsume("!!!.@@@", OP, ARGS), "non-base64 segments");
  }

  @Test
  void distinctMintsAreDistinctTokens() {
    ConsentCapsuleService svc = svc();
    assertNotEquals(svc.mint(OP, ARGS), svc.mint(OP, ARGS), "nonce makes each capsule unique");
  }

  @Test
  void argsBindingIsKeyOrderIndependent() {
    // The capsule binds to canonicalized args, so the same logical payload serialized with a
    // different key order still verifies (the mint-side and verify-side args come from two
    // separately-parsed HTTP bodies — order must not matter).
    ConsentCapsuleService svc = svc();
    String capsule = svc.mint(OP, "{\"a\":1,\"b\":2}");
    assertTrue(
        svc.verifyAndConsume(capsule, OP, "{\"b\":2,\"a\":1}"),
        "reordered-key args verify against the capsule");
  }

  @Test
  void mintEvictsExpiredGrants() {
    // The leak guard (F2): minted-but-never-verified grants are swept on the next mint, so the
    // live-grant map stays bounded. Since the subject is folded INTO the live-grant value (no
    // parallel grantSubjects map), bounding the live set bounds the subjects too — no leak.
    Instant t0 = Instant.parse("2026-05-26T00:00:00Z");
    MutableClock clock = new MutableClock(t0);
    ConsentCapsuleService svc = new ConsentCapsuleService(clock, Duration.ofMinutes(5));
    for (int i = 0; i < 3; i++) {
      svc.mint(OP, ARGS); // three minted-then-abandoned capsules at t0
    }
    assertTrue(svc.liveGrantCount() == 3, "three live grants before expiry");

    clock.advance(Duration.ofMinutes(6)); // all three now expired
    svc.mint(OP, ARGS); // the mint sweeps the expired three before inserting

    assertTrue(svc.liveGrantCount() == 1, "expired grants evicted on mint — no unbounded growth");
  }

  /** Minimal mutable clock for expiry tests. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
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

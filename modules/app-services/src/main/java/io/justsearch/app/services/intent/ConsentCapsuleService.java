/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.justsearch.app.api.operations.CanonicalOperationArguments;

/**
 * Mints and verifies <b>consent capsules</b> — tempdoc 550 Slice A1 (Authorize face), the
 * keystone primitive (decision D2 / review-package C2).
 *
 * <p>A consent capsule is a bound, single-use, expiring proof that a user explicitly
 * approved <i>one specific action</i>. It replaces the substrate's nominal confirmation
 * token (any non-blank string satisfied the trust lattice — {@code
 * OperationExecutorImpl} pre-A1), which the 550 diagnosis flagged: an LLM (or any
 * UNTRUSTED source) could fabricate a placeholder string. A capsule cannot be fabricated
 * without the per-session key, and it is cryptographically bound to {@code operationId} +
 * a hash of the exact {@code argsJson}, so it cannot be replayed against a different
 * action or different arguments.
 *
 * <p><b>Threat model (loopback-local, single user — D2).</b> The app binds 127.0.0.1
 * only; the adversary of concern is prompt-injection steering the agent, not a network
 * attacker. A symmetric per-process HMAC key is therefore proportional: capsules are
 * unforgeable without the key, single-use (consumed nonce), and short-lived. Full
 * asymmetric / Sigstore signing is deferred until a remote ingress exists. The key is
 * regenerated per process, so capsules do not survive a restart — acceptable, since a
 * pending approval that outlives the process should be re-confirmed.
 *
 * <p><b>FLAGGED for review (wide migration, not landed here):</b> A1 makes the lattice
 * <i>also accept</i> a valid capsule, additively, alongside the legacy non-blank-token
 * path that {@code ActionButton} and the agent-loop still use. Removing the legacy path
 * (forcing every caller onto capsules) is the irreversible cross-cutting cutover and is
 * flagged in 550 §Review-package C2, not done here. Open design point also flagged:
 * whether the approve endpoint that mints capsules needs a stronger guarantee that the
 * caller is a real user gesture and not the agent self-approving (see 550).
 *
 * <p>Wire form of a capsule token: {@code base64url(payload) + "." + base64url(hmac)}
 * where {@code payload = operationId + "|" + sha256Hex(argsJson) + "|" + nonce + "|" +
 * expiryEpochMillis}. Opaque to callers; it rides in the existing {@code
 * ShellAddress.Invocation.confirmationToken} field (no new wire field).
 */
public final class ConsentCapsuleService
    implements io.justsearch.agent.api.registry.ConsentCapsuleAuthority {

  private static final String HMAC_ALGO = "HmacSHA256";
  private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

  private final byte[] sessionKey;
  private final Clock clock;
  private final Duration ttl;

  /**
   * Live (issued, unconsumed) grants: grantId -> metadata. Presence = issued-and-unconsumed; any
   * removal (consume / expiry / sweep / revoke) drops the WHOLE entry, so there is no parallel map
   * to leak (tempdoc 550 critical-analysis F2 — folds the old grantSubjects map in here).
   */
  private final ConcurrentHashMap<String, GrantMeta> liveGrants = new ConcurrentHashMap<>();

  // Tempdoc 550 thesis IV — one revocation path. A revoked grant id fails verification even before
  // expiry / single-use consumption (the explicit revoke trail beyond time-decay + consumption).
  private final java.util.Set<String> revoked = ConcurrentHashMap.newKeySet();

  /**
   * Metadata carried alongside a live grant id: the subject for a REVOKED audit event, and the
   * {@link io.justsearch.agent.api.registry.SourceTier} of the action the grant authorizes — so
   * revocation can be scoped to non-user grants (tempdoc 550 critical-analysis F3).
   */
  private record GrantMeta(
      Instant expiry, String subject, io.justsearch.agent.api.registry.SourceTier sourceTier) {}

  // Tempdoc 550 thesis IV — one audit: grant lifecycle (ISSUED / CONSUMED / REVOKED) recorded into
  // the one action-event log. Nullable: unset → no audit emission (legacy/test paths).
  private volatile java.util.function.Consumer<io.justsearch.app.observability.ledger.ActionEvent>
      grantEventSink;

  /** Wire the action-event log sink that records grant lifecycle (tempdoc 550 thesis IV). */
  public void setGrantEventSink(
      java.util.function.Consumer<io.justsearch.app.observability.ledger.ActionEvent> sink) {
    this.grantEventSink = sink;
  }

  public ConsentCapsuleService() {
    this(Clock.systemUTC(), DEFAULT_TTL);
  }

  public ConsentCapsuleService(Clock clock, Duration ttl) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    this.sessionKey = key;
  }

  /**
   * Mints a single-use capsule approving {@code operationId} with exactly {@code argsJson},
   * recording the {@code sourceTier} of the authorized action so revocation can be scoped to
   * non-user grants (tempdoc 550 F3). Called from the approve path on an explicit user gesture.
   */
  @Override
  public String mint(
      String operationId, String argsJson, io.justsearch.agent.api.registry.SourceTier sourceTier) {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(argsJson, "argsJson");
    Objects.requireNonNull(sourceTier, "sourceTier");
    String grantId = UUID.randomUUID().toString();
    Instant now = clock.instant();
    Instant expiry = now.plus(ttl);
    // Tempdoc 550 thesis IV: the capsule IS a Grant — single-use, BoundAction scope (operation +
    // args hash), short TTL — the maximally-attenuated grant. The token is this Grant's MAC-signed
    // serialization; mint/verify route through the one Grant primitive.
    Grant capsule =
        new Grant(
            grantId,
            new Grant.BoundAction(operationId, CanonicalOperationArguments.digest(argsJson)),
            expiry,
            true);
    // Evict expired ids here — an id is otherwise removed only when its capsule is verified (or
    // found expired during verify), so a minted-but-never-verified capsule would leak its id
    // forever. Sweeping on mint bounds liveGrants to the unexpired set (drops subject + expiry
    // together — no leak).
    liveGrants.values().removeIf(meta -> now.isAfter(meta.expiry()));
    liveGrants.put(grantId, new GrantMeta(expiry, operationId, sourceTier));
    String payload = encode(capsule);
    String mac = base64(hmac(payload.getBytes(StandardCharsets.UTF_8)));
    emitGrant(grantId, "ISSUED", operationId, now);
    return base64(payload.getBytes(StandardCharsets.UTF_8)) + "." + mac;
  }

  /**
   * Revoke a single grant by id (tempdoc 550 thesis IV — the one revocation path). A revoked grant
   * id fails {@link #verifyAndConsume} thereafter; the revocation is recorded in the action-event
   * log. Idempotent.
   */
  public void revoke(String grantId) {
    if (grantId == null) {
      return;
    }
    revoked.add(grantId);
    GrantMeta meta = liveGrants.remove(grantId);
    emitGrant(grantId, "REVOKED", meta != null ? meta.subject() : grantId, clock.instant());
  }

  /**
   * Revoke every currently-live grant the Global Hard Stop would deny — the consumer the hard stop
   * drives when engaged (tempdoc 550 thesis IV). Scoped to {@code UNTRUSTED} grants to MATCH the
   * gate exactly: {@code IntentGateEvaluator} hard-stops only {@code UNTRUSTED} dispatch (agent /
   * MCP / plugin / emission), so revocation targets the same set (independent-review F3 SHOULD-FIX:
   * MEDIUM url/clipboard is user-mediated and is neither gate-hard-stopped nor revoked). A user's
   * own TRUSTED/MEDIUM pending approval survives an emergency stop.
   */
  public void revokeNonUser() {
    for (var entry : new java.util.ArrayList<>(liveGrants.entrySet())) {
      if (entry.getValue().sourceTier() == io.justsearch.agent.api.registry.SourceTier.UNTRUSTED) {
        revoke(entry.getKey());
      }
    }
  }

  private void emitGrant(String grantId, String action, String subject, Instant when) {
    var sink = this.grantEventSink;
    if (sink == null) {
      return;
    }
    try {
      sink.accept(
          new io.justsearch.app.observability.ledger.ActionEvent.Grant(
              "grant:" + action + ":" + grantId,
              when,
              "user",
              "APPROVAL",
              grantId,
              action,
              subject));
    } catch (RuntimeException ignored) {
      // Audit emission is best-effort and must NEVER alter mint/verify/revoke semantics
      // (fail-closed discipline, same as the gate-outcome emit).
    }
  }

  /**
   * Verifies {@code token} is a valid, unexpired, unconsumed capsule bound to exactly
   * {@code operationId} + {@code argsJson}. On success the capsule is <b>consumed</b>
   * (single-use) and {@code true} is returned. Never throws on malformed input — returns
   * {@code false}.
   */
  @Override
  public boolean verifyAndConsume(String token, String operationId, String argsJson) {
    if (token == null || operationId == null || argsJson == null) {
      return false;
    }
    int dot = token.indexOf('.');
    if (dot <= 0 || dot == token.length() - 1) {
      return false;
    }
    final String payload;
    final byte[] presentedMac;
    try {
      payload = new String(unbase64(token.substring(0, dot)), StandardCharsets.UTF_8);
      presentedMac = unbase64(token.substring(dot + 1));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
    // Signature first (constant-time) — reject forgeries before reading fields.
    byte[] expectedMac = hmac(payload.getBytes(StandardCharsets.UTF_8));
    if (!MessageDigest.isEqual(expectedMac, presentedMac)) {
      return false;
    }
    String[] parts = payload.split("\\|", -1);
    if (parts.length != 4) {
      return false;
    }
    long expiryMillis;
    try {
      expiryMillis = Long.parseLong(parts[3]);
    } catch (NumberFormatException e) {
      return false;
    }
    // Reconstruct the Grant this token encodes and validate it through the one Grant primitive
    // (tempdoc 550 thesis IV): scope binding, then expiry, then single-use consumption.
    Grant capsule =
        new Grant(
            parts[2], new Grant.BoundAction(parts[0], parts[1]), Instant.ofEpochMilli(expiryMillis), true);
    // Binding: the scope must authorize this exact action + arguments (canonicalized, so key
    // order / whitespace differences between mint-side and verify-side serializations of the same
    // logical args do not break the match).
    if (!capsule.scope().authorizes(operationId, CanonicalOperationArguments.digest(argsJson))) {
      return false;
    }
    // Revocation (tempdoc 550 thesis IV): a revoked grant id fails closed, before expiry/consume.
    if (revoked.contains(capsule.grantId())) {
      liveGrants.remove(capsule.grantId());
      return false;
    }
    // Expiry.
    if (capsule.isExpired(clock.instant())) {
      liveGrants.remove(capsule.grantId());
      return false;
    }
    // Single-use: consume the grant id iff still live. remove() is atomic, so concurrent
    // double-spend resolves to exactly one winner.
    boolean consumed = liveGrants.remove(capsule.grantId()) != null;
    if (consumed) {
      emitGrant(capsule.grantId(), "CONSUMED", operationId, clock.instant());
    }
    return consumed;
  }

  /** Test/diagnostic: count of issued-and-unconsumed grants (does not prune). */
  int liveGrantCount() {
    return liveGrants.size();
  }

  /**
   * Serialize a single-use {@link Grant.BoundAction} grant (the capsule) to the wire payload
   * {@code operationId|argsHash|grantId|expiryMillis}. The format is unchanged from the pre-Grant
   * capsule, so existing minted tokens round-trip; the token stays opaque to callers.
   */
  private static String encode(Grant grant) {
    Grant.BoundAction bound = (Grant.BoundAction) grant.scope();
    return bound.operationId()
        + "|"
        + bound.argsHash()
        + "|"
        + grant.grantId()
        + "|"
        + grant.expiry().toEpochMilli();
  }

  private byte[] hmac(byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGO);
      mac.init(new SecretKeySpec(sessionKey, HMAC_ALGO));
      return mac.doFinal(data);
    } catch (Exception e) {
      // HmacSHA256 is a JRE-guaranteed algorithm; failure is non-recoverable.
      throw new IllegalStateException("HMAC computation failed", e);
    }
  }

  private static String base64(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static byte[] unbase64(String s) {
    return Base64.getUrlDecoder().decode(s);
  }
}

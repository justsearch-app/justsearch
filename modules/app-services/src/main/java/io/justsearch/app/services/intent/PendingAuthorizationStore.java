/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of {@link PendingAuthorization}s — the backend side of the C3
 * unified Authorize ceremony (tempdoc 550) and the mechanism that hardens Tier-0 (WA-5).
 *
 * <p>A gate path that refuses a dispatch {@link #create creates} a pending entry and
 * surfaces its id. The approval endpoint {@link #consume consumes} the id (single-use) to
 * mint a capsule bound to the entry's stored {@code (operationId, argsJson)}. Because the
 * id is server-assigned and the entry can only be created by a real backend gate, an
 * in-process agent cannot fabricate an approvable pending — closing the "approve an
 * un-gated op" hole the bare-{@code (op,args)} mint endpoint had.
 *
 * <p>Entries are single-use (consume removes) and expire after {@link #ttl}; stale or
 * unknown ids resolve to {@link Optional#empty()} so a late or replayed approval fails
 * closed. Process-local and per-process — consistent with the consent-capsule HMAC key,
 * which is also per-process (a pending minted here is only meaningful to this process).
 */
public final class PendingAuthorizationStore {

  /** Default lifetime of a pending authorization (matches the consent-capsule expiry window). */
  public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

  /**
   * Defensive upper bound on live entries. Expiry sweeping (on every {@link #create}) is the
   * real eviction; this cap only guards against a pathological flood of unexpired-and-never-
   * approved pendings. Far above any plausible count of concurrently-pending approvals.
   */
  private static final int MAX_ENTRIES = 512;

  private final ConcurrentHashMap<String, PendingAuthorization> pendings = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration ttl;

  public PendingAuthorizationStore() {
    this(Clock.systemUTC(), DEFAULT_TTL);
  }

  public PendingAuthorizationStore(Clock clock, Duration ttl) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  /**
   * Record that a dispatch was gated and is awaiting human authorization. Returns the
   * opaque pending id the approval gesture will reference.
   */
  public String create(
      String operationId,
      String argsJson,
      SourceTier sourceTier,
      RiskTier riskTier,
      GateBehavior gateBehavior,
      String rationale, EngineContext engineContext, io.justsearch.agent.api.registry.InvocationProvenance provenance) {
    return create(operationId, argsJson, sourceTier, riskTier, gateBehavior, rationale, null, engineContext, provenance);
  }

  /**
   * Tempdoc 655 — records {@code requestedBy} (the calling MCP client's self-reported name,
   * display-only — see {@link PendingAuthorization#requestedBy}); {@code null} for a
   * browser-originated gate. Defaults {@code transport} to {@link TransportTag#SYSTEM_INTERNAL}
   * ("no caller-supplied transport context") for callers that don't yet supply one.
   */
  public String create(
      String operationId,
      String argsJson,
      SourceTier sourceTier,
      RiskTier riskTier,
      GateBehavior gateBehavior,
      String rationale,
      String requestedBy, EngineContext engineContext, io.justsearch.agent.api.registry.InvocationProvenance provenance) {
    return create(
        operationId,
        argsJson,
        sourceTier,
        riskTier,
        gateBehavior,
        rationale,
        requestedBy,
        TransportTag.valueOf(engineContext.transport()), engineContext, provenance);
  }

  /**
   * Tempdoc 655 critical-analysis fix — canonical overload: also records {@code transport} (which
   * transport's gate produced this pending — see {@link PendingAuthorization#transport}), so a
   * consumer like {@code PendingAuthorizationAdvisoryProjector} can distinguish a gate with no
   * in-page synchronous responder (MCP) from one the caller's own request is already driving a
   * ceremony dialog for (a browser 428).
   */
  public String create(
      String operationId,
      String argsJson,
      SourceTier sourceTier,
      RiskTier riskTier,
      GateBehavior gateBehavior,
      String rationale,
      String requestedBy,
      TransportTag transport, EngineContext engineContext, io.justsearch.agent.api.registry.InvocationProvenance provenance) {
    Instant now = clock.instant();
    // Evict expired entries here — expiry is otherwise only checked lazily on peek/consume of
    // a specific id, so a pending that is gated-then-abandoned (never approved) would never be
    // reclaimed. Sweeping on create bounds the map to the live (unexpired) set.
    pendings.values().removeIf(p -> p.isExpired(now));
    if (pendings.size() >= MAX_ENTRIES) {
      // Pathological: more than MAX_ENTRIES unexpired pendings. Drop the oldest to bound
      // memory (a stale approval the user never actioned is the least valuable to keep).
      pendings.entrySet().stream()
          .min(java.util.Map.Entry.comparingByValue(
              java.util.Comparator.comparing(PendingAuthorization::createdAt)))
          .ifPresent(oldest -> pendings.remove(oldest.getKey(), oldest.getValue()));
    }
    String id = "pa-" + UUID.randomUUID().toString().replace("-", "");
    pendings.put(
        id,
        new PendingAuthorization(
            id,
            operationId,
            argsJson,
            sourceTier,
            riskTier,
            gateBehavior,
            rationale,
            now,
            now.plus(ttl),
            requestedBy,
            transport, engineContext, provenance));
    return id;
  }

  /** Look up a pending without consuming it. Expired/unknown ids return empty. */
  public Optional<PendingAuthorization> peek(String id) {
    if (id == null) {
      return Optional.empty();
    }
    PendingAuthorization pending = pendings.get(id);
    if (pending == null) {
      return Optional.empty();
    }
    if (pending.isExpired(clock.instant())) {
      pendings.remove(id, pending);
      return Optional.empty();
    }
    return Optional.of(pending);
  }

  /**
   * Atomically remove and return a non-expired pending by id — the single-use approval
   * step. Returns empty for unknown, already-consumed, or expired ids (fail closed).
   */
  public Optional<PendingAuthorization> consume(String id) {
    if (id == null) {
      return Optional.empty();
    }
    PendingAuthorization pending = pendings.remove(id);
    if (pending == null || pending.isExpired(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(pending);
  }

  /** Test/diagnostic: current live entry count (does not prune). */
  public int size() {
    return pendings.size();
  }
}

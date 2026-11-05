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
  // Recent terminal decisions are process-local and bounded to 512, like pending ids.
  // After restart or eviction the key must pass the gate and receive a fresh answer.
  private final java.util.Map<String, Decision> decisions = new java.util.LinkedHashMap<>();
  private final java.util.Set<String> rejectedIds = new java.util.LinkedHashSet<>();
  private java.util.function.BiConsumer<PendingAuthorization,
      io.justsearch.app.observability.operations.AuthorizationDisposition> changeListener = (p, d) -> {};

  /** Attach the shared ledger and withdrawal channel before admitting requests. */
  public synchronized void setLifecycleSinks(
      io.justsearch.app.observability.operations.AuthorizationOutcomeStore outcomes,
      io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry changes) {
    changeListener = (pending, disposition) -> {
      outcomes.append(new io.justsearch.app.observability.operations.AuthorizationOutcomeEntry(
          pending.operationId(), pending.transport(), pending.sourceTier(), pending.riskTier(),
          pending.gateBehavior(), disposition, clock.instant(), pending.id()));
      if (disposition != io.justsearch.app.observability.operations.AuthorizationDisposition.GATED) {
        changes.broadcast(new io.justsearch.app.observability.operations.PendingAuthorizationEvent(
            pending.id(), pending.operationId(), pending.sourceTier(), pending.riskTier(),
            pending.gateBehavior(), pending.createdAt(), pending.expiresAt(), pending.transport(), disposition.name()));
      }
    };
  }

  private record Decision(
      io.justsearch.app.observability.operations.AuthorizationDisposition disposition, Instant decidedAt) {}

  /** Recent process-local decisions, addressed by the agent's original operation key. */
  public synchronized Optional<Instant> rejectedAt(String operationKey) {
    if (operationKey == null) return Optional.empty();
    return Optional.ofNullable(decisions.get(operationKey))
        .filter(row -> row.disposition() == io.justsearch.app.observability.operations.AuthorizationDisposition.DENIED)
        .map(Decision::decidedAt);
  }

  /** Distinguish a remembered answer from an expired or unknown pending lookup. */
  public synchronized boolean hasDecisionForKey(String operationKey) {
    return operationKey != null && decisions.containsKey(operationKey);
  }

  public synchronized boolean wasRejected(String id) {
    return rejectedIds.contains(id);
  }

  /** A live authorization is not an accepted execution receipt. */
  public synchronized Optional<PendingAuthorization> pendingForKey(String operationKey) {
    if (operationKey == null) return Optional.empty();
    return java.util.List.copyOf(pendings.values()).stream()
        .filter(row -> operationKey.equals(row.operationKey()))
        .map(row -> peek(row.id())).flatMap(Optional::stream).findFirst();
  }

  private void publish(PendingAuthorization pending,
      io.justsearch.app.observability.operations.AuthorizationDisposition disposition) {
    try {
      changeListener.accept(pending, disposition);
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(PendingAuthorizationStore.class)
          .warn("Pending authorization lifecycle publication failed for {}", pending.id(), failure);
    }
  }

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
    return create(operationId, argsJson, sourceTier, riskTier, gateBehavior, rationale, requestedBy,
        transport, engineContext, provenance, null, false);
  }

  /** Preserve the original operation identity across approval; neither field grants authority. */
  public String create(String operationId, String argsJson, SourceTier sourceTier, RiskTier riskTier,
      GateBehavior gateBehavior, String rationale, String requestedBy, TransportTag transport,
      EngineContext engineContext, io.justsearch.agent.api.registry.InvocationProvenance provenance,
      String operationKey, boolean undo) {
    return create(operationId, argsJson, sourceTier, riskTier, gateBehavior, rationale, requestedBy,
        transport, engineContext, provenance, operationKey, undo, null);
  }

  /** Preserve the exact frozen preparation selected before the gate. */
  public String create(String operationId, String argsJson, SourceTier sourceTier, RiskTier riskTier,
      GateBehavior gateBehavior, String rationale, String requestedBy, TransportTag transport,
      EngineContext engineContext, io.justsearch.agent.api.registry.InvocationProvenance provenance,
      String operationKey, boolean undo, UUID preparationNonce) {
    return create(operationId, argsJson, sourceTier, riskTier, gateBehavior, rationale, requestedBy,
        transport, engineContext, provenance, operationKey, undo, preparationNonce, null);
  }

  /** The preview is a bounded projection for point-to-point approval, never routing broadcast. */
  public synchronized String create(String operationId, String argsJson, SourceTier sourceTier, RiskTier riskTier,
      GateBehavior gateBehavior, String rationale, String requestedBy, TransportTag transport,
      EngineContext engineContext, io.justsearch.agent.api.registry.InvocationProvenance provenance,
      String operationKey, boolean undo, UUID preparationNonce,
      io.justsearch.agent.api.registry.OperationApprovalPreview approvalPreview) {
    return create(operationId, argsJson, sourceTier, riskTier, gateBehavior, rationale, requestedBy,
        transport, engineContext, provenance, operationKey, undo, preparationNonce, approvalPreview,
        "pa-" + UUID.randomUUID().toString().replace("-", ""));
  }

  /** Use the identity minted by the gate before its ledger row was published. */
  public synchronized String create(String operationId, String argsJson, SourceTier sourceTier, RiskTier riskTier,
      GateBehavior gateBehavior, String rationale, String requestedBy, TransportTag transport,
      EngineContext engineContext, io.justsearch.agent.api.registry.InvocationProvenance provenance,
      String operationKey, boolean undo, UUID preparationNonce,
      io.justsearch.agent.api.registry.OperationApprovalPreview approvalPreview, String id) {
    Objects.requireNonNull(id, "id");
    if (pendings.containsKey(id) || rejectedIds.contains(id)) {
      throw new IllegalStateException("Pending authorization identity already used");
    }
    Instant now = clock.instant();
    // Evict expired entries here — expiry is otherwise only checked lazily on peek/consume of
    // a specific id, so a pending that is gated-then-abandoned (never approved) would never be
    // reclaimed. Sweeping on create bounds the map to the live (unexpired) set.
    for (var pending : java.util.List.copyOf(pendings.values())) {
      if (pending.isExpired(now)) {
        pendings.remove(pending.id());
        publish(pending, io.justsearch.app.observability.operations.AuthorizationDisposition.EXPIRED);
      }
    }
    if (pendings.size() >= MAX_ENTRIES) {
      // Pathological: more than MAX_ENTRIES unexpired pendings. Drop the oldest to bound
      // memory (a stale approval the user never actioned is the least valuable to keep).
      pendings.entrySet().stream()
          .min(java.util.Map.Entry.comparingByValue(
              java.util.Comparator.comparing(PendingAuthorization::createdAt)))
          .ifPresent(oldest -> pendings.remove(oldest.getKey(), oldest.getValue()));
    }
    var pending = new PendingAuthorization(
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
            transport, engineContext, provenance, operationKey, undo, preparationNonce, approvalPreview);
    // A gate may have been in flight when another copy received the answer.
    var decided = Optional.ofNullable(operationKey == null ? null : decisions.get(operationKey));
    if (decided.isPresent()) {
      remember(pending, decided.get().disposition());
      publish(pending, decided.get().disposition());
    } else {
      pendings.put(id, pending);
    }
    return id;
  }

  /** Look up a pending without consuming it. Expired/unknown ids return empty. */
  public synchronized Optional<PendingAuthorization> peek(String id) {
    if (id == null) {
      return Optional.empty();
    }
    PendingAuthorization pending = pendings.get(id);
    if (pending == null) {
      return Optional.empty();
    }
    if (pending.isExpired(clock.instant())) {
      pendings.remove(id, pending);
      publish(pending, io.justsearch.app.observability.operations.AuthorizationDisposition.EXPIRED);
      return Optional.empty();
    }
    return Optional.of(pending);
  }

  /**
   * Atomically remove and return a non-expired pending by id — the single-use approval
   * step. Returns empty for unknown, already-consumed, or expired ids (fail closed).
   */
  public synchronized Optional<PendingAuthorization> consume(String id) {
    if (id == null) {
      return Optional.empty();
    }
    PendingAuthorization pending = pendings.remove(id);
    if (pending == null) return Optional.empty();
    if (pending.isExpired(clock.instant())) {
      publish(pending, io.justsearch.app.observability.operations.AuthorizationDisposition.EXPIRED);
      return Optional.empty();
    }
    decide(pending, io.justsearch.app.observability.operations.AuthorizationDisposition.APPROVED);
    return Optional.of(pending);
  }

  /** Competes atomically with approval and expiry; it never creates execution authority. */
  public synchronized Optional<PendingAuthorization> reject(String id) {
    var pending = peek(id);
    if (pending.isEmpty()) return Optional.empty();
    pendings.remove(id);
    decide(pending.get(), io.justsearch.app.observability.operations.AuthorizationDisposition.DENIED);
    return pending;
  }

  private void remember(PendingAuthorization pending,
      io.justsearch.app.observability.operations.AuthorizationDisposition disposition) {
    String key = pending.operationKey() == null ? pending.id() : pending.operationKey();
    decisions.putIfAbsent(key, new Decision(disposition, clock.instant()));
    while (decisions.size() > MAX_ENTRIES) decisions.remove(decisions.keySet().iterator().next());
    if (disposition == io.justsearch.app.observability.operations.AuthorizationDisposition.DENIED) {
      rejectedIds.add(pending.id());
      while (rejectedIds.size() > MAX_ENTRIES) rejectedIds.remove(rejectedIds.iterator().next());
    }
  }

  private void decide(PendingAuthorization pending,
      io.justsearch.app.observability.operations.AuthorizationDisposition disposition) {
    var answered = new java.util.ArrayList<PendingAuthorization>();
    answered.add(pending);
    if (pending.operationKey() != null) {
      for (var sibling : java.util.List.copyOf(pendings.values())) {
        if (pending.operationKey().equals(sibling.operationKey())) {
          pendings.remove(sibling.id());
          answered.add(sibling);
        }
      }
    }
    // Remove every sibling and remember the answer before invoking any subscriber.
    for (var row : answered) remember(row, disposition);
    for (var row : answered) publish(row, disposition);
  }

  /** Test/diagnostic: current live entry count (does not prune). */
  public int size() {
    return pendings.size();
  }
}

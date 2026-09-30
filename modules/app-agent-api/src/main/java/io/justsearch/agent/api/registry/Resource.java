/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Readable / subscribable data resource primitive.
 *
 * <p>Per tempdoc 429 §6 (Resource = MCP Resource): a typed, optionally subscribable
 * data source. Distinguished from Operation by being read-oriented; multiple subscription
 * modes (ONE_SHOT / SSE_STREAM / POLLING) collapse the earlier {@code TableSurface},
 * {@code LogSurface}, {@code HealthEventCatalog} framings.
 *
 * <p>Per tempdoc 444a (Resource Category substrate): the {@link #category} axis is
 * orthogonal to {@link #subscriptionMode} and discriminates information shape (STATE /
 * EVENT_STREAM / HISTORY / TABULAR / TIMESERIES). The Category × SubscriptionMode
 * constraint matrix lives in {@code 20-systems/01-resources.md} and is enforced by
 * {@code ResourceAreaValidator} (Phase 3 of slice 444a).
 *
 * <p>Slice 430 ships the first Resource catalog (HealthEvent, SSE_STREAM); slice 1.2
 * ships only the type and an empty catalog. Slice 444a Phase 2 extended this record
 * with {@code category}, {@code history}, and {@code recovery}.
 *
 * <p>Field guidance:
 *
 * <ul>
 *   <li>{@link #schema}: data-shape JSON Schema as source text (parallel to
 *       {@link Interface#inputs()}).
 *   <li>{@link #category}: information-shape discriminator. See
 *       {@code 50-decisions/06-resource-category.md} for the decision rationale.
 *   <li>{@link #endpoint}: URL where subscribers fetch / subscribe to entries.
 *   <li>{@link #kind}: discriminates Resource flavors used by the generic-renderer
 *       dispatcher (e.g., {@code "health-event-stream"} for tempdoc 430's events).
 *   <li>{@link #history}: retention semantics for Categories that imply retained history
 *       ({@link Category#EVENT_STREAM}, {@link Category#HISTORY}).
 *       Per slice 444a §B.A.2: for hybrid bodies (state + history, like HealthEvent),
 *       describes only the history-portion. State-portions are body-internal in the
 *       snapshot.
 *   <li>{@link #recovery}: per-Resource cross-link to a recovery {@link Operation}.
 *       Useful for STATE Resources or as an "all-encompassing" fix on event-stream
 *       Resources. Per slice 444a §B.A.1: per-event recovery (HealthEvent body level)
 *       is a separate concern handled by slice 438; this Resource-record slot is left
 *       empty for the {@code core.health-events} catalog entry.
 *   <li>{@link #privacy}: typed privacy axis (slice 445). Lifts the
 *       ADR-0028 + {@code LibraryResolveHashOnlyCallerPin} convention to a
 *       per-Resource declaration. Existing Resources with no path-typed fields
 *       use {@link Privacy#noPaths()}.
 *   <li>{@link #itemOperations}: {@link OperationRef}s that apply to a single
 *       item from this Resource (slice 445). Empty for Resources with no
 *       per-row affordances. Validator (when extended in slice 3a-1-8c)
 *       cross-references against the OperationCatalog.
 *   <li>{@link #collectionOperations}: {@link OperationRef}s that apply to the
 *       Resource's collection as a whole (e.g., clear-all, refresh).
 *       Independent of {@link #itemOperations}. Empty for Resources with no
 *       collection-level affordances beyond standard read.
 *   <li>{@link #primaryKey}: name of the field on a row / item that uniquely
 *       identifies it (slice 3a.1.9 §A.5). Required for {@link Category#TABULAR}
 *       Resources (the FE keyed-map state shape requires it). EVENT_STREAM and
 *       HISTORY may declare it to merge keyed snapshot/update overlap; rows without
 *       a key still append independently. Empty string retains append semantics. The
 *       schema-heuristic alternative (look for {@code *Hash} / {@code *Id}
 *       fields automatically) was rejected because it silently mis-identifies
 *       rows with multiple key-shaped fields.
 *   <li>{@link #emissionPolicy} (slice 490 §4.C): present when this Resource is
 *       advisory-shaped — i.e., when {@link #kind} equals {@link #KIND_ADVISORY}.
 *       Governs unprompted system → user emission semantics ({@link RenderHint});
 *       orthogonal to {@link OperationPolicy#confirm()} (which gates execution). Must
 *       be {@link Optional#empty()} for non-advisory kinds; must be present for
 *       advisory kinds. Cross-field invariant enforced by the compact constructor.
 * </ul>
 */
public record Resource(
    ResourceRef id,
    Presentation presentation,
    String schema,
    Category category,
    SubscriptionMode subscriptionMode,
    String endpoint,
    String kind,
    Optional<HistoryPolicy> history,
    Optional<OperationRef> recovery,
    Provenance provenance,
    Privacy privacy,
    Set<OperationRef> itemOperations,
    Set<OperationRef> collectionOperations,
    String primaryKey,
    Audience audience,
    List<ConsumerHook> consumers,
    Optional<EmissionPolicy> emissionPolicy,
    Role role,
    // tempdoc 575 §4.2: a SOURCE-only governance facet — read by the observed-happening gate from the
    // catalog source (.withOrigin), it has no runtime/FE consumer, so it is @JsonIgnore'd off the
    // /api/registry/resources wire (the registry wire carries runtime truth; origin is a build-time
    // classification guardrail). Keeping it off-wire keeps the UIResourceView wire conformance intact.
    @JsonIgnore Optional<ProducerKind> origin) implements RegistryEntry, ConsumerDeclaring {

  /**
   * Reserved {@link #kind} value for advisory-shaped Resources per slice 490 §4.A.
   *
   * <p>Resources declaring {@code kind = KIND_ADVISORY} are user-facing event streams
   * carrying unprompted messages discovered through the toast / inbox / rail-badge
   * Surfaces (slice 490 §4.D). Invariants tied to this kind value (enforced by the
   * compact constructor):
   *
   * <ul>
   *   <li>{@link #emissionPolicy} must be present.
   *   <li>{@link #category} must be {@link Category#EVENT_STREAM}.
   *   <li>{@link #subscriptionMode} must be {@link SubscriptionMode#SSE_STREAM}.
   *   <li>{@link #history} must be present (advisory streams retain at least a
   *       ring-buffer window — pure-ephemeral events without history make replay /
   *       reconnect impossible).
   * </ul>
   *
   * <p>Structurally identical to how {@code health-event-stream},
   * {@code indexing-jobs-table}, and {@code condition-recovery-index} reserve a
   * dispatcher key. No new field on {@link Resource} is required for advisory
   * discovery — a generic inbox renderer filters the {@link ResourceCatalog} by
   * {@code kind = KIND_ADVISORY}.
   */
  public static final String KIND_ADVISORY = "advisory-event-stream";

  public Resource {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(subscriptionMode, "subscriptionMode");
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(history, "history");
    Objects.requireNonNull(recovery, "recovery");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(privacy, "privacy");
    Objects.requireNonNull(primaryKey, "primaryKey");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(emissionPolicy, "emissionPolicy");
    itemOperations = itemOperations == null ? Set.of() : Set.copyOf(itemOperations);
    collectionOperations = collectionOperations == null ? Set.of() : Set.copyOf(collectionOperations);
    consumers = consumers == null ? List.of() : List.copyOf(consumers);
    // tempdoc 571 §4c: role defaults to PRODUCT so an undeclared Resource cannot lift a consuming
    // surface to a foreclosed altitude (DIAGNOSTIC / TRUST are explicit).
    role = role == null ? Role.PRODUCT : role;
    // tempdoc 575 §4.2: origin is the operator-trace producer mechanism of this Resource's data — a
    // GUARDRAIL facet. It defaults to empty because an ordinary Resource is product truth (no producer
    // mechanism). It is declarable so the Channel-vs-Resource boundary is REPRESENTABLE: a Resource that
    // reaches for an operator-trace origin (it is logback / worker-gRPC / external-observer trace data) is
    // modelling something that belongs on a DiagnosticChannel, which the observed-happening gate's
    // operator-trace-must-be-channel rule forecloses (ADR-0036: operator traces are not Resource truth).
    origin = origin == null ? Optional.empty() : origin;
    if (category == Category.TABULAR && primaryKey.isBlank()) {
      throw new IllegalArgumentException(
          "Resource " + id.value() + " is TABULAR; primaryKey must be non-blank");
    }
    // Slice 490 §4.A: emissionPolicy presence is gated by the reserved advisory kind.
    boolean isAdvisory = KIND_ADVISORY.equals(kind);
    if (isAdvisory && emissionPolicy.isEmpty()) {
      throw new IllegalArgumentException(
          "Resource " + id.value() + " has kind=" + KIND_ADVISORY
              + " — emissionPolicy must be present");
    }
    if (!isAdvisory && emissionPolicy.isPresent()) {
      throw new IllegalArgumentException(
          "Resource " + id.value() + " has kind=" + kind
              + " — emissionPolicy is only valid for kind=" + KIND_ADVISORY);
    }
    if (isAdvisory && category != Category.EVENT_STREAM) {
      throw new IllegalArgumentException(
          "Resource " + id.value() + " has kind=" + KIND_ADVISORY
              + " — category must be EVENT_STREAM (got " + category + ")");
    }
    if (isAdvisory && subscriptionMode != SubscriptionMode.SSE_STREAM) {
      throw new IllegalArgumentException(
          "Resource " + id.value() + " has kind=" + KIND_ADVISORY
              + " — subscriptionMode must be SSE_STREAM (got " + subscriptionMode + ")");
    }
    if (isAdvisory && history.isEmpty()) {
      throw new IllegalArgumentException(
          "Resource " + id.value() + " has kind=" + KIND_ADVISORY
              + " — history (HistoryPolicy) must be present");
    }
  }

  /** Backward-compat constructor — defaults audience=USER, consumers=empty, emissionPolicy=empty. */
  public Resource(
      ResourceRef id,
      Presentation presentation,
      String schema,
      Category category,
      SubscriptionMode subscriptionMode,
      String endpoint,
      String kind,
      Optional<HistoryPolicy> history,
      Optional<OperationRef> recovery,
      Provenance provenance,
      Privacy privacy,
      Set<OperationRef> itemOperations,
      Set<OperationRef> collectionOperations,
      String primaryKey) {
    this(id, presentation, schema, category, subscriptionMode, endpoint, kind,
        history, recovery, provenance, privacy, itemOperations, collectionOperations,
        primaryKey, Audience.USER, List.of(), Optional.empty(), Role.PRODUCT, Optional.empty());
  }

  /**
   * Returns a copy of this Resource with the altitude {@link Role} set — the declaration entry point
   * for diagnostic / trust Resource catalogs (tempdoc 571 §4c). Used as
   * {@code new Resource(...).withRole(Role.DIAGNOSTIC)} so a catalog keeps whichever back-compat
   * constructor it already uses (audience, emissionPolicy, …) without a combinatorial ctor explosion.
   */
  public Resource withRole(Role newRole) {
    return new Resource(
        id, presentation, schema, category, subscriptionMode, endpoint, kind,
        history, recovery, provenance, privacy, itemOperations, collectionOperations,
        primaryKey, audience, consumers, emissionPolicy, newRole, origin);
  }

  /**
   * Returns a copy of this Resource with the operator-trace {@link ProducerKind} origin set (tempdoc
   * 575 §4.2). Declaring an origin asserts this Resource's data is operator-trace data (logback /
   * worker-gRPC / external-observer) — which the {@code observed-happening} gate's
   * {@code operator-trace-must-be-channel} rule forecloses, because such data belongs on a
   * {@link DiagnosticChannel}, not a Resource (ADR-0036). The facet exists so that boundary is
   * representable-and-rejected rather than a silent mis-model. Mirrors {@link #withRole} so a catalog
   * keeps whichever back-compat constructor it already uses without a combinatorial ctor explosion.
   */
  public Resource withOrigin(ProducerKind newOrigin) {
    return new Resource(
        id, presentation, schema, category, subscriptionMode, endpoint, kind,
        history, recovery, provenance, privacy, itemOperations, collectionOperations,
        primaryKey, audience, consumers, emissionPolicy, role, Optional.ofNullable(newOrigin));
  }

  /** Backward-compat constructor — declares audience; defaults consumers=empty, emissionPolicy=empty. */
  public Resource(
      ResourceRef id,
      Presentation presentation,
      String schema,
      Category category,
      SubscriptionMode subscriptionMode,
      String endpoint,
      String kind,
      Optional<HistoryPolicy> history,
      Optional<OperationRef> recovery,
      Provenance provenance,
      Privacy privacy,
      Set<OperationRef> itemOperations,
      Set<OperationRef> collectionOperations,
      String primaryKey,
      Audience audience) {
    this(id, presentation, schema, category, subscriptionMode, endpoint, kind,
        history, recovery, provenance, privacy, itemOperations, collectionOperations,
        primaryKey, audience, List.of(), Optional.empty(), Role.PRODUCT, Optional.empty());
  }

  /** Backward-compat constructor — slice 490 pre-491 shape; declares emissionPolicy, defaults audience=USER, consumers=empty. */
  public Resource(
      ResourceRef id,
      Presentation presentation,
      String schema,
      Category category,
      SubscriptionMode subscriptionMode,
      String endpoint,
      String kind,
      Optional<HistoryPolicy> history,
      Optional<OperationRef> recovery,
      Provenance provenance,
      Privacy privacy,
      Set<OperationRef> itemOperations,
      Set<OperationRef> collectionOperations,
      String primaryKey,
      Optional<EmissionPolicy> emissionPolicy) {
    this(id, presentation, schema, category, subscriptionMode, endpoint, kind,
        history, recovery, provenance, privacy, itemOperations, collectionOperations,
        primaryKey, Audience.USER, List.of(), emissionPolicy, Role.PRODUCT, Optional.empty());
  }

  /** Backward-compat constructor — declares audience + consumers; defaults emissionPolicy=empty. */
  public Resource(
      ResourceRef id,
      Presentation presentation,
      String schema,
      Category category,
      SubscriptionMode subscriptionMode,
      String endpoint,
      String kind,
      Optional<HistoryPolicy> history,
      Optional<OperationRef> recovery,
      Provenance provenance,
      Privacy privacy,
      Set<OperationRef> itemOperations,
      Set<OperationRef> collectionOperations,
      String primaryKey,
      Audience audience,
      List<ConsumerHook> consumers) {
    this(id, presentation, schema, category, subscriptionMode, endpoint, kind,
        history, recovery, provenance, privacy, itemOperations, collectionOperations,
        primaryKey, audience, consumers, Optional.empty(), Role.PRODUCT, Optional.empty());
  }

  /**
   * Backward-compat constructor — the pre-571 canonical shape (declares audience + consumers +
   * emissionPolicy); defaults role=PRODUCT.
   */
  public Resource(
      ResourceRef id,
      Presentation presentation,
      String schema,
      Category category,
      SubscriptionMode subscriptionMode,
      String endpoint,
      String kind,
      Optional<HistoryPolicy> history,
      Optional<OperationRef> recovery,
      Provenance provenance,
      Privacy privacy,
      Set<OperationRef> itemOperations,
      Set<OperationRef> collectionOperations,
      String primaryKey,
      Audience audience,
      List<ConsumerHook> consumers,
      Optional<EmissionPolicy> emissionPolicy) {
    this(id, presentation, schema, category, subscriptionMode, endpoint, kind,
        history, recovery, provenance, privacy, itemOperations, collectionOperations,
        primaryKey, audience, consumers, emissionPolicy, Role.PRODUCT, Optional.empty());
  }
}

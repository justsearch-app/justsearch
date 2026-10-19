/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Versioned in-memory replica of currently-asserted health conditions and threshold states.
 *
 * <p>Per tempdoc 430 §"In scope — substrate" (item 6) + §A.6 wiring sketch + rev 3.11
 * §B.X.2: the head holds a single replica of every persistent-state {@link HealthEventBody}
 * (both {@link AssertedCondition} and {@link ThresholdState}) that emitters have asserted.
 * Subscribers receive a snapshot on connect; subsequent transitions broadcast as
 * {@link Transition} deltas through {@link HealthEventChangeRegistry}.
 *
 * <p>Semantics mirror k8s {@code SetStatusCondition} verbatim for {@link AssertedCondition}:
 *
 * <ul>
 *   <li>Re-emitting a condition with the same {@code status}, {@code reason}, and
 *       {@code message} is {@link Transition#UNCHANGED} (no version bump, stored
 *       record untouched).
 *   <li>Status flip is {@link Transition#MODIFIED} with the caller's
 *       {@code lastTransitionTime} stored — a real transition moment.
 *   <li>{@code reason}- or {@code message}-only change (status equal) is also
 *       {@link Transition#MODIFIED}, but the store <em>preserves the prior
 *       lastTransitionTime</em> per k8s convention. Callers may pass
 *       {@code clock.instant()} blindly; the store rewrites the stored body so
 *       {@code lastTransitionTime} reflects the last real status change.
 *   <li>New {@code (id, subject)} → {@link Transition#ADDED}.
 *   <li>Explicit {@link #clear} → {@link Transition#REMOVED}.
 * </ul>
 *
 * <p>For {@link ThresholdState} bodies (rev 3.11 §B.X.2 generalization): the
 * {@code phase} field replaces {@code status} as the transition discriminator
 * ({@link ThresholdPhase#FIRING} ≈ "the named threshold is firing"). Magnitude-only
 * changes (same phase, different magnitudes) preserve the prior
 * {@code lastTransitionTime} — same defect-class protection as the AssertedCondition
 * reason-only-change branch. {@link Transition#UNCHANGED} when phase, magnitudes, and
 * message all match.
 *
 * <p>{@link #upsert} is atomic with respect to the {@code (id, subject)} key — the
 * read-decide-write sequence runs inside a single {@link ConcurrentHashMap#compute}
 * call, so concurrent first-emits cannot both observe an absent prior and double-add.
 *
 * <p>{@link #currentSnapshot()} returns conditions in stable lexicographic order by
 * {@code (id, subject)} so the SSE wire payload is deterministic across restarts and
 * the FE renders without reflow on snapshot refresh. Mixed body types sort uniformly.
 *
 * <p><strong>Two version counters note</strong>: {@link #currentVersion()} is internal
 * bookkeeping for the in-memory replica; the SSE wire's {@code catalogVersion} is
 * {@link HealthEventChangeRegistry#currentVersion()}. Phase 4+ adapters MUST
 * {@code upsert} first, then {@code broadcast} only when
 * {@code transition != UNCHANGED}, so the registry's counter advances exactly once per
 * observable change.
 */
public final class ConditionStore {

  /**
   * Result of an upsert operation. {@link #UNCHANGED} bumps neither version nor stored
   * record. {@code clear()} returns {@code Optional<HealthEvent>} directly (present →
   * removed, empty → no-op) rather than a Transition value.
   */
  public enum Transition {
    ADDED,
    MODIFIED,
    UNCHANGED
  }

  private record ConditionKey(String id, String subject) {}

  private static final Comparator<HealthEvent> SNAPSHOT_ORDER =
      Comparator.<HealthEvent, String>comparing(HealthEvent::id)
          .thenComparing(ConditionStore::subjectOf);

  /** Extracts the subject from either an AssertedCondition or ThresholdState body. */
  private static String subjectOf(HealthEvent event) {
    HealthEventBody body = event.body();
    if (body instanceof AssertedCondition c) {
      return c.subject();
    }
    if (body instanceof ThresholdState t) {
      return t.subject();
    }
    throw new IllegalStateException(
        "ConditionStore: unexpected body type in snapshot: " + body.getClass().getSimpleName());
  }

  private final ConcurrentHashMap<ConditionKey, HealthEvent> conditions = new ConcurrentHashMap<>();
  private volatile ReentrantReadWriteLock publicationLock = new ReentrantReadWriteLock();
  private final AtomicLong version = new AtomicLong();

  /** Binds current-state reads to the component authority's visibility boundary before wiring. */
  public void bindPublicationBoundary(ReentrantReadWriteLock boundary) {
    publicationLock = Objects.requireNonNull(boundary, "boundary");
  }

  /**
   * Returns the internal mutation counter. <strong>Not</strong> the SSE wire's
   * {@code catalogVersion} — see {@link HealthEventChangeRegistry#currentVersion()} for
   * that. This counter advances on every successful insert/modify/remove; the registry's
   * counter advances per broadcast (which the bridge adapter suppresses on UNCHANGED).
   */
  public long currentVersion() {
    return version.get();
  }

  /** Returns the conditions in stable lexicographic order by {@code (id, subject)}. */
  public List<HealthEvent> currentSnapshot() {
    var lock = publicationLock.readLock();
    lock.lock();
    try { return conditions.values().stream().sorted(SNAPSHOT_ORDER).toList(); }
    finally { lock.unlock(); }
  }

  /** Returns the current condition for the given (id, subject) key, if any. */
  public Optional<HealthEvent> find(String id, String subject) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(subject, "subject");
    var lock = publicationLock.readLock();
    lock.lock();
    try { return Optional.ofNullable(conditions.get(new ConditionKey(id, subject))); }
    finally { lock.unlock(); }
  }

  /**
   * Inserts or updates the persistent-state body carried by {@code event}. The event's body
   * MUST be either an {@link AssertedCondition} or a {@link ThresholdState}; lifecycle bodies
   * belong in {@link OccurrenceLog}.
   *
   * <p>Atomic per {@link ConcurrentHashMap#compute} — the transition decision and the
   * store mutation run in a single critical section keyed on {@code (id, subject)}.
   *
   * <p>Per rev 3.11 §B.X.2: ThresholdState is handled by a parallel branch using {@code phase}
   * as the discriminator (analog of {@code status}). Magnitude-only changes (same phase)
   * preserve the prior {@code lastTransitionTime}.
   *
   * @return the resulting {@link Transition}; {@link Transition#UNCHANGED} bumps neither
   *     version nor stored record.
   */
  public Transition upsert(HealthEvent event) {
    Objects.requireNonNull(event, "event");
    if (event.body() instanceof AssertedCondition incoming) {
      return upsertAsserted(event, incoming);
    }
    if (event.body() instanceof ThresholdState incoming) {
      return upsertThreshold(event, incoming);
    }
    throw new IllegalArgumentException(
        "ConditionStore.upsert requires AssertedCondition or ThresholdState body, got "
            + event.body().getClass().getSimpleName());
  }

  private Transition upsertAsserted(HealthEvent event, AssertedCondition incoming) {
    ConditionKey key = new ConditionKey(event.id(), incoming.subject());
    AtomicReference<Transition> outcome = new AtomicReference<>();
    conditions.compute(
        key,
        (k, prior) -> {
          if (prior == null) {
            outcome.set(Transition.ADDED);
            return event;
          }
          if (!(prior.body() instanceof AssertedCondition priorCondition)) {
            // Body-type swap on the same key — treat as a fresh ADD per the rev 3.11 §B.X.2
            // additive semantics. Should not happen in practice (each id is statically a
            // condition or a threshold), but defended for forward-compat.
            outcome.set(Transition.MODIFIED);
            return event;
          }
          boolean statusEqual = priorCondition.status() == incoming.status();
          boolean reasonEqual = priorCondition.reason().equals(incoming.reason());
          boolean messageEqual = priorCondition.message().equals(incoming.message());
          if (statusEqual && reasonEqual && messageEqual) {
            outcome.set(Transition.UNCHANGED);
            return prior;
          }
          outcome.set(Transition.MODIFIED);
          if (statusEqual) {
            // k8s SetStatusCondition: lastTransitionTime moves only on status change.
            return rewriteAssertedTransitionTime(
                event, incoming, priorCondition.lastTransitionTime());
          }
          return event;
        });
    Transition transition = outcome.get();
    if (transition != Transition.UNCHANGED) {
      version.incrementAndGet();
    }
    return transition;
  }

  private Transition upsertThreshold(HealthEvent event, ThresholdState incoming) {
    ConditionKey key = new ConditionKey(event.id(), incoming.subject());
    AtomicReference<Transition> outcome = new AtomicReference<>();
    conditions.compute(
        key,
        (k, prior) -> {
          if (prior == null) {
            outcome.set(Transition.ADDED);
            return event;
          }
          if (!(prior.body() instanceof ThresholdState priorThreshold)) {
            // Body-type swap (see comment in upsertAsserted).
            outcome.set(Transition.MODIFIED);
            return event;
          }
          boolean phaseEqual = priorThreshold.phase() == incoming.phase();
          boolean magnitudesEqual = priorThreshold.magnitudes().equals(incoming.magnitudes());
          boolean messageEqual = priorThreshold.message().equals(incoming.message());
          if (phaseEqual && magnitudesEqual && messageEqual) {
            outcome.set(Transition.UNCHANGED);
            return prior;
          }
          outcome.set(Transition.MODIFIED);
          if (phaseEqual) {
            // Magnitude-only / message-only change: preserve prior lastTransitionTime
            // (analogous to k8s SetStatusCondition's reason-only branch).
            return rewriteThresholdTransitionTime(
                event, incoming, priorThreshold.lastTransitionTime());
          }
          return event;
        });
    Transition transition = outcome.get();
    if (transition != Transition.UNCHANGED) {
      version.incrementAndGet();
    }
    return transition;
  }

  /**
   * Removes a condition by {@code (id, subject)}.
   *
   * @return the removed event when one was present (so callers can broadcast it as a
   *     {@code CONDITION_REMOVED} delta), or {@link Optional#empty()} when no record
   *     existed for the key (no-op; version not bumped).
   */
  public Optional<HealthEvent> clear(String id, String subject) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(subject, "subject");
    HealthEvent removed = conditions.remove(new ConditionKey(id, subject));
    if (removed == null) {
      return Optional.empty();
    }
    version.incrementAndGet();
    return Optional.of(removed);
  }

  /**
   * Builds a clone of {@code event} whose {@link AssertedCondition} body carries
   * {@code preservedTransitionTime} instead of the caller-supplied value. Used for the
   * reason-only / message-only MODIFIED branch where k8s mandates preserving the prior
   * transition time.
   */
  private static HealthEvent rewriteAssertedTransitionTime(
      HealthEvent event, AssertedCondition incoming, Instant preservedTransitionTime) {
    AssertedCondition rewritten =
        new AssertedCondition(
            incoming.subject(),
            incoming.status(),
            incoming.reason(),
            preservedTransitionTime,
            incoming.message(),
            incoming.recovery(),
            incoming.relatedMetrics());
    return new HealthEvent(
        event.id(),
        event.timestamp(),
        event.source(),
        event.severity(),
        event.i18nKey(),
        rewritten);
  }

  /**
   * Builds a clone of {@code event} whose {@link ThresholdState} body carries
   * {@code preservedTransitionTime}. Used for the magnitude-only / message-only MODIFIED
   * branch where the rule engine mandates preserving the prior transition time
   * (rev 3.11 §B.X.2 generalization).
   */
  private static HealthEvent rewriteThresholdTransitionTime(
      HealthEvent event, ThresholdState incoming, Instant preservedTransitionTime) {
    ThresholdState rewritten =
        new ThresholdState(
            incoming.subject(),
            incoming.phase(),
            incoming.magnitudes(),
            preservedTransitionTime,
            incoming.message(),
            incoming.recovery(),
            incoming.relatedMetrics());
    return new HealthEvent(
        event.id(),
        event.timestamp(),
        event.source(),
        event.severity(),
        event.i18nKey(),
        rewritten);
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OperationLeaseSnapshot;
import io.justsearch.app.api.OperationAdmissionClosedException;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One process-local work owner. Admission reserves an actual live work slot atomically; a slot
 * remains occupied until every asynchronous owner acknowledges completion. Attribution equality
 * selects a fairness bucket only, never a cancellation target.
 */
public final class EngineAdmissionController implements EngineAdmissionService, OperationLeaseService {
  private static final Logger LOG = LoggerFactory.getLogger(EngineAdmissionController.class);
  private final Object lock = new Object();
  private final int perContextLimit;
  private final int aggregateLimit;
  private final int retryAfterSeconds;
  private final int observationAggregateLimit;
  private final OperationLeaseService leases;
  private final ReentrantReadWriteLock publication;
  private final Map<UUID, Work> active = new LinkedHashMap<>();
  private final Map<Bucket, Integer> inferenceCounts = new HashMap<>();
  private int inferenceCount;
  private String preparationId;
  private boolean closing;
  private final java.util.concurrent.atomic.AtomicReference<StatusAdmission> statusAdmission =
      new java.util.concurrent.atomic.AtomicReference<>(
          new StatusAdmission(false, 0, Map.of(), 0, Map.of()));
  private final io.justsearch.core.scheduling.UserActivity userActivity =
      new io.justsearch.core.scheduling.UserActivity();

  public io.justsearch.core.scheduling.UserActivity userActivity() { return userActivity; }

  /** Explicit standalone-launcher provider; the full Engine root supplies its already-loaded policy. */
  public EngineAdmissionController() {
    this(EngineResourcePolicy.load());
  }

  EngineAdmissionController(EngineResourcePolicy policy) {
    this(policy, new ReentrantReadWriteLock());
  }

  EngineAdmissionController(EngineResourcePolicy policy, ReentrantReadWriteLock publication) {
    this(policy.execution().get("perContextLimit"), policy.execution().get("aggregateLimit"),
        policy.execution().get("retryAfterSeconds"), observationCapacity(policy),
        new OperationLeaseServiceImpl(), publication);
  }

  /** Explicit policy seam for deterministic admission and transport acceptance tests. */
  public EngineAdmissionController(int perContextLimit, int aggregateLimit, int retryAfterSeconds) {
    this(perContextLimit, aggregateLimit, retryAfterSeconds, aggregateLimit, OperationLeaseServiceImpl.processLocal(),
        new ReentrantReadWriteLock());
  }

  private EngineAdmissionController(int perContextLimit, int aggregateLimit, int retryAfterSeconds,
      int observationAggregateLimit, OperationLeaseService leases, ReentrantReadWriteLock publication) {
    if (perContextLimit < 1 || aggregateLimit < 1 || retryAfterSeconds < 1
        || observationAggregateLimit < 1) {
      throw new IllegalArgumentException("Engine admission limits must be positive");
    }
    this.perContextLimit = perContextLimit;
    this.aggregateLimit = aggregateLimit;
    this.retryAfterSeconds = retryAfterSeconds;
    this.observationAggregateLimit = observationAggregateLimit;
    this.leases = Objects.requireNonNull(leases, "leases");
    this.publication = Objects.requireNonNull(publication, "publication");
  }

  private static int observationCapacity(EngineResourcePolicy policy) {
    // Status uses the background call lane, isolated from foreground semantic search.
    // Match EngineKnowledgeClient's capacity; every admitted read has a worker or queue seat.
    int backgroundThreads = policy.execution().get("backgroundThreads");
    return Math.min(policy.execution().get("aggregateLimit"),
        backgroundThreads + policy.execution().get("backgroundQueue"));
  }

  @Override
  public OperationLeaseHandle register(String opClass, OpCriticality criticality,
      long expectedDurationSec, Map<String, Object> metadata) {
    synchronized (lock) {
      if (closing) throw closedRegistration();
      return leases.register(opClass, criticality, expectedDurationSec, metadata);
    }
  }

  @Override
  public OperationLeaseHandle register(String opClass, OpCriticality criticality,
      long expectedDurationSec, Map<String, Object> metadata, Runnable cancellationRequest) {
    synchronized (lock) {
      if (closing) throw closedRegistration();
      return leases.register(opClass, criticality, expectedDurationSec, metadata, cancellationRequest);
    }
  }

  @Override
  public OperationLeaseSnapshot freezeAdmission(String reason) {
    requireReason(reason);
    synchronized (lock) {
      OperationLeaseSnapshot snapshot = leases.freezeAdmission(reason);
      freeze(snapshot.preparationId(), reason);
      return snapshot;
    }
  }

  @Override
  public OperationLeaseSnapshot snapshot() {
    synchronized (lock) {
      OperationLeaseSnapshot observed = leases.snapshot();
      return closing ? new OperationLeaseSnapshot(true, "process-closing", "shutdown",
          observed.activeLeases(), observed.cancellationRequestedOpIds()) : observed;
    }
  }

  @Override
  public OperationLeaseSnapshot requestCancellation(String id) {
    // The delegate invokes owner callbacks outside its own lock; do not hold ours across them.
    return leases.requestCancellation(id);
  }

  @Override
  public void releaseAdmission(String id) {
    synchronized (lock) {
      leases.releaseAdmission(id);
      releaseFreeze(id);
    }
  }

  @Override
  public EngineWorkHandle admit(EngineContext context, boolean allowWhileFrozen) {
    return admit(context, allowWhileFrozen, false);
  }

  @Override
  public EngineWorkHandle admitObservation(EngineContext context) {
    return admit(context, false, true);
  }

  private EngineWorkHandle admit(EngineContext context, boolean allowWhileFrozen,
      boolean observation) {
    Objects.requireNonNull(context, "context");
    if (context.workId().isPresent()) throw new IllegalArgumentException("Work is already attached");
    synchronized (lock) {
      if (closing) refuse(EngineAdmissionException.Reason.FROZEN);
      if (preparationId != null && !allowWhileFrozen) refuse(EngineAdmissionException.Reason.FROZEN);
      Bucket bucket = Bucket.of(context);
      reserveAdmission(bucket, allowWhileFrozen, observation);
      UUID id = UUID.randomUUID();
      Work work = new Work(context.withWorkId(id), bucket, observation);
      active.put(id, work);
      return new Reference(work, work.initial);
    }
  }

  /**
   * Reserves a status read in the bounded observation pool without taking the work owner's lock:
   * search and generation capacity cannot refuse it, but overlapping observations beyond the
   * observation limits are refused. Released once, after response serialization.
   */
  @Override
  public StatusReservation reserveStatusAdmission(EngineContext context) {
    Objects.requireNonNull(context, "context");
    Bucket bucket = Bucket.of(context);
    reserveAdmission(bucket, false, true);
    var released = new java.util.concurrent.atomic.AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true)) releaseReservation(bucket, true);
    };
  }

  private void reserveAdmission(Bucket bucket, boolean allowWhileFrozen, boolean observation) {
    for (;;) {
      StatusAdmission observed = statusAdmission.get();
      if (observed.frozen() && !allowWhileFrozen) refuse(EngineAdmissionException.Reason.FROZEN);
      Map<Bucket, Integer> selectedCounts =
          observation ? observed.observationCounts() : observed.counts();
      int aggregateCount = observation
          ? observed.observationCount() : observed.activeCount() - observed.observationCount();
      int selectedLimit = observation ? observationAggregateLimit : aggregateLimit;
      if (aggregateCount >= selectedLimit) refuse(EngineAdmissionException.Reason.ENGINE_LIMIT);
      if (selectedCounts.getOrDefault(bucket, 0) >= perContextLimit) {
        refuse(EngineAdmissionException.Reason.CONTEXT_LIMIT);
      }
      var nextCounts = new HashMap<>(selectedCounts);
      nextCounts.merge(bucket, 1, Integer::sum);
      StatusAdmission next = observation
          ? new StatusAdmission(observed.frozen(), observed.activeCount() + 1, observed.counts(),
              observed.observationCount() + 1, Map.copyOf(nextCounts))
          : new StatusAdmission(observed.frozen(), observed.activeCount() + 1, Map.copyOf(nextCounts),
              observed.observationCount(), observed.observationCounts());
      if (statusAdmission.compareAndSet(observed, next)) return;
    }
  }

  private void releaseReservation(Bucket bucket, boolean observation) {
    statusAdmission.updateAndGet(observed -> {
      var nextCounts = new HashMap<>(observation ? observed.observationCounts() : observed.counts());
      nextCounts.compute(bucket, (ignored, count) -> count == 1 ? null : count - 1);
      return observation
          ? new StatusAdmission(observed.frozen(), observed.activeCount() - 1, observed.counts(),
              observed.observationCount() - 1, Map.copyOf(nextCounts))
          : new StatusAdmission(observed.frozen(), observed.activeCount() - 1, Map.copyOf(nextCounts),
              observed.observationCount(), observed.observationCounts());
    });
  }

  /** Admission writers change the boundary without overwriting concurrent status reservations. */
  private void publishStatusAdmission() {
    statusAdmission.updateAndGet(observed -> new StatusAdmission(
        closing || preparationId != null, observed.activeCount(), observed.counts(),
        observed.observationCount(), observed.observationCounts()));
  }

  @Override
  public EngineWorkHandle admitRequest(
      EngineContext context, boolean allowWhileFrozen, boolean activeRequest) {
    return admitRequest(context, allowWhileFrozen, activeRequest, 0);
  }

  @Override
  public EngineWorkHandle admitRequest(
      EngineContext context, boolean allowWhileFrozen, boolean activeRequest, long inputAgeMs) {
    var work = admit(context, allowWhileFrozen);
    if (activeRequest) userActivity.record(inputAgeMs);
    return work;
  }

  @Override
  public EngineWorkHandle admitObservationRequest(EngineContext context, boolean activeRequest) {
    var work = admitObservation(context);
    if (activeRequest) userActivity.record();
    return work;
  }

  @Override
  public EngineWorkHandle attach(EngineContext context) {
    Objects.requireNonNull(context, "context");
    if (context.workId().isEmpty()) return admitRequest(context, false,
        context.clientKind() != EngineContext.ClientKind.INTERNAL
            && context.clientKind() != EngineContext.ClientKind.SUPERVISOR
            && context.urgency() == EngineContext.Urgency.FOREGROUND);
    synchronized (lock) {
      if (closing) refuse(EngineAdmissionException.Reason.FROZEN);
      Work work = active.get(context.workId().orElseThrow());
      if (work == null || !work.bucket.equals(Bucket.of(context))) {
        throw new EngineAdmissionException(EngineAdmissionException.Reason.WORK_FINISHED, retryAfterSeconds);
      }
      work.references++;
      return new Reference(work, context);
    }
  }

  @Override
  public EngineWorkHandle attachInference(EngineContext context) {
    synchronized (lock) {
      Reference reference = (Reference) attach(context);
      Work work = reference.work;
      try {
        if (!work.inference) {
          // An issued native run cannot surrender ownership at its caller's deadline. Reserve
          // one text slot in both envelopes, across all inference lanes and exact work references.
          if (inferenceCount >= aggregateLimit - 1) {
            refuse(EngineAdmissionException.Reason.ENGINE_LIMIT);
          }
          if (inferenceCounts.getOrDefault(work.bucket, 0) >= perContextLimit - 1) {
            refuse(EngineAdmissionException.Reason.CONTEXT_LIMIT);
          }
          work.inference = true;
          inferenceCount++;
          inferenceCounts.merge(work.bucket, 1, Integer::sum);
        }
        return reference;
      } catch (RuntimeException | Error failure) {
        reference.close();
        throw failure;
      }
    }
  }

  @Override
  public void beginClosing() {
    publication.writeLock().lock();
    try {
      synchronized (lock) {
        closing = true;
        publishStatusAdmission();
      }
    } finally {
      publication.writeLock().unlock();
    }
  }

  /** Monotonic process-closing observation, sampled under the shared publication write lock. */
  @Override public boolean isClosing() {
    synchronized (lock) { return closing; }
  }

  private static OperationAdmissionClosedException closedRegistration() {
    return new OperationAdmissionClosedException("process-closing", "shutdown");
  }

  private void freeze(String id, String reason) {
    requireReason(id);
    requireReason(reason);
    synchronized (lock) {
      if (preparationId == null) preparationId = id;
      else if (!preparationId.equals(id)) throw new IllegalArgumentException("Admission freeze has another owner");
      publishStatusAdmission();
    }
  }

  private void releaseFreeze(String id) {
    synchronized (lock) {
      if (preparationId == null) return;
      if (!preparationId.equals(id)) throw new IllegalArgumentException("Admission freeze has another owner");
      preparationId = null;
      publishStatusAdmission();
    }
  }

  @Override
  public void cancelInteractive(String reason) {
    requireReason(reason);
    List<Work> targets;
    synchronized (lock) {
      targets = active.values().stream()
          .filter(w -> w.initial.survival() == EngineContext.Survival.INTERACTIVE).toList();
    }
    targets.forEach(w -> cancel(w, reason));
  }

  @Override
  public int retryAfterSeconds() { return retryAfterSeconds; }

  @Override
  public EngineAdmissionService.Limits limits() {
    return new EngineAdmissionService.Limits(perContextLimit, aggregateLimit, retryAfterSeconds);
  }

  @Override
  public int activeWorkCount() {
    return statusAdmission.get().activeCount();
  }

  @Override
  public boolean awaitDrained(java.time.Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    long budget = timeout.toNanos();
    if (budget < 0) throw new IllegalArgumentException("Drain timeout must be nonnegative");
    long deadline = System.nanoTime() + budget;
    synchronized (lock) {
      if (!closing) throw new IllegalStateException("Drain requires process closing");
      while (statusAdmission.get().activeCount() != 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try {
          java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, Math.min(remaining, 10_000_000L));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    }
  }

  private void refuse(EngineAdmissionException.Reason reason) {
    throw new EngineAdmissionException(reason, retryAfterSeconds);
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 256
        || reason.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("A bounded cancellation or freeze reason is required");
    }
  }

  private void cancel(Work work, String reason) {
    requireReason(reason);
    List<Runnable> callbacks;
    synchronized (lock) {
      if (work.completed || work.cancelReason != null) return;
      work.cancelReason = reason;
      callbacks = new ArrayList<>(work.cancellation);
      work.cancellation.clear();
    }
    notifyCallbacks(callbacks);
  }

  private static void notifyCallbacks(List<Runnable> callbacks) {
    for (Runnable callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException failure) {
        // One broken owner must not prevent cancellation/release notification of the others.
        LOG.warn("Engine work lifecycle callback failed", failure);
      }
    }
  }

  private record Bucket(EngineContext.ClientKind kind, String clientId) {
    static Bucket of(EngineContext context) { return new Bucket(context.clientKind(), context.clientId()); }
  }

  /**
   * Lock-free admission publication. {@code activeCount} holds every live slot (work and
   * observations); work capacity is {@code activeCount - observationCount}, so observations never
   * consume search or generation capacity.
   */
  private record StatusAdmission(boolean frozen, int activeCount, Map<Bucket, Integer> counts,
      int observationCount, Map<Bucket, Integer> observationCounts) {}

  private static final class Work {
    private final EngineContext initial;
    private final Bucket bucket;
    private final boolean observation;
    private final Set<Runnable> cancellation = new LinkedHashSet<>();
    private final Set<Runnable> background = new LinkedHashSet<>();
    private final Set<Runnable> completion = new LinkedHashSet<>();
    private int references = 1;
    private String cancelReason;
    private boolean detached;
    private boolean completed;
    private boolean inference;

    private Work(EngineContext initial, Bucket bucket, boolean observation) {
      this.initial = initial;
      this.bucket = bucket;
      this.observation = observation;
    }
  }

  private final class Reference implements EngineWorkHandle {
    private final Work work;
    private final EngineContext view;
    private boolean closed;

    private Reference(Work work, EngineContext view) { this.work = work; this.view = view; }

    @Override
    public EngineContext context() {
      synchronized (lock) {
        return new EngineContext(view.clientKind(), view.clientId(), view.sessionId(),
            view.grantReference(), view.sourceTier(), view.transport(), work.initial.survival(),
            work.detached ? EngineContext.Urgency.BACKGROUND : work.initial.urgency(), view.workId());
      }
    }

    @Override
    public EngineWorkHandle retain() {
      synchronized (lock) {
        if (closed || work.completed) {
          throw new EngineAdmissionException(EngineAdmissionException.Reason.WORK_FINISHED, retryAfterSeconds);
        }
        work.references++;
        return new Reference(work, view);
      }
    }

    @Override
    public Optional<String> cancellationReason() {
      synchronized (lock) { return Optional.ofNullable(work.cancelReason); }
    }

    @Override
    public void cancel(String reason) { EngineAdmissionController.this.cancel(work, reason); }

    @Override
    public void waitingClientGone() {
      List<Runnable> callbacks;
      synchronized (lock) {
        if (work.completed || work.detached || work.initial.survival() != EngineContext.Survival.DURABLE
            || work.initial.urgency() != EngineContext.Urgency.FOREGROUND) return;
        work.detached = true;
        callbacks = new ArrayList<>(work.background);
        work.background.clear();
      }
      notifyCallbacks(callbacks);
    }

    @Override
    public Registration onCancel(Consumer<String> callback) {
      Objects.requireNonNull(callback, "callback");
      return register(work.cancellation, () -> callback.accept(cancellationReason().orElseThrow()),
          Event.CANCEL);
    }

    @Override
    public Registration onBackground(Runnable callback) { return register(work.background, callback, Event.BACKGROUND); }

    @Override
    public Registration onCompletion(Runnable callback) { return register(work.completion, callback, Event.COMPLETE); }

    private Registration register(Set<Runnable> callbacks, Runnable callback, Event event) {
      Objects.requireNonNull(callback, "callback");
      boolean happened;
      synchronized (lock) {
        happened = switch (event) {
          case CANCEL -> work.cancelReason != null;
          case BACKGROUND -> work.detached;
          case COMPLETE -> work.completed;
        };
        if (!happened && !work.completed) callbacks.add(callback);
      }
      if (happened) callback.run();
      return () -> { synchronized (lock) { callbacks.remove(callback); } };
    }

    @Override
    public void close() {
      List<Runnable> callbacks = List.of();
      synchronized (lock) {
        if (closed) return;
        closed = true;
        if (--work.references == 0) {
          work.completed = true;
          active.remove(work.initial.workId().orElseThrow());
          releaseReservation(work.bucket, work.observation);
          if (work.inference) {
            inferenceCount--;
            inferenceCounts.compute(work.bucket, (key, count) -> count == 1 ? null : count - 1);
          }
          lock.notifyAll();
          callbacks = new ArrayList<>(work.completion);
          work.completion.clear();
          work.cancellation.clear();
          work.background.clear();
        }
      }
      notifyCallbacks(callbacks);
    }
  }

  private enum Event { CANCEL, BACKGROUND, COMPLETE }
}

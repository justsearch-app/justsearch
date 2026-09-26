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
  private final OperationLeaseService leases;
  private final ReentrantReadWriteLock publication;
  private final Map<UUID, Work> active = new LinkedHashMap<>();
  private final Map<Bucket, Integer> counts = new HashMap<>();
  private String preparationId;
  private boolean closing;

  /** Explicit standalone-launcher provider; the full Engine root supplies its already-loaded policy. */
  public EngineAdmissionController() {
    this(EngineResourcePolicy.load());
  }

  EngineAdmissionController(EngineResourcePolicy policy) {
    this(policy, new ReentrantReadWriteLock());
  }

  EngineAdmissionController(EngineResourcePolicy policy, ReentrantReadWriteLock publication) {
    this(policy.execution().get("perContextLimit"), policy.execution().get("aggregateLimit"),
        policy.execution().get("retryAfterSeconds"), new OperationLeaseServiceImpl(), publication);
  }

  /** Explicit policy seam for deterministic admission and transport acceptance tests. */
  public EngineAdmissionController(int perContextLimit, int aggregateLimit, int retryAfterSeconds) {
    this(perContextLimit, aggregateLimit, retryAfterSeconds, OperationLeaseServiceImpl.processLocal(),
        new ReentrantReadWriteLock());
  }

  private EngineAdmissionController(int perContextLimit, int aggregateLimit, int retryAfterSeconds,
      OperationLeaseService leases, ReentrantReadWriteLock publication) {
    if (perContextLimit < 1 || aggregateLimit < 1 || retryAfterSeconds < 1) {
      throw new IllegalArgumentException("Engine admission limits must be positive");
    }
    this.perContextLimit = perContextLimit;
    this.aggregateLimit = aggregateLimit;
    this.retryAfterSeconds = retryAfterSeconds;
    this.leases = Objects.requireNonNull(leases, "leases");
    this.publication = Objects.requireNonNull(publication, "publication");
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
    Objects.requireNonNull(context, "context");
    if (context.workId().isPresent()) throw new IllegalArgumentException("Work is already attached");
    synchronized (lock) {
      if (closing) refuse(EngineAdmissionException.Reason.FROZEN);
      if (preparationId != null && !allowWhileFrozen) refuse(EngineAdmissionException.Reason.FROZEN);
      Bucket bucket = Bucket.of(context);
      if (active.size() >= aggregateLimit) refuse(EngineAdmissionException.Reason.ENGINE_LIMIT);
      if (counts.getOrDefault(bucket, 0) >= perContextLimit) {
        refuse(EngineAdmissionException.Reason.CONTEXT_LIMIT);
      }
      UUID id = UUID.randomUUID();
      Work work = new Work(context.withWorkId(id), bucket);
      active.put(id, work);
      counts.merge(bucket, 1, Integer::sum);
      return new Reference(work, work.initial);
    }
  }

  @Override
  public EngineWorkHandle attach(EngineContext context) {
    Objects.requireNonNull(context, "context");
    if (context.workId().isEmpty()) return admit(context, false);
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
  public void beginClosing() {
    publication.writeLock().lock();
    try {
      synchronized (lock) { closing = true; }
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
    }
  }

  private void releaseFreeze(String id) {
    synchronized (lock) {
      if (preparationId == null) return;
      if (!preparationId.equals(id)) throw new IllegalArgumentException("Admission freeze has another owner");
      preparationId = null;
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
    synchronized (lock) { return active.size(); }
  }

  @Override
  public boolean awaitDrained(java.time.Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    long budget = timeout.toNanos();
    if (budget < 0) throw new IllegalArgumentException("Drain timeout must be nonnegative");
    long deadline = System.nanoTime() + budget;
    synchronized (lock) {
      if (!closing) throw new IllegalStateException("Drain requires process closing");
      while (!active.isEmpty()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try {
          java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, remaining);
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

  private static final class Work {
    private final EngineContext initial;
    private final Bucket bucket;
    private final Set<Runnable> cancellation = new LinkedHashSet<>();
    private final Set<Runnable> background = new LinkedHashSet<>();
    private final Set<Runnable> completion = new LinkedHashSet<>();
    private int references = 1;
    private String cancelReason;
    private boolean detached;
    private boolean completed;

    private Work(EngineContext initial, Bucket bucket) {
      this.initial = initial;
      this.bucket = bucket;
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
          counts.compute(work.bucket, (key, count) -> count == 1 ? null : count - 1);
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

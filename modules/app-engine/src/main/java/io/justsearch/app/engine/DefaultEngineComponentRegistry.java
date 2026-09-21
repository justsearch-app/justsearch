/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.context.RetainedStateBudget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Default process-owned implementation of the Engine component registry. */
public final class DefaultEngineComponentRegistry implements EngineComponentRegistry {
  static final String ATTEMPTED_CONFIGURATIONS = "attempted-configurations";
  private static final System.Logger LOG =
      System.getLogger(DefaultEngineComponentRegistry.class.getName());

  private final Object monitor = new Object();
  private final Map<String, DefaultComponentHandle> components = new LinkedHashMap<>();
  private final List<ListenerSubscription> listeners = new ArrayList<>();
  private final ReentrantLock applyLock = new ReentrantLock();
  private final RetainedStateBudget retainedState;
  private long revision;
  private ApplyLeaseImpl outstandingLease;
  private boolean closed;

  public DefaultEngineComponentRegistry(RetainedStateBudget retainedState) {
    this.retainedState = Objects.requireNonNull(retainedState, "retainedState");
    retainedState.activate(ATTEMPTED_CONFIGURATIONS);
  }

  @Override
  public ComponentHandle register(ComponentSpec spec) {
    Objects.requireNonNull(spec, "spec");
    EngineComponentSnapshot published;
    List<Consumer<EngineComponentSnapshot>> observers;
    DefaultComponentHandle handle;
    synchronized (monitor) {
      ensureOpen();
      if (components.containsKey(spec.name())) {
        throw new IllegalArgumentException("Component name is already registered: " + spec.name());
      }
      handle = new DefaultComponentHandle(spec, Instant.now(), System.nanoTime());
      components.put(spec.name(), handle);
      revision++;
      published = snapshotLocked();
      observers = listenersLocked();
    }
    publish(observers, published);
    return handle;
  }

  @Override
  public EngineComponentSnapshot snapshot() {
    synchronized (monitor) {
      return snapshotLocked();
    }
  }

  @Override
  public Subscription subscribe(Consumer<EngineComponentSnapshot> listener) {
    Objects.requireNonNull(listener, "listener");
    synchronized (monitor) {
      ensureOpen();
      var subscription = new ListenerSubscription(listener);
      listeners.add(subscription);
      return subscription;
    }
  }

  @Override
  public ApplyAttempt tryApply() {
    if (applyLock.isHeldByCurrentThread()) {
      return new ApplyAttempt.Refused(ApplyAttempt.Reason.REENTRANT);
    }
    synchronized (monitor) {
      if (closed) return new ApplyAttempt.Refused(ApplyAttempt.Reason.CLOSED);
    }
    if (!applyLock.tryLock()) return new ApplyAttempt.Refused(ApplyAttempt.Reason.BUSY);

    RetainedStateBudget.Permit permit = null;
    try {
      synchronized (monitor) {
        if (closed) return unlockAndRefuse(ApplyAttempt.Reason.CLOSED);
        var acquired = retainedState.tryAcquire(ATTEMPTED_CONFIGURATIONS);
        if (acquired.isEmpty()) return unlockAndRefuse(ApplyAttempt.Reason.BUSY);
        permit = acquired.orElseThrow();
        outstandingLease = new ApplyLeaseImpl(Thread.currentThread(), permit);
        return new ApplyAttempt.Acquired(outstandingLease);
      }
    } catch (RuntimeException | Error failure) {
      if (permit != null) permit.close();
      applyLock.unlock();
      throw failure;
    }
  }

  private ApplyAttempt unlockAndRefuse(ApplyAttempt.Reason reason) {
    applyLock.unlock();
    return new ApplyAttempt.Refused(reason);
  }

  @Override
  public void close() {
    synchronized (monitor) {
      if (closed) return;
      if (outstandingLease != null) {
        throw new IllegalStateException(
            "Cannot close component registry while apply lease is held");
      }
      closed = true;
      listeners.forEach(ListenerSubscription::closeLocked);
      listeners.clear();
    }
  }

  private void mutate(DefaultComponentHandle handle, Mutation mutation) {
    mutate(handle, null, mutation);
  }

  private boolean mutate(DefaultComponentHandle handle,
      EngineComponentSnapshot.Component expected, Mutation mutation) {
    return mutate(handle, expected, null, mutation);
  }

  private boolean mutate(DefaultComponentHandle handle,
      EngineComponentSnapshot.Component expected, EngineComponentSnapshot expectedRegistry,
      Mutation mutation) {
    EngineComponentSnapshot published;
    List<Consumer<EngineComponentSnapshot>> observers;
    synchronized (monitor) {
      ensureOpen();
      if (expectedRegistry != null && !expectedRegistry.equals(snapshotLocked())) return false;
      if (expected != null && !expected.equals(handle.snapshotLocked())) return false;
      if (!mutation.apply(handle)) return true;
      revision++;
      published = snapshotLocked();
      observers = listenersLocked();
    }
    publish(observers, published);
    return true;
  }

  private EngineComponentSnapshot snapshotLocked() {
    return new EngineComponentSnapshot(revision, components.values().stream()
        .map(DefaultComponentHandle::snapshotLocked)
        .sorted(Comparator.comparing(row -> row.spec().name()))
        .toList());
  }

  private List<Consumer<EngineComponentSnapshot>> listenersLocked() {
    return listeners.stream().filter(subscription -> !subscription.closed)
        .map(subscription -> subscription.listener).toList();
  }

  private static void publish(List<Consumer<EngineComponentSnapshot>> observers,
      EngineComponentSnapshot snapshot) {
    for (var observer : observers) {
      try {
        observer.accept(snapshot);
      } catch (RuntimeException failure) {
        LOG.log(System.Logger.Level.WARNING, "Engine component listener failed", failure);
      }
    }
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Engine component registry is closed");
  }

  private static String optionalNonBlank(String value, String field) {
    if (value != null && value.isBlank()) {
      throw new IllegalArgumentException(field + " must be null or non-blank");
    }
    return value;
  }

  @FunctionalInterface
  private interface Mutation {
    boolean apply(DefaultComponentHandle handle);
  }

  private final class DefaultComponentHandle implements ComponentHandle {
    private final ComponentSpec spec;
    private ComponentState state = ComponentState.ABSENT;
    private String reasonCode;
    private Instant stateSince;
    private long stateSinceMonotonicNanos;
    private String appliedVersion;
    private String desiredVersion;
    private ComposeEvidence lastCompose;
    private int recoveryAttempts;
    private String evidence;

    private DefaultComponentHandle(ComponentSpec spec, Instant stateSince,
        long stateSinceMonotonicNanos) {
      this.spec = spec;
      this.stateSince = stateSince;
      this.stateSinceMonotonicNanos = stateSinceMonotonicNanos;
    }

    @Override
    public ComponentSpec spec() {
      return spec;
    }

    @Override
    public EngineComponentSnapshot.Component snapshot() {
      synchronized (monitor) {
        return snapshotLocked();
      }
    }

    @Override
    public void transition(ComponentState next, String nextReasonCode, String nextEvidence) {
      transition(null, null, next, nextReasonCode, nextEvidence);
    }

    @Override
    public boolean transitionIfUnchanged(EngineComponentSnapshot.Component expected,
        ComponentState next, String nextReasonCode, String nextEvidence) {
      Objects.requireNonNull(expected, "expected");
      return transition(expected, null, next, nextReasonCode, nextEvidence);
    }

    @Override
    public boolean transitionIfUnchanged(EngineComponentSnapshot expected,
        ComponentState next, String nextReasonCode, String nextEvidence) {
      Objects.requireNonNull(expected, "expected");
      return transition(null, expected, next, nextReasonCode, nextEvidence);
    }

    private boolean transition(EngineComponentSnapshot.Component expected,
        EngineComponentSnapshot expectedRegistry,
        ComponentState next, String nextReasonCode, String nextEvidence) {
      Objects.requireNonNull(next, "state");
      optionalNonBlank(nextReasonCode, "reasonCode");
      optionalNonBlank(nextEvidence, "evidence");
      return mutate(this, expected, expectedRegistry, handle -> {
        boolean stateChanged = handle.state != next;
        if (!stateChanged && Objects.equals(handle.reasonCode, nextReasonCode)
            && Objects.equals(handle.evidence, nextEvidence)) return false;
        handle.state = next;
        handle.reasonCode = nextReasonCode;
        handle.evidence = nextEvidence;
        if (stateChanged) {
          handle.stateSince = Instant.now();
          handle.stateSinceMonotonicNanos = System.nanoTime();
        }
        return true;
      });
    }

    @Override
    public void setAppliedVersion(String digest) {
      optionalNonBlank(digest, "appliedVersion");
      mutate(this, handle -> {
        if (Objects.equals(handle.appliedVersion, digest)) return false;
        handle.appliedVersion = digest;
        return true;
      });
    }

    @Override
    public void setDesiredVersion(String digest) {
      optionalNonBlank(digest, "desiredVersion");
      mutate(this, handle -> {
        if (Objects.equals(handle.desiredVersion, digest)) return false;
        handle.desiredVersion = digest;
        return true;
      });
    }

    @Override
    public void setLastCompose(ComposeEvidence next) {
      mutate(this, handle -> {
        if (Objects.equals(handle.lastCompose, next)) return false;
        handle.lastCompose = next;
        return true;
      });
    }

    @Override
    public void recordRecoveryAttempt(String nextEvidence) {
      optionalNonBlank(nextEvidence, "evidence");
      mutate(this, handle -> {
        handle.recoveryAttempts++;
        handle.evidence = nextEvidence;
        return true;
      });
    }

    private EngineComponentSnapshot.Component snapshotLocked() {
      return new EngineComponentSnapshot.Component(spec, state, reasonCode, stateSince,
          stateSinceMonotonicNanos, appliedVersion, desiredVersion, lastCompose,
          recoveryAttempts, evidence);
    }
  }

  private final class ListenerSubscription implements Subscription {
    private final Consumer<EngineComponentSnapshot> listener;
    private boolean closed;

    private ListenerSubscription(Consumer<EngineComponentSnapshot> listener) {
      this.listener = listener;
    }

    @Override
    public void close() {
      synchronized (monitor) {
        if (closed) return;
        closeLocked();
        listeners.remove(this);
      }
    }

    private void closeLocked() {
      closed = true;
    }
  }

  private final class ApplyLeaseImpl implements ApplyLease {
    private final Thread owner;
    private final RetainedStateBudget.Permit permit;
    private boolean released;

    private ApplyLeaseImpl(Thread owner, RetainedStateBudget.Permit permit) {
      this.owner = owner;
      this.permit = permit;
    }

    @Override
    public void close() {
      if (Thread.currentThread() != owner) {
        throw new IllegalStateException("Apply lease must be closed by its owner thread");
      }
      synchronized (monitor) {
        if (released) return;
        released = true;
        outstandingLease = null;
        permit.close();
        applyLock.unlock();
      }
    }
  }
}

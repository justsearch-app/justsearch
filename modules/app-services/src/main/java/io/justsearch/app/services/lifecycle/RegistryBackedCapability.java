/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only {@link Capability} projection of one component in the process registry.
 *
 * <p>The registry remains the lifecycle authority. Every query reads a current immutable registry
 * snapshot, while each subscription retains only enough projected bookkeeping to order changes and
 * suppress revisions that do not alter the capability observation.
 */
public final class RegistryBackedCapability implements Capability {
  private static final Logger log = LoggerFactory.getLogger(RegistryBackedCapability.class);

  private final EngineComponentRegistry registry;
  private final String componentName;
  private final String legacyName;

  public RegistryBackedCapability(
      EngineComponentRegistry registry, String componentName, String legacyName) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.componentName = requireName(componentName, "componentName");
    this.legacyName = requireName(legacyName, "legacyName");
    project(registry.snapshot(), true);
  }

  /** Returns the current projection directly from the registry authority. */
  public Observation current() {
    return project(registry.snapshot(), false);
  }

  @Override
  public CapabilityHealth health() {
    return current().health();
  }

  @Override
  public String pendingReason() {
    return current().reason();
  }

  @Override
  public String pendingDetail() {
    return current().detail();
  }

  @Override
  public boolean required() {
    return current().required();
  }

  @Override
  public String name() {
    return legacyName;
  }

  /**
   * Observes capability changes without manufacturing an event for the initial snapshot.
   *
   * <p>Registration precedes the bootstrap read. A callback racing that read is replayed only when
   * its revision is newer than the snapshot returned by the read. This is a current-state
   * projection, not a lossless event log: a stalled subscriber retains only the newest pending
   * observation. Listener calls are serialized per subscription and occur outside registry and
   * projection-bookkeeping locks.
   */
  public Subscription subscribe(Consumer<Change> listener) {
    SubscriptionState state = new SubscriptionState(Objects.requireNonNull(listener, "listener"));
    EngineComponentRegistry.Subscription upstream = registry.subscribe(state::accept);
    state.attach(upstream);
    try {
      state.bootstrap(registry.snapshot());
      return state;
    } catch (RuntimeException | Error failure) {
      try {
        state.close();
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** Immutable capability view projected from one registry revision. */
  public record Observation(
      long revision,
      CapabilityHealth health,
      String reason,
      String detail,
      boolean required,
      String name) {
    public Observation {
      if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
      Objects.requireNonNull(health, "health");
      Objects.requireNonNull(name, "name");
    }
  }

  /** One effective capability change. */
  public record Change(Observation previous, Observation current) {
    public Change {
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(current, "current");
    }
  }

  /** Closeable ownership of one adapter listener. */
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  private Observation project(EngineComponentSnapshot snapshot, boolean construction) {
    EngineComponentSnapshot.Component component = snapshot.components().stream()
        .filter(candidate -> candidate.spec().name().equals(componentName))
        .findFirst()
        .orElseThrow(() -> construction
            ? new IllegalArgumentException("Component is not registered: " + componentName)
            : new IllegalStateException("Registered component disappeared: " + componentName));
    CapabilityHealth health = healthOf(component.state());
    boolean ready = component.state() == ComponentState.READY;
    return new Observation(
        snapshot.revision(),
        health,
        ready ? null : component.reasonCode(),
        ready ? null : component.evidence(),
        component.spec().essential() || component.state() != ComponentState.ABSENT,
        legacyName);
  }

  /** Maps the registry's component state to the legacy read-only capability vocabulary. */
  public static CapabilityHealth healthOf(ComponentState state) {
    return switch (state) {
      case ABSENT, UNAVAILABLE -> CapabilityHealth.OFFLINE;
      case STARTING -> CapabilityHealth.PENDING;
      case READY -> CapabilityHealth.READY;
      case RELOADING -> CapabilityHealth.RECOVERING;
      case FAILED -> CapabilityHealth.DEGRADED;
    };
  }

  private static boolean sameProjection(Observation left, Observation right) {
    return left.health() == right.health()
        && Objects.equals(left.reason(), right.reason())
        && Objects.equals(left.detail(), right.detail())
        && left.required() == right.required()
        && left.name().equals(right.name());
  }

  private static String requireName(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    return value;
  }

  private final class SubscriptionState implements Subscription {
    private final Object monitor = new Object();
    private final Consumer<Change> listener;
    private EngineComponentRegistry.Subscription upstream;
    private EngineComponentSnapshot bootCallback;
    private Observation delivered;
    private Observation inFlight;
    private Observation pending;
    private long lastRevision = -1;
    private boolean bootstrapped;
    private boolean draining;
    private boolean closed;

    private SubscriptionState(Consumer<Change> listener) {
      this.listener = listener;
    }

    private void attach(EngineComponentRegistry.Subscription upstream) {
      synchronized (monitor) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
      }
    }

    private void bootstrap(EngineComponentSnapshot initial) {
      boolean shouldDrain;
      synchronized (monitor) {
        if (closed) return;
        delivered = project(initial, false);
        lastRevision = initial.revision();
        bootstrapped = true;
        if (bootCallback != null) {
          processLocked(bootCallback);
        }
        bootCallback = null;
        shouldDrain = startDrainLocked();
      }
      if (shouldDrain) drain();
    }

    private void accept(EngineComponentSnapshot snapshot) {
      boolean shouldDrain;
      synchronized (monitor) {
        if (closed) return;
        if (!bootstrapped) {
          if (bootCallback == null || snapshot.revision() > bootCallback.revision()) {
            bootCallback = snapshot;
          }
          return;
        }
        processLocked(snapshot);
        shouldDrain = startDrainLocked();
      }
      if (shouldDrain) drain();
    }

    private void processLocked(EngineComponentSnapshot snapshot) {
      if (snapshot.revision() <= lastRevision) return;
      Observation next = project(snapshot, false);
      lastRevision = snapshot.revision();
      Observation deliveryBase = inFlight != null ? inFlight : delivered;
      pending = sameProjection(deliveryBase, next) ? null : next;
    }

    private boolean startDrainLocked() {
      if (draining || pending == null) return false;
      draining = true;
      return true;
    }

    private void drain() {
      while (true) {
        Change change;
        synchronized (monitor) {
          if (closed) {
            pending = null;
            inFlight = null;
            draining = false;
            return;
          }
          if (pending == null) {
            inFlight = null;
            draining = false;
            return;
          }
          inFlight = pending;
          pending = null;
          change = new Change(delivered, inFlight);
        }
        try {
          listener.accept(change);
        } catch (RuntimeException failure) {
          log.warn("Capability projection listener failed for {}", componentName, failure);
        } catch (Error failure) {
          synchronized (monitor) {
            pending = null;
            inFlight = null;
            draining = false;
          }
          throw failure;
        }
        synchronized (monitor) {
          delivered = inFlight;
          inFlight = null;
        }
      }
    }

    @Override
    public void close() {
      EngineComponentRegistry.Subscription owned;
      synchronized (monitor) {
        if (closed) return;
        closed = true;
        bootCallback = null;
        pending = null;
        owned = upstream;
        upstream = null;
      }
      if (owned != null) owned.close();
    }
  }
}

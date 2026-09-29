/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Mutable in-memory component observations for consumer tests.
 *
 * <p>This fixture is not production registry, concurrency, configuration-apply, or boot-wiring
 * proof. In particular, {@link #fourComponents()} only creates test registrations; it does not
 * prove that a production root registers those components.
 */
public final class TestEngineComponents implements EngineComponentRegistry {
  private static final System.Logger LOG =
      System.getLogger(TestEngineComponents.class.getName());

  private final Object monitor = new Object();
  private final Map<String, TestComponentHandle> components = new LinkedHashMap<>();
  private final List<TestSubscription> subscriptions = new ArrayList<>();
  private long revision;
  private boolean closed;

  /** Creates the standard four test registrations, all initially {@link ComponentState#ABSENT}. */
  public static TestEngineComponents fourComponents() {
    var registry = new TestEngineComponents();
    registry.register(spec("api", true));
    registry.register(spec("index", true));
    registry.register(spec("encoders", false));
    registry.register(spec("generative", false));
    return registry;
  }

  private static ComponentSpec spec(String name, boolean essential) {
    return new ComponentSpec(
        name,
        essential,
        Set.of(),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY,
        Duration.ZERO,
        2);
  }

  /** Returns a previously registered test handle. */
  public ComponentHandle handle(String name) {
    Objects.requireNonNull(name, "name");
    synchronized (monitor) {
      TestComponentHandle handle = components.get(name);
      if (handle == null) {
        throw new IllegalArgumentException("No test component is registered: " + name);
      }
      return handle;
    }
  }

  @Override
  public ComponentHandle register(ComponentSpec spec) {
    Objects.requireNonNull(spec, "spec");
    EngineComponentSnapshot published;
    List<Consumer<EngineComponentSnapshot>> listeners;
    TestComponentHandle handle;
    synchronized (monitor) {
      ensureOpen();
      if (components.containsKey(spec.name())) {
        throw new IllegalArgumentException("Component name is already registered: " + spec.name());
      }
      handle = new TestComponentHandle(spec, Instant.now(), System.nanoTime());
      components.put(spec.name(), handle);
      revision++;
      published = snapshotLocked();
      listeners = listenersLocked();
    }
    publish(listeners, published);
    return handle;
  }

  @Override
  public EngineComponentSnapshot snapshot() {
    synchronized (monitor) {
      return snapshotLocked();
    }
  }

  @Override
  public PreparedBatch prepareBatch(
      Map<String, EngineComponentSnapshot.Component> replacements) {
    Objects.requireNonNull(replacements, "replacements");
    synchronized (monitor) {
      ensureOpen();
      var updates = new ArrayList<PreparedUpdate>(replacements.size());
      for (var entry : Map.copyOf(replacements).entrySet()) {
        String name = Objects.requireNonNull(entry.getKey(), "replacement name");
        var desired = Objects.requireNonNull(entry.getValue(), "replacement for " + name);
        var handle = components.get(name);
        if (handle == null) {
          throw new IllegalArgumentException("Component is not registered: " + name);
        }
        if (!handle.spec.equals(desired.spec())) {
          throw new IllegalArgumentException("Replacement spec does not match component: " + name);
        }
        var before = handle.snapshotLocked();
        updates.add(new PreparedUpdate(handle, before,
            withPublicationStateSince(before, desired)));
      }
      boolean changed = updates.stream().anyMatch(update -> !update.before.equals(update.desired));
      long nextRevision = changed ? revision + 1 : revision;
      var byName = new LinkedHashMap<String, EngineComponentSnapshot.Component>();
      for (var update : updates) byName.put(update.handle.spec.name(), update.desired);
      var rows = components.values().stream()
          .map(handle -> byName.getOrDefault(handle.spec.name(), handle.snapshotLocked()))
          .sorted(Comparator.comparing(row -> row.spec().name()))
          .toList();
      var prepared = new EngineComponentSnapshot(nextRevision, rows);
      var observers = changed ? listenersLocked() : List.<Consumer<EngineComponentSnapshot>>of();
      return new TestPreparedBatch(revision, updates, prepared, observers, changed);
    }
  }

  private static EngineComponentSnapshot.Component withPublicationStateSince(
      EngineComponentSnapshot.Component before, EngineComponentSnapshot.Component desired) {
    boolean changed = before.state() != desired.state();
    return new EngineComponentSnapshot.Component(
        desired.spec(), desired.state(), desired.reasonCode(),
        changed ? Instant.now() : before.stateSince(),
        changed ? System.nanoTime() : before.stateSinceMonotonicNanos(),
        desired.appliedVersion(), desired.desiredVersion(), desired.lastCompose(),
        desired.recoveryAttempts(), desired.evidence());
  }

  @Override
  public Subscription subscribe(Consumer<EngineComponentSnapshot> listener) {
    Objects.requireNonNull(listener, "listener");
    synchronized (monitor) {
      ensureOpen();
      var subscription = new TestSubscription(listener);
      subscriptions.add(subscription);
      return subscription;
    }
  }

  @Override
  public ApplyAttempt tryApply() {
    throw new UnsupportedOperationException(
        "Use the production registry for configuration-apply accounting "
            + "and concurrency assertions");
  }

  @Override
  public void close() {
    synchronized (monitor) {
      if (closed) return;
      closed = true;
      subscriptions.forEach(TestSubscription::closeLocked);
      subscriptions.clear();
    }
  }

  private void mutate(TestComponentHandle handle, Mutation mutation) {
    mutate(handle, null, null, mutation);
  }

  private boolean mutate(
      TestComponentHandle handle,
      EngineComponentSnapshot.Component expected,
      EngineComponentSnapshot expectedRegistry,
      Mutation mutation) {
    return mutateAndCapture(handle, expected, expectedRegistry, mutation).isPresent();
  }

  /** Captures the winning row before test listeners receive the publication. */
  private Optional<EngineComponentSnapshot.Component> mutateAndCapture(
      TestComponentHandle handle,
      EngineComponentSnapshot.Component expected,
      EngineComponentSnapshot expectedRegistry,
      Mutation mutation) {
    EngineComponentSnapshot published;
    List<Consumer<EngineComponentSnapshot>> listeners;
    EngineComponentSnapshot.Component exact;
    synchronized (monitor) {
      ensureOpen();
      if (expectedRegistry != null && !expectedRegistry.equals(snapshotLocked())) {
        return Optional.empty();
      }
      if (expected != null && !expected.equals(handle.snapshotLocked())) {
        return Optional.empty();
      }
      if (!mutation.apply(handle)) return Optional.of(handle.snapshotLocked());
      revision++;
      published = snapshotLocked();
      listeners = listenersLocked();
      exact = handle.snapshotLocked();
    }
    publish(listeners, published);
    return Optional.of(exact);
  }

  private EngineComponentSnapshot snapshotLocked() {
    return new EngineComponentSnapshot(
        revision,
        components.values().stream()
            .map(TestComponentHandle::snapshotLocked)
            .sorted(Comparator.comparing(component -> component.spec().name()))
            .toList());
  }

  private List<Consumer<EngineComponentSnapshot>> listenersLocked() {
    return subscriptions.stream()
        .filter(subscription -> !subscription.closed)
        .map(subscription -> subscription.listener)
        .toList();
  }

  private static void publish(
      List<Consumer<EngineComponentSnapshot>> listeners, EngineComponentSnapshot snapshot) {
    for (Consumer<EngineComponentSnapshot> listener : listeners) {
      try {
        listener.accept(snapshot);
      } catch (RuntimeException failure) {
        LOG.log(System.Logger.Level.WARNING, "Test component listener failed", failure);
      }
    }
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Test component registry is closed");
  }

  private static String optionalNonBlank(String value, String field) {
    if (value != null && value.isBlank()) {
      throw new IllegalArgumentException(field + " must be null or non-blank");
    }
    return value;
  }

  @FunctionalInterface
  private interface Mutation {
    boolean apply(TestComponentHandle handle);
  }

  private record PreparedUpdate(TestComponentHandle handle,
      EngineComponentSnapshot.Component before,
      EngineComponentSnapshot.Component desired) {}

  private final class TestPreparedBatch implements PreparedBatch {
    private final long baseRevision;
    private final List<PreparedUpdate> updates;
    private final EngineComponentSnapshot preparedSnapshot;
    private final List<Consumer<EngineComponentSnapshot>> observers;
    private final boolean changed;
    private boolean installed;
    private boolean notified;

    private TestPreparedBatch(long baseRevision, List<PreparedUpdate> updates,
        EngineComponentSnapshot preparedSnapshot,
        List<Consumer<EngineComponentSnapshot>> observers, boolean changed) {
      this.baseRevision = baseRevision;
      this.updates = List.copyOf(updates);
      this.preparedSnapshot = preparedSnapshot;
      this.observers = List.copyOf(observers);
      this.changed = changed;
    }

    @Override public EngineComponentSnapshot snapshot() { return preparedSnapshot; }

    @Override
    public void validate() {
      synchronized (monitor) {
        ensureOpen();
        if (installed || revision != baseRevision
            || updates.stream().anyMatch(update ->
                !update.before.equals(update.handle.snapshotLocked()))) {
          throw new IllegalStateException("prepared test component batch is stale");
        }
      }
    }

    @Override
    public void install() {
      synchronized (monitor) {
        ensureOpen();
        if (installed || revision != baseRevision
            || updates.stream().anyMatch(update ->
                !update.before.equals(update.handle.snapshotLocked()))) {
          throw new IllegalStateException("prepared test component batch is stale");
        }
        if (changed) {
          updates.forEach(update -> update.handle.installPrepared(update.desired));
          revision = preparedSnapshot.revision();
        }
        installed = true;
      }
    }

    @Override
    public void notifyObservers() {
      synchronized (monitor) {
        if (!installed || notified) {
          throw new IllegalStateException(
              "prepared test component observers require one installed batch");
        }
        notified = true;
      }
      publish(observers, preparedSnapshot);
    }

    @Override
    public void commit() {
      validate();
      install();
      notifyObservers();
    }
  }

  private final class TestComponentHandle implements ComponentHandle {
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

    private TestComponentHandle(
        ComponentSpec spec, Instant stateSince, long stateSinceMonotonicNanos) {
      this.spec = spec;
      this.stateSince = stateSince;
      this.stateSinceMonotonicNanos = stateSinceMonotonicNanos;
    }

    @Override
    public ComponentSpec spec() {
      return spec;
    }

    @Override
    public PreparedBatch prepareReplacement(EngineComponentSnapshot.Component replacement) {
      return TestEngineComponents.this.prepareBatch(Map.of(spec.name(), replacement));
    }

    @Override
    public boolean belongsTo(EngineComponentRegistry registry) {
      return registry == TestEngineComponents.this;
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
    public boolean transitionIfUnchanged(
        EngineComponentSnapshot.Component expected,
        ComponentState next,
        String nextReasonCode,
        String nextEvidence) {
      return tryTransitionIfUnchanged(expected, next, nextReasonCode, nextEvidence).isPresent();
    }

    @Override
    public Optional<EngineComponentSnapshot.Component> tryTransitionIfUnchanged(
        EngineComponentSnapshot.Component expected,
        ComponentState next,
        String nextReasonCode,
        String nextEvidence) {
      Objects.requireNonNull(expected, "expected");
      return transitionExact(expected, null, next, nextReasonCode, nextEvidence);
    }

    @Override
    public boolean transitionIfUnchanged(
        EngineComponentSnapshot expected,
        ComponentState next,
        String nextReasonCode,
        String nextEvidence) {
      Objects.requireNonNull(expected, "expected");
      return transition(null, expected, next, nextReasonCode, nextEvidence);
    }

    private boolean transition(
        EngineComponentSnapshot.Component expected,
        EngineComponentSnapshot expectedRegistry,
        ComponentState next,
        String nextReasonCode,
        String nextEvidence) {
      return transitionExact(expected, expectedRegistry, next, nextReasonCode, nextEvidence)
          .isPresent();
    }

    private Optional<EngineComponentSnapshot.Component> transitionExact(
        EngineComponentSnapshot.Component expected,
        EngineComponentSnapshot expectedRegistry,
        ComponentState next,
        String nextReasonCode,
        String nextEvidence) {
      Objects.requireNonNull(next, "state");
      optionalNonBlank(nextReasonCode, "reasonCode");
      optionalNonBlank(nextEvidence, "evidence");
      return mutateAndCapture(
          this,
          expected,
          expectedRegistry,
          handle -> {
            boolean stateChanged = handle.state != next;
            if (!stateChanged
                && Objects.equals(handle.reasonCode, nextReasonCode)
                && Objects.equals(handle.evidence, nextEvidence)) {
              return false;
            }
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
      mutate(
          this,
          handle -> {
            if (Objects.equals(handle.appliedVersion, digest)) return false;
            handle.appliedVersion = digest;
            return true;
          });
    }

    @Override
    public Optional<EngineComponentSnapshot.Component> tryBeginRecovery(
        EngineComponentSnapshot.Component expected, String nextReasonCode, String nextEvidence) {
      Objects.requireNonNull(expected, "expected");
      optionalNonBlank(nextReasonCode, "reasonCode");
      optionalNonBlank(nextEvidence, "evidence");
      return mutateAndCapture(this, expected, null, handle -> {
        handle.state = ComponentState.STARTING;
        handle.stateSince = Instant.now();
        handle.stateSinceMonotonicNanos = System.nanoTime();
        handle.reasonCode = nextReasonCode;
        handle.evidence = nextEvidence;
        handle.recoveryAttempts++;
        return true;
      });
    }

    @Override
    public void setDesiredVersion(String digest) {
      optionalNonBlank(digest, "desiredVersion");
      mutate(
          this,
          handle -> {
            if (Objects.equals(handle.desiredVersion, digest)) return false;
            handle.desiredVersion = digest;
            return true;
          });
    }

    @Override
    public void setLastCompose(ComposeEvidence next) {
      mutate(
          this,
          handle -> {
            if (Objects.equals(handle.lastCompose, next)) return false;
            handle.lastCompose = next;
            return true;
          });
    }

    @Override
    public void recordRecoveryAttempt(String nextEvidence) {
      optionalNonBlank(nextEvidence, "evidence");
      mutate(
          this,
          handle -> {
            handle.recoveryAttempts++;
            if (nextEvidence != null) handle.evidence = nextEvidence;
            return true;
          });
    }

    private EngineComponentSnapshot.Component snapshotLocked() {
      return new EngineComponentSnapshot.Component(
          spec,
          state,
          reasonCode,
          stateSince,
          stateSinceMonotonicNanos,
          appliedVersion,
          desiredVersion,
          lastCompose,
          recoveryAttempts,
          evidence);
    }

    private void installPrepared(EngineComponentSnapshot.Component desired) {
      state = desired.state();
      reasonCode = desired.reasonCode();
      stateSince = desired.stateSince();
      stateSinceMonotonicNanos = desired.stateSinceMonotonicNanos();
      appliedVersion = desired.appliedVersion();
      desiredVersion = desired.desiredVersion();
      lastCompose = desired.lastCompose();
      recoveryAttempts = desired.recoveryAttempts();
      evidence = desired.evidence();
    }
  }

  private final class TestSubscription implements Subscription {
    private final Consumer<EngineComponentSnapshot> listener;
    private boolean closed;

    private TestSubscription(Consumer<EngineComponentSnapshot> listener) {
      this.listener = listener;
    }

    @Override
    public void close() {
      synchronized (monitor) {
        if (closed) return;
        closeLocked();
        subscriptions.remove(this);
      }
    }

    private void closeLocked() {
      closed = true;
    }
  }
}

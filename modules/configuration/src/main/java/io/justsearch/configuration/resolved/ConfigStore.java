/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import io.justsearch.observable.ObservableNotifier;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Thread-safe holder for the active {@link ResolvedConfig} snapshot.
 *
 * <p>Components access configuration via {@link #get()} which returns the current immutable
 * snapshot. When settings change at runtime (e.g., via the GUI), a new snapshot is built and
 * atomically swapped in via {@link #swap(ResolvedConfig)}, then registered listeners are notified
 * via {@link #notifyListeners(ConfigChangedEvent)}.
 *
 * <p>Snapshots remain immutable and are stored in an {@link AtomicReference}; the process-owned
 * publication lock additionally orders configuration reads with paired runtime-reference reads.
 *
 * <p>Example:
 * <pre>{@code
 * ConfigStore store = new ConfigStore(initialConfig);
 * store.addListener(event -> {
 *     if (event.keyChanged("justsearch.data.dir")) {
 *         log.info("Data dir changed, restart required");
 *     }
 * });
 * // Later, when settings change:
 * store.update(newConfig);
 * }</pre>
 */
public final class ConfigStore {

  private static volatile ConfigStore GLOBAL; // NOPMD - intentionally nullable

  /**
   * Sets the application-wide global ConfigStore instance.
   *
   * <p>Called once at startup by the Head process ({@code HeadlessApp}) and optionally by the Worker
   * process ({@code IndexerWorker}) after loading the config snapshot. Subsequent calls replace the
   * previous global (no exception).
   *
   * @param store the ConfigStore to publish globally (must not be null)
   */
  public static synchronized void setGlobal(ConfigStore store) {
    GLOBAL = Objects.requireNonNull(store, "store");
  }

  /** Restores a scoped publisher's predecessor, including uninitialized state, if it still owns the global. */
  public static synchronized void restoreGlobal(ConfigStore installed, ConfigStore previous) {
    if (GLOBAL == Objects.requireNonNull(installed, "installed")) GLOBAL = previous;
  }

  /**
   * Returns the application-wide global ConfigStore.
   *
   * @throws IllegalStateException if {@link #setGlobal} has not been called yet
   */
  public static ConfigStore global() {
    ConfigStore s = GLOBAL;
    if (s == null) {
      throw new IllegalStateException(
          "ConfigStore not initialized — call setGlobal() at startup");
    }
    return s;
  }

  /**
   * Returns the application-wide global ConfigStore, or {@code null} if not yet initialized.
   *
   * <p>Use this in code paths that may run before startup completes (e.g., static utility methods
   * called during early initialization).
   */
  public static ConfigStore globalOrNull() {
    return GLOBAL;
  }

  /**
   * Clears the global ConfigStore, returning to the uninitialized state.
   *
   * <p><b>Test-only.</b> Production code should never call this — it exists so test fixtures can
   * restore the pre-test state when no ConfigStore was set before the test ran.
   */
  @SuppressWarnings("unused") // Called from TestResolvedConfigHelper (test fixtures)
  static synchronized void clearGlobal() {
    GLOBAL = null;
  }

  private final AtomicReference<ResolvedConfig> current;
  private final ReentrantReadWriteLock publicationLock;
  // Tempdoc 518 Appendix F W4.1 — shared listener substrate.
  private final ObservableNotifier<ConfigChangedEvent> listeners =
      new ObservableNotifier<>("ConfigStore");

  /**
   * Creates a new ConfigStore with the given initial config snapshot.
   *
   * @param initial the initial resolved configuration (must not be null)
   */
  public ConfigStore(ResolvedConfig initial) {
    this(initial, new ReentrantReadWriteLock());
  }

  /**
   * Creates a store using the process publication lock shared by configuration and runtime
   * owners.
   *
   * <p>The lock is deliberately supplied by the composition root. It must be the same lock used
   * by every owner whose references are captured together with this configuration store.
   *
   * @param initial the initial resolved configuration (must not be null)
   * @param publicationLock the process-owned publication lock (must not be null)
   */
  public ConfigStore(ResolvedConfig initial, ReentrantReadWriteLock publicationLock) {
    this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial config"));
    this.publicationLock = Objects.requireNonNull(publicationLock, "publication lock");
  }

  /**
   * Returns the current config snapshot.
   *
   * <p>The returned snapshot is immutable and safe to use across threads. A subsequent call to
   * {@link #update} does not affect previously returned snapshots. The short read-lock section
   * orders this read with a paired runtime capture using the same publication lock.
   *
   * @return the current immutable config snapshot
   */
  public ResolvedConfig get() {
    publicationLock.readLock().lock();
    try {
      return current.get();
    } finally {
      publicationLock.readLock().unlock();
    }
  }

  /** Returns the process publication lock supplied at construction time. */
  public ReentrantReadWriteLock publicationLock() {
    return publicationLock;
  }

  /**
   * Atomically replaces the current config snapshot without invoking listeners.
   *
   * @param next the new config snapshot (must not be null)
   * @return the event describing the replaced snapshot
   */
  public ConfigChangedEvent swap(ResolvedConfig next) {
    Objects.requireNonNull(next, "next config");
    publicationLock.writeLock().lock();
    try {
      ResolvedConfig prev = current.get();
      // Construct the event before the new snapshot becomes visible. The prepared path below
      // uses the same ordering while allowing the outer coordinator to do this work earlier.
      ConfigChangedEvent event = new ConfigChangedEvent(prev, next);
      current.set(next);
      return event;
    } finally {
      publicationLock.writeLock().unlock();
    }
  }

  /**
   * Prepares a swap by capturing the exact predecessor and constructing its event before commit.
   *
   * <p>The returned value becomes stale if another writer publishes first. Callers must retain
   * the existing settings reservation while preparing and commit it through
   * {@link #commitPrepared(PreparedSwap)}.
   */
  public PreparedSwap prepareSwap(ResolvedConfig next) {
    Objects.requireNonNull(next, "next config");
    publicationLock.readLock().lock();
    try {
      ResolvedConfig previous = current.get();
      return new PreparedSwap(previous, next, new ConfigChangedEvent(previous, next));
    } finally {
      publicationLock.readLock().unlock();
    }
  }

  /**
   * Commits a previously prepared swap while holding the shared publication lock.
   *
   * @return the already constructed event; listeners are not invoked
   * @throws IllegalArgumentException when the prepared swap belongs to another store
   * @throws IllegalStateException when a different snapshot was published first
   */
  public ConfigChangedEvent commitPrepared(PreparedSwap prepared) {
    Objects.requireNonNull(prepared, "prepared swap");
    publicationLock.writeLock().lock();
    try {
      validatePrepared(prepared);
      installPrepared(prepared);
      return prepared.event;
    } finally {
      publicationLock.writeLock().unlock();
    }
  }

  /** Validates a prepared swap before the outer coordinator crosses its durable commit point. */
  public void validatePrepared(PreparedSwap prepared) {
    requireWriteLock();
    Objects.requireNonNull(prepared, "prepared swap");
    if (prepared.owner != this) {
      throw new IllegalArgumentException("prepared swap belongs to another ConfigStore");
    }
    if (prepared.installed.get()) {
      throw new IllegalStateException("prepared config swap was already installed");
    }
    if (current.get() != prepared.previous) {
      throw new IllegalStateException("prepared config swap is stale");
    }
  }

  /**
   * Installs a swap that was validated under the caller's held publication write lock.
   *
   * <p>This method performs only the prepared reference assignment. It deliberately does not
   * revalidate or allocate, so a coordinator can place it after durable file replacement.
   */
  public void installPrepared(PreparedSwap prepared) {
    requireWriteLock();
    // The coordinator must call validatePrepared while the same write lock is held before it
    // crosses its durable commit point. Keep this post-commit operation assignment-only.
    current.set(prepared.next);
    prepared.installed.set(true);
  }

  private void requireWriteLock() {
    if (!publicationLock.isWriteLockedByCurrentThread()) {
      throw new IllegalStateException("publication write lock must be held by the caller");
    }
  }

  /** Immutable, single-predecessor swap prepared by {@link #prepareSwap(ResolvedConfig)}. */
  public final class PreparedSwap {
    private final ConfigStore owner = ConfigStore.this;
    private final ResolvedConfig previous;
    private final ResolvedConfig next;
    private final ConfigChangedEvent event;
    private final AtomicBoolean installed = new AtomicBoolean();

    private PreparedSwap(ResolvedConfig previous, ResolvedConfig next, ConfigChangedEvent event) {
      this.previous = previous;
      this.next = next;
      this.event = event;
    }

    public ResolvedConfig previous() { return previous; }

    public ResolvedConfig next() { return next; }

    public ConfigChangedEvent event() { return event; }
  }

  /**
   * Notifies the registered listeners for a previously completed snapshot swap.
   *
   * <p>This method is deliberately separate from {@link #swap(ResolvedConfig)} so callers can
   * release their publication lock before arbitrary listener code runs.
   *
   * @param event the event returned by {@link #swap(ResolvedConfig)} (must not be null)
   */
  public void notifyListeners(ConfigChangedEvent event) {
    listeners.notifyAll(Objects.requireNonNull(event, "config change event"));
  }

  /**
   * Atomically replaces the current config snapshot and notifies listeners.
   *
   * <p>Convenience API retained for callers that do not need to separate publication from
   * notification.
   *
   * @param next the new config snapshot (must not be null)
   */
  public void update(ResolvedConfig next) {
    notifyListeners(swap(next));
  }

  /**
   * Registers a listener to be notified on config changes.
   *
   * @param listener the listener (must not be null)
   */
  public void addListener(Consumer<ConfigChangedEvent> listener) {
    listeners.register(Objects.requireNonNull(listener, "listener"));
  }

  /**
   * Removes a previously registered listener.
   *
   * @param listener the listener to remove
   */
  public void removeListener(Consumer<ConfigChangedEvent> listener) {
    listeners.unregister(listener);
  }
}

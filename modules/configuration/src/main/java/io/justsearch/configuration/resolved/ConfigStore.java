/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import io.justsearch.observable.ObservableNotifier;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thread-safe holder for the active {@link ResolvedConfig} snapshot.
 *
 * <p>Components access configuration via {@link #get()} which returns the current immutable
 * snapshot. When settings change at runtime (e.g., via the GUI), a new snapshot is built and
 * atomically swapped in via {@link #update(ResolvedConfig)}, and all registered listeners are
 * notified.
 *
 * <p>Follows the atomic snapshot replacement pattern (SEI CERT VNA01-J, Android LiveData):
 * {@link AtomicReference} provides thread-safe read access; settings changes build a new
 * immutable snapshot and swap it in.
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
  // Tempdoc 518 Appendix F W4.1 — shared listener substrate.
  private final ObservableNotifier<ConfigChangedEvent> listeners =
      new ObservableNotifier<>("ConfigStore");

  /**
   * Creates a new ConfigStore with the given initial config snapshot.
   *
   * @param initial the initial resolved configuration (must not be null)
   */
  public ConfigStore(ResolvedConfig initial) {
    this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial config"));
  }

  /**
   * Returns the current config snapshot.
   *
   * <p>This is a non-blocking read. The returned snapshot is immutable and safe to use across
   * threads. A subsequent call to {@link #update} does not affect previously returned snapshots.
   *
   * @return the current immutable config snapshot
   */
  public ResolvedConfig get() {
    return current.get();
  }

  /**
   * Atomically replaces the current config snapshot and notifies listeners.
   *
   * <p>Listeners are invoked synchronously on the calling thread. Listener exceptions are caught
   * and logged but do not prevent other listeners from being notified.
   *
   * @param next the new config snapshot (must not be null)
   */
  public void update(ResolvedConfig next) {
    Objects.requireNonNull(next, "next config");
    ResolvedConfig prev = current.getAndSet(next);
    listeners.notifyAll(new ConfigChangedEvent(prev, next));
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

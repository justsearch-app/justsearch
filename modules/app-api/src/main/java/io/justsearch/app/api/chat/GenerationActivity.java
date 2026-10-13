/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * The generation-in-flight signal: one counter that the indexing gate and the status surface both
 * read. A GENERATE lease opens at admission, before the request queues for the server, and closes
 * exactly once when the exchange ends. AUX leases keep a drain waiting but never count here.
 */
public interface GenerationActivity {

  /** True while at least one GENERATE lease is open. A volatile read, O(1). */
  boolean generating();

  /** Open GENERATE leases. */
  int inFlight();

  /** Admitted demands still waiting for a lease. */
  int queued();

  /** Monotonic time of the last generating-to-idle edge; {@link Long#MIN_VALUE} while generating. */
  long idleSinceNanos();

  /** Incremented on every idle-to-generating edge. */
  long epoch();

  /** Subscribes to edges, delivered in order and off the caller's thread. */
  AutoCloseable onEdge(EdgeListener listener);

  /**
   * Runs {@code task} once, off the caller's thread where the activity has a delivery executor, as
   * soon as no lease of any kind is open: at once when none is open now, else when the last one
   * closes. AUX and assist leases make no edge, so this is how a step an open lease refused learns
   * it may try again. An activity that does not track leases never runs it.
   */
  default void whenNoLeaseOpen(Runnable task) {
    java.util.Objects.requireNonNull(task, "task");
  }

  /** Receives each idle/generating edge. */
  @FunctionalInterface
  interface EdgeListener {
    void onEdge(boolean generating, long epoch);
  }
}

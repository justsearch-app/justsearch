/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.coordination;

import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.io.Closeable;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * The signals the index half reads from the rest of the Engine.
 *
 * <p><b>Lane F stage A item A10 narrowed this interface to what one JVM can actually answer.</b>
 * It began as a cross-process coordination surface: the memory-mapped bus carried a port to
 * discover, a heartbeat to watch, a shutdown byte and the suicide pact built on top of them. All
 * four existed only because the Head and the index half were two processes, and the wire client
 * stack that wrote them ({@code MainSignalBus}) and the implementation that read them
 * ({@code MmfWorkerSignalBus}) were deleted with this item. Inside one JVM "did the other half
 * die?" has no answer to poll for — if the Engine dies, the index half died with it — so the
 * members were removed rather than left returning a constant nothing produces.
 *
 * <p>What remains is scheduling advice, and it is genuinely intra-process: the GPU/energy gauge the
 * composition root shares with its writers, and the pending-ingest probe the indexing loop
 * publishes about itself. The dev hot-reload trigger is the one cross-process signal left, and it
 * is a file rather than a byte (see {@link InProcessWorkerSignalBus}).
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link InProcessWorkerSignalBus} - the live one: a reference to the process-wide gauge</li>
 *   <li>Per-test anonymous stubs - hermetic testing</li>
 * </ul>
 */
public interface WorkerSignalBus extends Closeable {

  /**
   * Opens the signal bus for read/write access.
   *
   * @throws IOException if the signal bus cannot be opened
   */
  void open() throws IOException;

  /**
   * Checks if the Main process is actively using the GPU (Online Mode).
   * Used to pause GPU-accelerated embeddings when Main has the GPU.
   *
   * <p>When this returns true, the Worker should skip GPU operations
   * to avoid VRAM conflicts on systems with limited GPU memory.
   *
   * @return true if Main process is using GPU (Online Mode active)
   */
  boolean isMainGpuActive();

  /**
   * Whether the OS is requesting reduced background work (energy saver engaged; tempdoc 630). Main
   * writes this from a polled {@code GetSystemPowerStatus}. Default {@code false} (conservative):
   * impls without an energy signal — and a host where the probe is unavailable — never throttle.
   *
   * @return true if the OS wants background work reduced to save power
   */
  default boolean isEnergyReduced() {
    return false;
  }

  /**
   * Whether the Worker should yield the GPU-heavy bulk backfill right now — either because Main has
   * claimed the GPU (Online Mode) OR because the OS wants reduced background work (tempdoc 630).
   * One concept, two reasons; the GPU-heavy backfill sites read this instead of {@link
   * #isMainGpuActive()} alone.
   *
   * <p>Lane F item A5: the rule itself now lives in {@link GpuSchedulingGauge}, the one in-process
   * holder both halves of the merged Engine write and read. This method is the read side the
   * GPU-heavy backfill sites already hold a bus for, so it stays and delegates — there is one rule
   * and one copy of it, which is the property item A10 had to preserve when it deleted the wire.
   *
   * @return true if GPU-heavy bulk backfill should be deferred
   */
  default boolean shouldYieldGpuBackfill() {
    return GpuSchedulingGauge.shouldYield(isMainGpuActive(), isEnergyReduced());
  }

  /**
   * Whether primary indexing (ingest) work is waiting in the job queue right now (tempdoc 798).
   *
   * <p>The second "yield to something more important" signal, alongside {@link
   * #shouldYieldGpuBackfill()}: background enrichment backfill must never starve primary
   * indexing. Backfill drains a finite population and can always resume next cycle; a queued
   * ingest job the user is waiting on cannot.
   *
   * <p>Unlike the other two this is an intra-process signal — the worker's own indexing loop owns
   * the job queue and publishes the probe via {@link #setPendingIngestProbe(BooleanSupplier)};
   * nothing crosses the memory-mapped region.
   *
   * <p>Default {@code false} (conservative, matching {@link #isEnergyReduced()}): an impl with no
   * probe registered never claims pending ingest, so backfill behaves as it did before.
   *
   * @return true if ingest jobs are waiting to be claimed
   */
  default boolean hasPendingIngest() {
    return false;
  }

  /**
   * Registers the probe that {@link #hasPendingIngest()} reads. Called by the component that owns
   * the job queue (the indexing loop). A {@code null} probe clears the registration.
   *
   * @param probe the pending-ingest probe, or null to clear
   */
  default void setPendingIngestProbe(BooleanSupplier probe) {
    // no-op by default
  }

  /**
   * Returns the startup time of this signal bus instance.
   *
   * @return Startup timestamp in epoch millis
   */
  long startupTime();

  /**
   * Checks if a dev hot-reload signal has been set.
   * Used by the sentinel thread to trigger classloader restart (tempdoc 305 Phase 2).
   *
   * @return true if reload is requested
   */
  default boolean isReloadRequested() {
    return false;
  }

  /**
   * Clears the reload signal after the worker has acknowledged it.
   */
  default void clearReloadSignal() {
    // no-op by default
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.coordination;

import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The {@link WorkerSignalBus} for an index half running inside the Engine JVM (lane F stage A item
 * A6).
 *
 * <p>Every signal the memory-mapped bus carried existed because the two halves were two processes.
 * Inside one JVM each one either collapses to a field or stops being a question at all:
 *
 * <ul>
 *   <li><b>{@code mainGpuActive} / {@code energyReduced}</b> — the only two that survive, and they
 *       read the {@link GpuSchedulingGauge} the composition root shares with the writers
 *       ({@code InferenceWiring} and {@code EnergyStatePoller}). No byte, no polling lag: the
 *       reader observes the writer's field.
 *   <li><b>heartbeat / shutdown / suicide pact</b> — the "did the Head die?" question. In one JVM
 *       the answer is always no: if the Engine dies, the index half died with it. {@link
 *       #shouldDie()} is therefore permanently {@code false}, and the sentinel thread's kill path
 *       has no producer. This is deliberate, not an omission — a process that killed itself
 *       because it could not find its own heartbeat would be the defect.
 *   <li><b>port publication</b> — {@link #writePort(int)} records the port for diagnostics and
 *       publishes it nowhere: there is no second process to discover it. Item A9 removes the
 *       caller.
 *   <li><b>reload signal</b> — {@code false} here; hot reload is re-homed at item A18 onto a
 *       trigger that does not need a shared memory region.
 * </ul>
 *
 * <p>Thread-safe: the gauge is, and the two fields this class owns are atomic/volatile.
 */
public final class InProcessWorkerSignalBus implements WorkerSignalBus {

  private final GpuSchedulingGauge gpuScheduling;
  private final long startupTime = System.currentTimeMillis();
  private final AtomicInteger boundPort = new AtomicInteger(0);
  private volatile BooleanSupplier pendingIngestProbe;

  /**
   * @param gpuScheduling the process-wide gauge — the same instance the Head-side writers hold,
   *     never a fresh one, or the worker reads a gauge nobody writes
   */
  public InProcessWorkerSignalBus(GpuSchedulingGauge gpuScheduling) {
    this.gpuScheduling = Objects.requireNonNull(gpuScheduling, "gpuScheduling");
  }

  @Override
  public void open() {
    // Nothing to map: the "bus" is a reference to a gauge in this JVM.
  }

  @Override
  public void writePort(int port) {
    boundPort.set(port);
  }

  /** The port the index half last bound, or 0. Diagnostic only; nothing discovers it. */
  public int boundPort() {
    return boundPort.get();
  }

  @Override
  public long readHeartbeat() {
    // The Engine is alive by construction while this method can be called at all.
    return System.currentTimeMillis();
  }

  @Override
  public boolean isShutdownRequested() {
    // Shutdown is an ordered in-process sequence owned by the composition root, not a byte.
    return false;
  }

  @Override
  public boolean shouldDie() {
    return false;
  }

  @Override
  public boolean isMainGpuActive() {
    return gpuScheduling.isMainGpuActive();
  }

  @Override
  public boolean isEnergyReduced() {
    return gpuScheduling.isEnergyReduced();
  }

  @Override
  public boolean hasPendingIngest() {
    BooleanSupplier probe = pendingIngestProbe;
    return probe != null && probe.getAsBoolean();
  }

  @Override
  public void setPendingIngestProbe(BooleanSupplier probe) {
    this.pendingIngestProbe = probe;
  }

  @Override
  public long startupTime() {
    return startupTime;
  }

  @Override
  public void close() {
    // Nothing owned.
  }
}

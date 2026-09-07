/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

/**
 * The one in-process gauge behind "should GPU-heavy background work yield right now?" (lane F stage
 * A item A5; design 4, "one scheduling policy for the GPU").
 *
 * <p>Two independent reasons to yield, one rule:
 *
 * <ul>
 *   <li><b>{@code mainGpuActive}</b> — the chat/inference runtime has claimed the GPU (Online Mode).
 *       Written when the inference lifecycle changes mode.
 *   <li><b>{@code energyReduced}</b> — the OS is asking for reduced background work (energy saver
 *       engaged; tempdoc 630). Written from a polled {@code GetSystemPowerStatus}.
 * </ul>
 *
 * <p><b>Why this type exists.</b> Both signals cross a process boundary today: the Head writes two
 * bytes of the memory-mapped signal file ({@code MainSignalBus.writeGpuActive} /
 * {@code writeEnergyReduced}) and the Worker reads them back through {@code WorkerSignalBus}. Lane F
 * merges the two processes, so the MMF slots go with the wire — the *quantity* the scheduler is
 * about is a pair of booleans in one JVM, not a shared file. This class is that pair, and it is
 * deliberately in {@code core}: {@code core} carries no project dependency at all, and both halves
 * already see it: {@code app-services} declares {@code api(project(":modules:core"))}
 * ({@code modules/app-services/build.gradle.kts:23}), and the worker half reaches it through
 * {@code worker-core}'s {@code api(project(":modules:adapters-lucene"))}
 * ({@code modules/worker-core/build.gradle.kts:9}), whose own {@code api(project(":modules:core"))}
 * ({@code modules/adapters-lucene/build.gradle.kts:13}) re-exports it. No new module edge is needed
 * in either direction, so nothing about the ring structure or ArchUnit rule 6b moves.
 *
 * <p><b>Transitional state (until item A10).</b> While the split process still exists the MMF stays
 * the transport: the writers set this gauge <em>and</em> the byte, and the Worker's
 * {@code MmfWorkerSignalBus} feeds its own gauge from the byte on every read. The gauge is therefore
 * the read surface and the single owner of the composition rule in both processes now, and becomes
 * the only mechanism once {@code MmfWorkerSignalLayoutV1} is deleted.
 *
 * <p>Thread-safe: written from the inference mode-change listener and the energy poll thread, read
 * from the indexing loop and backfill threads. Both fields are {@code volatile} and independent, so
 * no lock is needed — a reader may observe a mid-update pair, which is exactly the semantics the two
 * MMF bytes had.
 *
 * <p><b>Conservative defaults.</b> Both fields start {@code false}: an unwritten gauge — no
 * inference runtime, a host where {@code GetSystemPowerStatus} is unavailable — never throttles.
 * That matches {@code WorkerSignalBus.isEnergyReduced()}'s documented default.
 */
public final class GpuSchedulingGauge {

  private volatile boolean mainGpuActive;
  private volatile boolean energyReduced;

  /**
   * The composition rule, as a pure function so the one authority can be applied to values that do
   * not come from this instance (the {@code WorkerSignalBus} default still composes its own two
   * reads while the interface survives; see item A10).
   *
   * @param mainGpuActive whether the inference runtime has claimed the GPU
   * @param energyReduced whether the OS wants background work reduced
   * @return true if GPU-heavy bulk backfill should be deferred
   */
  public static boolean shouldYield(boolean mainGpuActive, boolean energyReduced) {
    return mainGpuActive || energyReduced;
  }

  /** Records whether the inference runtime has claimed the GPU (Online Mode). */
  public void setMainGpuActive(boolean active) {
    this.mainGpuActive = active;
  }

  /** Records the OS energy-intent: true when the OS wants background work reduced. */
  public void setEnergyReduced(boolean reduced) {
    this.energyReduced = reduced;
  }

  /** Whether the inference runtime is actively using the GPU. */
  public boolean isMainGpuActive() {
    return mainGpuActive;
  }

  /** Whether the OS is requesting reduced background work. */
  public boolean isEnergyReduced() {
    return energyReduced;
  }

  /**
   * Whether GPU-heavy bulk backfill should yield right now — either because the GPU is claimed OR
   * because the OS wants reduced background work. One concept, two reasons.
   */
  public boolean shouldYieldGpuBackfill() {
    return shouldYield(mainGpuActive, energyReduced);
  }

  @Override
  public String toString() {
    return "GpuSchedulingGauge[mainGpuActive="
        + mainGpuActive
        + ", energyReduced="
        + energyReduced
        + "]";
  }
}

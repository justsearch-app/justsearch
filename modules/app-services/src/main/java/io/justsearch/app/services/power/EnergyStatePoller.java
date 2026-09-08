/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.power;

import io.justsearch.app.util.EnergyState;
import io.justsearch.app.util.WindowsPowerStatus;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the OS energy-intent and publishes it (tempdoc 630; lane F stage A item A5).
 *
 * <p>This is {@code WorkerSpawner.pollEnergyState()} lifted out of the spawner, unchanged in
 * behaviour: the same 15 s cadence, the same {@code GetSystemPowerStatus} probe through
 * {@link WindowsPowerStatus#read()}, the same {@code justsearch.power.force_energy_state} override,
 * and the same best-effort contract (a probe failure leaves the last state in place, so an
 * unreadable signal never throttles indexing).
 *
 * <p><b>Why it moved.</b> The energy poll has nothing to do with spawning a process — it was hosted
 * there only because the spawner already owned a scheduler and the memory-mapped signal file. Item
 * A11 deleted {@code WorkerSpawner}; the poll had to outlive it, so it became a small component the
 * composition root owns. {@code KnowledgeServerBootstrap} constructs and starts it.
 *
 * <p><b>One sink.</b> Every poll writes {@link GpuSchedulingGauge}, the in-process authority.
 * There used to be a second: an optional {@code EnergyReducedSink} carrying the memory-mapped
 * {@code energy_reduced} byte to the separate Worker process. That interface's own javadoc said it
 * "is deleted with the MMF bus at A10" — A10 landed and it was not, so it survived as a parameter
 * with no implementation anywhere in the repo, the one production call site passing {@code null},
 * and four of six test sites passing {@code null} too. The stage-A checkpoint deleted it. The gauge
 * is the half that survives.
 */
public final class EnergyStatePoller implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(EnergyStatePoller.class);

  /** OS energy-intent poll cadence. Energy state changes slowly (plug/unplug). */
  public static final long POLL_INTERVAL_MS = 15_000L;

  private final GpuSchedulingGauge gauge;
  private final Supplier<EnergyState> probe;
  private final AtomicReference<EnergyState> latest = new AtomicReference<>(EnergyState.unknown());
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> task;

  /**
   * @param gauge the in-process GPU-scheduling gauge this poller writes (required)
   */
  public EnergyStatePoller(GpuSchedulingGauge gauge) {
    this(gauge, EnergyStatePoller::probeHost);
  }

  /** Test seam: an injectable probe standing in for the host's {@code GetSystemPowerStatus}. */
  EnergyStatePoller(GpuSchedulingGauge gauge, Supplier<EnergyState> probe) {
    this.gauge = Objects.requireNonNull(gauge, "gauge");
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  /**
   * Starts the poll: once immediately, then every {@link #POLL_INTERVAL_MS}. Idempotent — a second
   * call while running is a no-op, so a restart of the process being supervised does not stack
   * pollers — and restartable after {@link #close()}, because {@code KnowledgeServerBootstrap} may
   * be started again on the same instance (boot recovery, upgrade restart). The last polled state
   * survives a stop/start cycle: the OS energy-intent does not change because we stopped looking.
   */
  public synchronized void start() {
    if (task != null) {
      return;
    }
    if (scheduler == null || scheduler.isShutdown()) {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "energy-state-poller");
                t.setDaemon(true);
                return t;
              });
    }
    task = scheduler.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * One poll. Best-effort: a probe failure is logged at debug and leaves the previously published
   * state untouched (so a transient failure cannot flip the throttle on or off).
   */
  public void poll() {
    try {
      EnergyState state = probe.get();
      latest.set(state);
      gauge.setEnergyReduced(state.reduced());
    } catch (Exception e) {
      log.debug("Energy-state poll failed (treated as unknown): {}", e.getMessage());
    }
  }

  /** The latest polled OS energy-intent. Never null; UNKNOWN until the first successful poll. */
  public EnergyState energyState() {
    return latest.get();
  }

  @Override
  public synchronized void close() {
    if (task != null) {
      task.cancel(false);
      task = null;
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  /**
   * The host probe, verbatim from {@code WorkerSpawner.pollEnergyState()}: the
   * {@code justsearch.power.force_energy_state} sysprop ({@code reduced}/{@code full}) overrides the
   * probe for testing and UI validation, otherwise {@code GetSystemPowerStatus} is read through
   * {@link WindowsPowerStatus#read()}.
   */
  private static EnergyState probeHost() {
    String forced = EnvRegistry.POWER_FORCE_ENERGY_STATE.getString("");
    if ("reduced".equalsIgnoreCase(forced)) {
      return new EnergyState(EnergyState.Intent.REDUCED, EnergyState.Source.AC);
    }
    if ("full".equalsIgnoreCase(forced)) {
      return new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC);
    }
    return WindowsPowerStatus.read();
  }
}

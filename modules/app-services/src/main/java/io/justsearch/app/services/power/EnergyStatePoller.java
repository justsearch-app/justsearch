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
 * composition root owns. Today {@code KnowledgeServerBootstrap} (the root-to-be on the Head side)
 * constructs and starts it, which keeps it running in the current split build; at A6 the
 * {@code app-engine} root takes it over unchanged.
 *
 * <p><b>Two sinks, one transitional.</b> Every poll writes {@link GpuSchedulingGauge}, the
 * in-process authority. It also calls the optional {@link EnergyReducedSink} — the memory-mapped
 * {@code energy_reduced} byte the separate Worker process still reads. That sink is the half that
 * dies at A10; the gauge is the half that survives.
 */
public final class EnergyStatePoller implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(EnergyStatePoller.class);

  /** OS energy-intent poll cadence. Energy state changes slowly (plug/unplug). */
  public static final long POLL_INTERVAL_MS = 15_000L;

  /**
   * The transitional cross-process sink: the memory-mapped {@code energy_reduced} byte. Deleted with
   * the MMF bus at item A10, after which the gauge is the only publication.
   */
  @FunctionalInterface
  public interface EnergyReducedSink {
    void publish(boolean reduced);
  }

  private final GpuSchedulingGauge gauge;
  private final EnergyReducedSink mmfSink;
  private final Supplier<EnergyState> probe;
  private final AtomicReference<EnergyState> latest = new AtomicReference<>(EnergyState.unknown());
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> task;

  /**
   * @param gauge the in-process GPU-scheduling gauge this poller writes (required)
   * @param mmfSink the transitional memory-mapped publication, or {@code null} once there is no
   *     second process to publish to
   */
  public EnergyStatePoller(GpuSchedulingGauge gauge, EnergyReducedSink mmfSink) {
    this(gauge, mmfSink, EnergyStatePoller::probeHost);
  }

  /** Test seam: an injectable probe standing in for the host's {@code GetSystemPowerStatus}. */
  EnergyStatePoller(GpuSchedulingGauge gauge, EnergyReducedSink mmfSink, Supplier<EnergyState> probe) {
    this.gauge = Objects.requireNonNull(gauge, "gauge");
    this.mmfSink = mmfSink;
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
      EnergyReducedSink sink = mmfSink;
      if (sink != null) {
        sink.publish(state.reduced());
      }
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

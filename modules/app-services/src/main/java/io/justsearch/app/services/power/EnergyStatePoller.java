/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.power;

import io.justsearch.app.util.EnergyState;
import io.justsearch.app.util.WindowsPowerStatus;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.io.Closeable;
import java.util.Objects;
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
  private final EngineExecutorRegistry processExecutors;
  private final Supplier<EnergyState> probe;
  private final AtomicReference<EnergyState> latest = new AtomicReference<>(EnergyState.unknown());
  private volatile BulkWorkGate bulkWork;
  private ScheduledExecutorService scheduler;
  private EngineExecutorRegistry.Registration schedulerRegistration;
  private ScheduledFuture<?> task;
  private long activation;
  private java.util.concurrent.CountDownLatch firstReading = new java.util.concurrent.CountDownLatch(1);
  public static final long FIRST_READING_TIMEOUT_MS = 3000L;

  /**
   * @param gauge the in-process GPU-scheduling gauge this poller writes (required)
   */
  public EnergyStatePoller(
      EngineExecutorRegistry processExecutors, GpuSchedulingGauge gauge) {
    this(processExecutors, gauge, EnergyStatePoller::probeHost);
  }

  /** Test seam: an injectable probe standing in for the host's {@code GetSystemPowerStatus}. */
  EnergyStatePoller(
      EngineExecutorRegistry processExecutors,
      GpuSchedulingGauge gauge,
      Supplier<EnergyState> probe) {
    this.processExecutors = Objects.requireNonNull(processExecutors, "processExecutors");
    this.gauge = Objects.requireNonNull(gauge, "gauge");
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  /**
   * Starts the poll: once immediately, then every {@link #POLL_INTERVAL_MS}. Idempotent — a second
   * call while running is a no-op, so a restart of the process being supervised does not stack
   * pollers — and restartable after {@link #close()}, because {@code KnowledgeServerBootstrap} may
   * be started again on the same instance (boot recovery, upgrade restart). A battery-pause
   * activation starts UNKNOWN and requires a fresh observation. With that preference off, the
   * existing best-effort energy-saver cache survives restart. Old probes cannot publish into either.
   */
  public synchronized void start() {
    if (task != null) {
      return;
    }
    if (scheduler == null || scheduler.isShutdown()) {
      EngineExecutorRegistry.Limits background =
          processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
      EngineExecutorRegistry.Registration registration =
          processExecutors.register(
              new EngineExecutorSpec(
                  "head.energy-state-poller",
                  EngineExecutorSpec.Kind.BACKGROUND,
                  EngineExecutorSpec.Mode.SCHEDULED,
                  1,
                  background.maxQueue(),
                  1));
      try {
        scheduler =
            registration.openScheduled(
                r -> {
                  Thread t = new Thread(r, "energy-state-poller");
                  t.setDaemon(true);
                  return t;
                });
        schedulerRegistration = registration;
      } catch (RuntimeException | Error failure) {
        try {
          registration.close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }
    long current = ++activation;
    firstReading = new java.util.concurrent.CountDownLatch(1);
    if (gauge.indexingHold().pauseOnBattery()) publish(EnergyState.unknown());
    task = scheduler.scheduleAtFixedRate(() -> poll(current), 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  /** Wait only for the current activation when the saved battery rule requires it. */
  public void awaitFirstReading() throws InterruptedException {
    if (!gauge.indexingHold().pauseOnBattery()) return;
    final java.util.concurrent.CountDownLatch completion;
    synchronized (this) { completion = firstReading; }
    // CountDownLatch uses elapsed/monotonic time, independent of wall-clock adjustments.
    completion.await(FIRST_READING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /** One best-effort poll; a first failure completes as UNKNOWN, later failures keep the reading. */
  public void poll() {
    final long current;
    synchronized (this) { current = activation; }
    poll(current);
  }

  private void poll(long current) {
    EnergyState observed;
    try { observed = Objects.requireNonNull(probe.get(), "power observation"); }
    catch (Exception failure) {
      log.debug("Energy-state poll failed: {}", failure.getMessage());
      synchronized (this) {
        if (current == activation && firstReading.getCount() != 0) {
          if (gauge.indexingHold().pauseOnBattery()) publish(EnergyState.unknown());
          firstReading.countDown();
        }
      }
      return;
    }
    synchronized (this) {
      if (current != activation) return;
      publish(observed);
      firstReading.countDown();
    }
  }

  private void publish(EnergyState state) {
    EnergyState before = latest.getAndSet(state);
    io.justsearch.core.status.StatusChangeCounter.process().changed(before, state);
    gauge.setEnergyReduced(state.reduced());
    gauge.indexingHold().setOnBattery(state.source() == EnergyState.Source.BATTERY);
    BulkWorkGate gate = bulkWork;
    if (gate != null) gate.setEnergyReduced(state.reduced());
  }

  /**
   * Mirrors every later poll into the Engine's bulk-work gate as well (Spec E §1.2, step E1). The
   * mirror is status only: the gate lists energy saver in its verdict and never holds for it, while
   * the gauge above stays the energy actuation (P7). The current state is mirrored at once.
   */
  public void mirrorEnergyTo(BulkWorkGate gate) {
    this.bulkWork = Objects.requireNonNull(gate, "gate");
    gate.setEnergyReduced(latest.get().reduced());
  }

  /** The latest polled OS energy-intent. Never null; UNKNOWN until the first successful poll. */
  public EnergyState energyState() {
    return latest.get();
  }

  @Override
  public synchronized void close() {
    activation++;
    firstReading.countDown();
    if (task != null) {
      task.cancel(false);
      task = null;
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
    if (schedulerRegistration != null) {
      schedulerRegistration.close();
      schedulerRegistration = null;
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

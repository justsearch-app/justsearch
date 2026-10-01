/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.api.OperationAdmissionClosedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the ordered {@link InstallStage}s of one install run (tempdoc 840 Phase 3).
 *
 * <p>Where {@link AcquisitionScheduler} owns the set of FILES, this owns the sequence of STAGES: per
 * stage it takes an op-lease, acquires that stage's slice, applies the configuration its artifacts
 * enable, and moves on. The first ~1.3 GB supplies the core search models before all ~7 GB
 * have arrived. Encoder paths are resolved when Engine configuration is built; applying a
 * stage reports that JustSearch must be restarted to use the newly downloaded models.
 *
 * <p><b>It performs no IO and knows no wire type</b>, for the same reason the scheduler does not:
 * acquisition goes through {@link Acquirer}, configuration through {@link Configurer}, leases
 * through {@link LeaseRegistrar}, and everything a surface would want to know is reported to {@link
 * Listener}. The three properties that were previously only reachable by downloading ~7 GB onto
 * Windows — the ordering, that a stage which delivered nothing skips the final apply step, and that
 * a cancellation mid-stage leaves the earlier stages' results standing — are unit-testable here.
 */
final class StagedAcquisition {

  private static final Logger log = LoggerFactory.getLogger(StagedAcquisition.class);

  /** How one stage ended. */
  enum StageState {
    /** Nothing to acquire: already installed, hardware-skipped, or declined. */
    SKIPPED,
    /** Every planned file of the stage landed. */
    COMPLETED,
    /** The stage ran and at least one of its files failed. */
    FAILED,
    /** The run stopped during (or instead of) this stage. */
    CANCELLED,
    /**
     * A precondition refused the stage before it started — today, not enough disk space for what it
     * would fetch (tempdoc 840 U2). Distinct from FAILED: nothing was attempted, so nothing broke,
     * and the remedy is the user's to apply rather than a retry's.
     */
    BLOCKED;

    /** The wire-facing id — the same vocabulary {@code AiInstallStatus.StageStatus.state} uses. */
    String id() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * Refuses a stage before it starts. Returns the human reason, or {@code null} to allow it.
   *
   * <p>Consulted after the empty check and BEFORE the lease is registered: a stage that cannot run
   * must not take out an op-lease that would block an upgrade for a download never attempted.
   */
  @FunctionalInterface
  interface Precondition {
    String blockedReason(InstallStage.Slice slice);

    /** The default: nothing is refused. */
    static Precondition open() {
      return slice -> null;
    }
  }

  /** One stage's op-lease, released exactly once when the stage ends. */
  @FunctionalInterface
  interface Lease {
    void release(boolean success);
  }

  /** Takes out one stage's lease. */
  @FunctionalInterface
  interface LeaseRegistrar {
    /**
     * Registers the lease covering this stage's acquisition.
     *
     * @throws OperationAdmissionClosedException when an upgrade preparation owns the admission
     *     barrier — which is a legitimate reason for the run to stop between stages, not an error
     */
    Lease register(InstallStage.Slice slice);
  }

  /** Acquires one stage's slice — in production, an {@link AcquisitionStage} over those downloads. */
  @FunctionalInterface
  interface Acquirer {
    AcquisitionScheduler.Summary acquire(InstallStage.Slice slice);
  }

  /** Applies the configuration this stage's artifacts enable, and restarts the Worker onto them. */
  @FunctionalInterface
  interface Configurer {
    ConfigurationStage.Applied configure(InstallStage.Slice slice, AcquisitionScheduler.Summary acquired);
  }

  /**
   * The configuration pass a run owes when NO stage ran one — the invariant "an install run
   * configures the process at least once" (tempdoc 840 R1).
   *
   * <p>Every stage being empty is a supported outcome, not a degenerate one: it is what a
   * pre-staged {@code JUSTSEARCH_MODELS_DIR} produces, and what a repair on an already-complete
   * machine produces. Before this seam existed such a run applied nothing at all — no ONNX model
   * paths written, no system properties latched, no ConfigStore rebuild, no configuration application — while
   * reporting itself completed, because every configuration call site hung off a stage that had
   * files to fetch.
   *
   * <p><b>It restarts the Worker unconditionally</b>, which is exactly what {@link
   * #restartGate(AcquisitionScheduler.Summary, BooleanSupplier)} refuses for a stage. The two are
   * not in tension: that gate asks "did THIS stage place anything for a restarted Worker to load?",
   * and the answer for a run that fetched nothing is that the files are new to the WORKER even
   * though they are not new to the disk — a Worker started before the models were staged has
   * resolved none of them, and there is no encoder hot-reload.
   */
  @FunctionalInterface
  interface TerminalConfigurer {
    ConfigurationStage.Applied configure();

    /** No terminal pass — the pre-seam behaviour, kept for constructions that do not configure. */
    static TerminalConfigurer none() {
      return () -> null;
    }
  }

  /** Everything an observer needs to project the staged run, with no surface type mentioned. */
  interface Listener {
    /** This stage is about to acquire. */
    default void onStageStarted(InstallStage stage) {}

    /** This stage's acquisition finished, however it went — the point its bytes are banked. */
    default void onStageAcquired(InstallStage stage, AcquisitionScheduler.Summary summary) {}

    /** This stage reached a terminal state. */
    default void onStageEnded(InstallStage stage, StageState state) {}

    /** A precondition refused this stage before it started, with the reason to show the user. */
    default void onStageBlocked(InstallStage stage, String reason) {}
  }

  private final Acquirer acquirer;
  private final Configurer configurer;
  private final TerminalConfigurer terminalConfigurer;
  private final LeaseRegistrar leases;
  private final Listener listener;
  private final BooleanSupplier cancelRequested;
  private final Precondition precondition;

  StagedAcquisition(
      Acquirer acquirer,
      Configurer configurer,
      LeaseRegistrar leases,
      Listener listener,
      BooleanSupplier cancelRequested) {
    this(acquirer, configurer, leases, listener, cancelRequested, Precondition.open());
  }

  StagedAcquisition(
      Acquirer acquirer,
      Configurer configurer,
      LeaseRegistrar leases,
      Listener listener,
      BooleanSupplier cancelRequested,
      Precondition precondition) {
    this(
        acquirer,
        configurer,
        TerminalConfigurer.none(),
        leases,
        listener,
        cancelRequested,
        precondition);
  }

  StagedAcquisition(
      Acquirer acquirer,
      Configurer configurer,
      TerminalConfigurer terminalConfigurer,
      LeaseRegistrar leases,
      Listener listener,
      BooleanSupplier cancelRequested,
      Precondition precondition) {
    this.acquirer = acquirer;
    this.configurer = configurer;
    this.terminalConfigurer =
        terminalConfigurer == null ? TerminalConfigurer.none() : terminalConfigurer;
    this.leases = leases;
    this.listener = listener == null ? new Listener() {} : listener;
    this.cancelRequested = cancelRequested == null ? () -> false : cancelRequested;
    this.precondition = precondition == null ? Precondition.open() : precondition;
  }

  /**
   * A configuration step that is ATTEMPTED at every stage but can only APPLY once per run.
   *
   * <p>The configuration list is re-run per stage, relying on each step's own guards to fall out
   * when its inputs are not on disk yet. That is right for a step whose work is incremental
   * ({@code applyOnnxSettings} writes one more encoder path each time a stage lands one) and wrong
   * for a step that flips a process-wide latch: {@code applySettings} ends in {@code
   * applyRuntimeOverrides(RESTART_IF_ONLINE)}, which restarts the engine whenever the engine is
   * online <em>regardless of whether the config changed</em>, so a repair run that already has the
   * chat model on disk would restart it once per stage where a single run used to restart it once.
   *
   * <p>The latch is set only on a TRUE return, which is what makes it compatible with the guards
   * rather than a replacement for them: a step that legitimately fell out of a guard at the core
   * stage (no chat model on disk yet) is attempted again at the chat stage and applies there.
   */
  static BooleanSupplier applyOncePerRun(BooleanSupplier step) {
    AtomicBoolean applied = new AtomicBoolean(false);
    return () -> {
      if (applied.get()) {
        return false;
      }
      boolean did = step.getAsBoolean();
      if (did) {
        applied.set(true);
      }
      return did;
    };
  }

  /**
   * The final configuration-application notification, gated on acquiring new artifacts.
   *
   * <p>The notification requests an application restart; it does not restart a component here.
   * This is the ONE configuration step that is stage-gated; every other step guards on its own inputs
   * being on disk, which is a better selector than any stage mapping because it is disk truth.
   *
   * <p>Short-circuit order is load-bearing: {@code restart} must not be invoked at all when nothing
   * was installed.
   */
  static BooleanSupplier restartGate(AcquisitionScheduler.Summary acquired, BooleanSupplier restart) {
    return () -> acquired != null && acquired.installed() > 0 && restart.getAsBoolean();
  }

  /**
   * Runs the stages in order.
   *
   * @param slices the ordered stages, empty ones included
   * @param firstStageLease a lease already registered for whichever stage comes first — the install
   *     run takes it out on the CALLING thread so upgrade prepare can never observe no blocker while
   *     a download is starting. Never registered again here. May be null.
   * @return true when every stage ran to its own end; false when the run was cancelled, in which
   *     case the caller has already been told which stage it happened in
   */
  boolean run(List<InstallStage.Slice> slices, Lease firstStageLease) {
    Lease carried = firstStageLease;
    boolean anyStageConfigured = false;
    for (InstallStage.Slice slice : slices) {
      Lease lease = carried;
      carried = null;

      if (slice.isEmpty()) {
        // No lease or configuration pass: this stage acquired no new artifacts.
        listener.onStageEnded(slice.stage(), StageState.SKIPPED);
        if (lease != null) {
          lease.release(true);
        }
        continue;
      }

      String blocked = precondition.blockedReason(slice);
      if (blocked != null) {
        // Refused before anything was attempted. Continue rather than abandoning the run: each stage
        // is measured against its own size, so a disk that cannot hold the chat model can still be
        // holding a working retrieval core placed by an earlier stage. Nothing special-cases a
        // blocked CORE either — its packages simply never reach `installed`, and the existing
        // completeness machinery already reports that honestly as not-installed and repair-needed.
        log.warn("Install stage [{}] blocked before start: {}", slice.stage().id(), blocked);
        listener.onStageBlocked(slice.stage(), blocked);
        listener.onStageEnded(slice.stage(), StageState.BLOCKED);
        if (lease != null) {
          lease.release(false);
        }
        continue;
      }

      if (lease == null) {
        try {
          lease = leases.register(slice);
        } catch (OperationAdmissionClosedException admissionClosed) {
          // An upgrade preparation froze admission between stages. That is the same outcome the
          // lease's cancellation callback produces, arriving by a different door: stop here, with
          // everything the earlier stages placed already on disk and already configured.
          log.info(
              "Install stage [{}] not started — operation admission is frozen: {}",
              slice.stage().id(),
              admissionClosed.getMessage());
          listener.onStageEnded(slice.stage(), StageState.CANCELLED);
          return false;
        }
      }

      boolean succeeded = false;
      try {
        listener.onStageStarted(slice.stage());
        AcquisitionScheduler.Summary acquired = acquirer.acquire(slice);
        listener.onStageAcquired(slice.stage(), acquired);

        if (acquired == null || acquired.cancelled() || cancelRequested.getAsBoolean()) {
          listener.onStageEnded(slice.stage(), StageState.CANCELLED);
          return false;
        }

        ConfigurationStage.Applied configured = configurer.configure(slice, acquired);
        anyStageConfigured = true;
        if (configured != null && configured.cancelled()) {
          listener.onStageEnded(slice.stage(), StageState.CANCELLED);
          return false;
        }

        succeeded = acquired.failed() == 0;
        listener.onStageEnded(
            slice.stage(), succeeded ? StageState.COMPLETED : StageState.FAILED);
      } finally {
        lease.release(succeeded);
      }
    }
    if (!anyStageConfigured) {
      // No stage had anything to fetch, so no stage configured anything — yet the run must still
      // leave the process pointed at what is on disk. See TerminalConfigurer for why this one also
      // reports the application restart requirement even when every stage was already on disk.
      log.info(
          "No stage acquired anything; applying the run's configuration once, terminally"
              + " (pre-staged models or a repair with nothing missing)");
      ConfigurationStage.Applied configured = terminalConfigurer.configure();
      if (configured != null && configured.cancelled()) {
        return false;
      }
    }
    return true;
  }
}

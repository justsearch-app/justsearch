/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 3 of an install run: point the running process at what was just installed.
 *
 * <p><b>The order is the load-bearing part, so it is a list and not a comment.</b> These steps write
 * system properties through {@code SystemPropertyUtils.setSysPropIfBlank}, which is first-writer
 * wins — a permanent latch for the life of the process. So a step that runs before another does not
 * merely run earlier, it can decide what the later one is allowed to see. The one case this
 * currently turns on is {@code CUDA_SERVER_EXE} before {@code LLM_SETTINGS}: the {@code
 * applyRuntimeOverrides} call inside the settings step reads the server executable through the
 * ConfigStore, so with the order reversed chat stays on the default CPU variant until the next app
 * restart. Previously that constraint existed only as a comment above two adjacent statements,
 * where nothing stopped an edit from separating them.
 *
 * <p><b>What applied is recorded.</b> Every step reports whether it actually did anything — each one
 * has guards it can legitimately fall out of (no chat model on this profile, no cuda12 binary
 * staged, a user override already set, missing CUDA runtime DLLs). Before this, a run in which three
 * of five steps quietly did nothing was indistinguishable from one where all five applied. {@link
 * Applied} makes a partial application a fact the run can report rather than a silence.
 */
final class ConfigurationStage {

  private static final Logger log = LoggerFactory.getLogger(ConfigurationStage.class);

  /**
   * One named configuration step.
   *
   * @param phase phase id to publish before running this step, or null to stay on the current phase
   * @param phaseMessage user-facing message accompanying {@code phase}
   * @param cancellationCheckpointBefore whether cancellation is honoured before this step. Set only
   *     where the run already honoured it: no step is made interruptible mid-write, because a
   *     half-applied configuration is worse than a fully applied one.
   * @param action runs the step; returns true when it actually applied something
   */
  record Step(
      String name,
      String phase,
      String phaseMessage,
      boolean cancellationCheckpointBefore,
      BooleanSupplier action) {}

  /** Publishes a phase change to the surface. */
  @FunctionalInterface
  interface PhaseReporter {
    void phase(String phase, String message);
  }

  /**
   * What the stage did.
   *
   * @param cancelled true when a checkpoint stopped the stage; {@code notRun} names what was left
   * @param applied steps that reported doing something
   * @param notApplied steps that ran and fell out of a guard
   * @param notRun steps never reached, because the stage was cancelled first
   */
  record Applied(
      boolean cancelled, Set<String> applied, Set<String> notApplied, Set<String> notRun) {

    boolean fullyApplied() {
      return !cancelled && notApplied.isEmpty() && notRun.isEmpty();
    }
  }

  /** Step names, so callers and tests refer to the same strings the log prints. */
  static final String CUDA_SERVER_EXE = "cuda_server_exe";

  static final String LLM_SETTINGS = "llm_settings";
  static final String ONNX_SETTINGS = "onnx_settings";
  static final String ORT_NATIVE_PATH = "ort_native_path";
  static final String WORKER_RESTART = "worker_restart";

  private final List<Step> steps;
  private final BooleanSupplier cancelRequested;
  private final PhaseReporter phaseReporter;

  ConfigurationStage(List<Step> steps, BooleanSupplier cancelRequested, PhaseReporter phaseReporter) {
    this.steps = List.copyOf(steps);
    this.cancelRequested = cancelRequested == null ? () -> false : cancelRequested;
    this.phaseReporter = phaseReporter == null ? (p, m) -> {} : phaseReporter;
  }

  /**
   * The canonical install ordering. This factory is the single place the sequence is declared; a
   * change to it is a change to the one thing about this stage that can silently break chat.
   */
  static ConfigurationStage forInstall(
      BooleanSupplier cudaServerExe,
      BooleanSupplier llmSettings,
      BooleanSupplier onnxSettings,
      BooleanSupplier ortNativePath,
      BooleanSupplier workerRestart,
      BooleanSupplier cancelRequested,
      PhaseReporter phaseReporter) {
    List<Step> steps =
        List.of(
            new Step(CUDA_SERVER_EXE, "apply", "Applying configuration...", false, cudaServerExe),
            new Step(LLM_SETTINGS, null, null, false, llmSettings),
            new Step(ONNX_SETTINGS, null, null, false, onnxSettings),
            new Step(ORT_NATIVE_PATH, null, null, false, ortNativePath),
            // Lane F stage A: the copy used to say "Restarting worker...", which was true when a
            // child process could be replaced under a running Head. Nothing restarts a worker now
            // (RestartRequiredException) — the settings are applied and the change takes effect on
            // the next Engine start, so the message says that instead of narrating an act that no
            // longer happens. The phase ID stays `restart_worker`: it is the machine key a caller
            // reads, and renaming it would be a contract change rather than a copy fix.
            new Step(
                WORKER_RESTART,
                "restart_worker",
                "Applied — restart JustSearch to use the new configuration",
                true,
                workerRestart));
    return new ConfigurationStage(steps, cancelRequested, phaseReporter);
  }

  /** Runs the steps in order and reports what applied. */
  Applied apply() {
    Set<String> applied = new LinkedHashSet<>();
    Set<String> notApplied = new LinkedHashSet<>();
    for (int i = 0; i < steps.size(); i++) {
      Step step = steps.get(i);
      if (step.cancellationCheckpointBefore() && cancelRequested.getAsBoolean()) {
        Set<String> notRun = new LinkedHashSet<>();
        for (Step remaining : steps.subList(i, steps.size())) {
          notRun.add(remaining.name());
        }
        return new Applied(true, applied, notApplied, notRun);
      }
      if (step.phase() != null) {
        phaseReporter.phase(step.phase(), step.phaseMessage());
      }
      if (step.action() != null && step.action().getAsBoolean()) {
        applied.add(step.name());
      } else {
        notApplied.add(step.name());
      }
    }
    Applied result = new Applied(false, applied, notApplied, Set.of());
    if (result.fullyApplied()) {
      log.info("Install configuration applied in full: {}", new ArrayList<>(applied));
    } else {
      log.info(
          "Install configuration partially applied: applied={}, notApplied={}",
          new ArrayList<>(applied),
          new ArrayList<>(notApplied));
    }
    return result;
  }
}

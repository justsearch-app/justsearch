/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The configuration tail's two properties that used to be un-checkable: the ORDER of the steps, and
 * which of them actually did anything.
 *
 * <p>The order was previously a comment above two adjacent statements. Nothing failed if an edit
 * separated them, and the symptom would have been a user-visible one that no test could see — chat
 * silently staying on the CPU llama-server binary until the next app restart, because the system
 * property {@code setSysPropIfBlank} latches is first-writer-wins.
 */
final class ConfigurationStageTest {

  private static ConfigurationStage.PhaseReporter noPhases() {
    return (phase, message) -> {};
  }

  @Test
  @Timeout(10)
  @DisplayName("the cuda server.exe step runs before the settings step that reads it")
  void ordersCudaServerExeBeforeLlmSettings() {
    List<String> order = new ArrayList<>();

    ConfigurationStage.Applied applied =
        ConfigurationStage.forInstall(
                () -> order.add(ConfigurationStage.CUDA_SERVER_EXE),
                () -> order.add(ConfigurationStage.LLM_SETTINGS),
                () -> order.add(ConfigurationStage.ONNX_SETTINGS),
                () -> order.add(ConfigurationStage.ORT_NATIVE_PATH),
                () -> order.add(ConfigurationStage.WORKER_RESTART),
                () -> false,
                noPhases())
            .apply();

    assertEquals(
        List.of(
            ConfigurationStage.CUDA_SERVER_EXE,
            ConfigurationStage.LLM_SETTINGS,
            ConfigurationStage.ONNX_SETTINGS,
            ConfigurationStage.ORT_NATIVE_PATH,
            ConfigurationStage.WORKER_RESTART),
        order,
        "cuda12 server.exe must be selected before applySettings reads it through the ConfigStore");
    assertTrue(applied.fullyApplied());
    assertFalse(applied.cancelled());
  }

  @Test
  @Timeout(10)
  @DisplayName("a step that falls out of a guard is recorded as not applied, not as applied")
  void recordsPartialApplication() {
    ConfigurationStage.Applied applied =
        ConfigurationStage.forInstall(
                () -> false, // no cuda12 binary staged
                () -> true,
                () -> false, // no ONNX package landed
                () -> true,
                () -> true,
                () -> false,
                noPhases())
            .apply();

    assertFalse(applied.fullyApplied(), "three of five is not a full application");
    assertEquals(
        Set.of(
            ConfigurationStage.LLM_SETTINGS,
            ConfigurationStage.ORT_NATIVE_PATH,
            ConfigurationStage.WORKER_RESTART),
        applied.applied());
    assertEquals(
        Set.of(ConfigurationStage.CUDA_SERVER_EXE, ConfigurationStage.ONNX_SETTINGS),
        applied.notApplied(),
        "a step that silently did nothing must be nameable afterwards");
    assertTrue(applied.notRun().isEmpty());
  }

  @Test
  @Timeout(10)
  @DisplayName("the settings quartet is not interruptible; only the worker restart has a checkpoint")
  void onlyTheWorkerRestartHonoursCancellationMidStage() {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    List<String> ran = new ArrayList<>();

    ConfigurationStage.Applied applied =
        ConfigurationStage.forInstall(
                () -> {
                  ran.add(ConfigurationStage.CUDA_SERVER_EXE);
                  // Cancel raised in the middle of the quartet: a half-applied configuration is
                  // worse than a fully applied one, so the remaining settings steps still run.
                  cancelled.set(true);
                  return true;
                },
                () -> ran.add(ConfigurationStage.LLM_SETTINGS),
                () -> ran.add(ConfigurationStage.ONNX_SETTINGS),
                () -> ran.add(ConfigurationStage.ORT_NATIVE_PATH),
                () -> ran.add(ConfigurationStage.WORKER_RESTART),
                cancelled::get,
                noPhases())
            .apply();

    assertEquals(
        List.of(
            ConfigurationStage.CUDA_SERVER_EXE,
            ConfigurationStage.LLM_SETTINGS,
            ConfigurationStage.ONNX_SETTINGS,
            ConfigurationStage.ORT_NATIVE_PATH),
        ran,
        "no settings step is made interruptible mid-write");
    assertTrue(applied.cancelled());
    assertEquals(
        Set.of(ConfigurationStage.WORKER_RESTART),
        applied.notRun(),
        "the step the checkpoint guarded is named as never run");
  }

  @Test
  @Timeout(10)
  @DisplayName("phase changes are published only where the run publishes them today")
  void publishesTheTwoPhasesTheSurfaceExpects() {
    List<String> phases = new ArrayList<>();

    ConfigurationStage.forInstall(
            () -> true,
            () -> true,
            () -> true,
            () -> true,
            () -> true,
            () -> false,
            (phase, message) -> phases.add(phase + "|" + message))
        .apply();

    assertEquals(
        List.of(
            "apply|Applying configuration...",
            "restart_worker|Applied — restart JustSearch to use the new configuration"),
        phases,
        "the FE keys off phase — these two strings are the contract. The second message changed"
            + " at lane F stage A: nothing restarts a worker any more, so it states the outcome"
            + " and the user action (restart_required) instead of narrating a restart. Pinned"
            + " exactly, so a re-introduction of the old over-claim fails here.");
  }
}

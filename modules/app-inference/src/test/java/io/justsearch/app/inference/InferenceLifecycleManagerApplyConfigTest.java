/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

final class InferenceLifecycleManagerApplyConfigTest {
  @TempDir Path directory;

  @Test
  void insufficientVramRefusesCandidateWithoutDisturbingOnlineIncumbent() throws Exception {
    InferenceConfig incumbent = config(0);
    InferenceConfig candidate = config(1);
    InferenceTelemetryEvents events = mock(InferenceTelemetryEvents.class);

    try (MockedConstruction<GpuCapabilitiesService> gpuConstruction =
            mockConstruction(
                GpuCapabilitiesService.class,
                (gpu, context) -> when(gpu.snapshot()).thenReturn(gpuSnapshot(1L)));
        MockedConstruction<LlamaServerOps> serverConstruction =
            mockConstruction(LlamaServerOps.class);
        MockedConstruction<TokenEndpointOps> tokenConstruction =
            mockConstruction(TokenEndpointOps.class);
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = new InferenceLifecycleManager(executors, incumbent, events)) {
      LlamaServerOps serverOps = serverConstruction.constructed().getFirst();
      TokenEndpointOps tokenOps = tokenConstruction.constructed().getFirst();
      manager.switchToOnlineMode();
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      clearInvocations(serverOps, tokenOps, events);

      ModeTransitionException failure =
          assertThrows(
              ModeTransitionException.class,
              () ->
                  manager.applyConfig(
                      candidate, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS));

      assertEquals(ModeTransitionException.Reason.INSUFFICIENT_VRAM, failure.reason());
      verify(serverOps, never()).stopLlamaServer();
      verify(serverOps, never()).resetCrashCounters();
      verify(serverOps, never()).startLlamaServer();
      verify(tokenOps, never()).clearCaches();
      verify(events, times(1)).onConfigApplyFailure(any());
      assertSame(incumbent, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(1, gpuConstruction.constructed().size());
    }
  }

  @Test
  void unknownVramPreservesExplicitGpuRequestProceedBehavior() throws Exception {
    InferenceConfig incumbent = config(0);
    InferenceConfig candidate = config(1);

    try (MockedConstruction<GpuCapabilitiesService> gpuConstruction =
            mockConstruction(
                GpuCapabilitiesService.class,
                (gpu, context) -> when(gpu.snapshot()).thenReturn(gpuSnapshot(null)));
        MockedConstruction<LlamaServerOps> serverConstruction =
            mockConstruction(LlamaServerOps.class);
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = new InferenceLifecycleManager(executors, incumbent)) {
      LlamaServerOps serverOps = serverConstruction.constructed().getFirst();
      manager.switchToOnlineMode();
      clearInvocations(serverOps);

      manager.applyConfig(candidate, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      verify(serverOps).stopLlamaServer();
      verify(serverOps).resetCrashCounters();
      verify(serverOps).startLlamaServer();
      verify(serverOps).waitForServerHealth();
      assertSame(candidate, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(1, gpuConstruction.constructed().size());
    }
  }

  @Test
  void failedCandidateAndFailedRollbackLeaveRestoredConfigOffline() throws Exception {
    InferenceConfig incumbent = config(0, 4096);
    InferenceConfig candidate = config(0, 8192);
    InferenceTelemetryEvents events = mock(InferenceTelemetryEvents.class);

    try (MockedConstruction<GpuCapabilitiesService> gpuConstruction =
            mockConstruction(GpuCapabilitiesService.class);
        MockedConstruction<LlamaServerOps> serverConstruction =
            mockConstruction(LlamaServerOps.class);
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = new InferenceLifecycleManager(executors, incumbent, events)) {
      LlamaServerOps serverOps = serverConstruction.constructed().getFirst();
      manager.switchToOnlineMode();
      clearInvocations(serverOps, events);
      doThrow(
              new ModeTransitionException(
                  ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT,
                  "candidate health failed"),
              new ModeTransitionException(
                  ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT,
                  "rollback health failed"))
          .when(serverOps)
          .waitForServerHealth();

      ModeTransitionException failure =
          assertThrows(
              ModeTransitionException.class,
              () ->
                  manager.applyConfig(
                      candidate, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS));

      assertEquals(ModeTransitionException.Reason.CONFIG_APPLY_FAILED, failure.reason());
      assertTrue(failure.getMessage().contains("Rollback failed"));
      assertTrue(failure.getMessage().contains("rollback health failed"));
      assertSame(incumbent, manager.currentConfig());
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      verify(events, times(1)).onConfigApplyFailure(any());
      verify(serverOps, times(2)).startLlamaServer();
      verify(serverOps, times(2)).waitForServerHealth();
      assertEquals(1, gpuConstruction.constructed().size());
    }
  }

  @Test
  void rollbackVramRefusalLeavesRestoredGpuConfigOffline() throws Exception {
    InferenceConfig incumbent = config(1, 4096);
    InferenceConfig candidate = config(2, 8192);
    long enoughVram = HardwareProfile.MINIMUM_VRAM_FOR_GGUF;
    long insufficientVram = enoughVram - 1;

    try (MockedConstruction<GpuCapabilitiesService> gpuConstruction =
            mockConstruction(
                GpuCapabilitiesService.class,
                (gpu, context) ->
                    when(gpu.snapshot())
                        .thenReturn(
                            gpuSnapshot(enoughVram),
                            gpuSnapshot(enoughVram),
                            gpuSnapshot(insufficientVram)));
        MockedConstruction<LlamaServerOps> serverConstruction =
            mockConstruction(LlamaServerOps.class);
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = new InferenceLifecycleManager(executors, incumbent)) {
      LlamaServerOps serverOps = serverConstruction.constructed().getFirst();
      manager.switchToOnlineMode();
      clearInvocations(serverOps);
      doThrow(
              new ModeTransitionException(
                  ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT,
                  "candidate health failed"))
          .when(serverOps)
          .waitForServerHealth();

      ModeTransitionException failure =
          assertThrows(
              ModeTransitionException.class,
              () ->
                  manager.applyConfig(
                      candidate, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS));

      assertEquals(ModeTransitionException.Reason.INSUFFICIENT_VRAM, failure.reason());
      assertTrue(failure.getMessage().contains("Rollback failed (VRAM)"));
      assertSame(incumbent, manager.currentConfig());
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      verify(serverOps).startLlamaServer();
      verify(serverOps).waitForServerHealth();
      assertEquals(1, gpuConstruction.constructed().size());
    }
  }

  private InferenceConfig config(int gpuLayers) throws Exception {
    return config(gpuLayers, 4096);
  }

  private InferenceConfig config(int gpuLayers, int contextSize) throws Exception {
    Path executable = directory.resolve("llama-server.exe");
    Path model = directory.resolve("model.gguf");
    if (Files.notExists(executable)) Files.writeString(executable, "test executable");
    if (Files.notExists(model)) Files.writeString(model, "test model");
    return new InferenceConfig(executable, model, null, 18081, contextSize, gpuLayers, false);
  }

  private static GpuCapabilities gpuSnapshot(Long totalVramBytes) {
    var effective =
        new GpuCapabilities.Effective(
            true,
            "test",
            GpuCapabilities.Confidence.HIGH,
            null,
            null,
            null,
            1,
            totalVramBytes,
            totalVramBytes,
            0L,
            GpuCapabilities.Cuda.unknown());
    return new GpuCapabilities(null, null, effective);
  }
}

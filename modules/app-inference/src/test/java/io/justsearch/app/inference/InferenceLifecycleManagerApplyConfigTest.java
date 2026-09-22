/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.InferenceLifecycleManager.ConfigApplyDisposition;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

final class InferenceLifecycleManagerApplyConfigTest {
  @TempDir Path directory;
  private ConfigStore previousStore;

  @BeforeEach
  void captureGlobalStore() {
    previousStore = ConfigStore.globalOrNull();
  }

  @AfterEach
  void restoreGlobalStore() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  void candidatePublishesOnlyAfterStartAndHealthComplete() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.onStart =
          request -> {
            if (request.context().inference() == b) assertSame(a, manager.currentConfig());
          };
      server.onHealth =
          result -> {
            if (result.context().inference() == b) assertSame(a, manager.currentConfig());
          };

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.APPLIED, result.disposition());
      assertNull(result.failure());
      assertSame(b, result.configuration());
      assertSame(b, manager.currentConfig());
      assertEquals(expectedHash(b, resolvedB), result.declaredConfigHash());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
    }
  }

  @Test
  void candidateFailureRollsBackUsingActualAContextDespiteGlobalC() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    ResolvedConfig resolvedC = resolved("c", false);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      installGlobal(resolvedC);
      server.failNextHealth(healthFailure("candidate B failed"));

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.ROLLED_BACK_TO_A, result.disposition());
      assertNotNull(result.failure());
      assertSame(a, result.configuration());
      assertSame(a, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(3, server.starts.size());
      assertSame(resolvedB, server.starts.get(1).context().resolved());
      assertSame(resolvedA, server.starts.get(2).context().resolved());
      assertSame(a, server.starts.get(2).context().inference());
    }
  }

  @Test
  void rollbackReplacesApplyOnlyDesiredConfigWithServingAAndRetainsStrictPolicy()
      throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig desiredC = config(0, 12288);
    InferenceConfig candidateD = config(0, 16384);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedC = resolved("c", true);
    ResolvedConfig resolvedD = resolved("d", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var configured = manager.applyResolvedConfig(
          desiredC, resolvedC, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      assertEquals(ConfigApplyDisposition.CONFIGURED, configured.disposition());
      assertSame(desiredC, manager.currentConfig());
      assertEquals(4096, manager.configuredContextTokens(),
          "the active launch remains A while APPLY_ONLY retains desired C");
      server.failNextHealth(healthFailure("candidate D failed"));

      var rolledBack = manager.applyResolvedConfig(
          candidateD, resolvedD, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.ROLLED_BACK_TO_A, rolledBack.disposition());
      assertSame(a, rolledBack.configuration());
      assertSame(a, manager.currentConfig());
      assertEquals(4096, manager.configuredContextTokens());
      LlamaServerOps.StartRequest restoredA = server.starts.get(2);
      assertSame(resolvedA, restoredA.context().resolved());
      assertEquals(
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          restoredA.adoptionPolicy());

      manager.switchToIndexingMode();
      manager.switchToOnlineMode();

      LlamaServerOps.StartRequest laterA = server.starts.getLast();
      assertSame(a, laterA.context().inference());
      assertSame(resolvedA, laterA.context().resolved());
      assertEquals(
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          laterA.adoptionPolicy());
    }
  }

  @Test
  void failedCandidateAndFailedRollbackPreserveBothCausesAndLeaveOffline() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig desiredC = config(0, 12288);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedC = resolved("c", true);
    ResolvedConfig resolvedB = resolved("b", true);
    InferenceTelemetryEvents events = mock(InferenceTelemetryEvents.class);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, events)) {
      manager.switchToOnlineMode();
      manager.applyResolvedConfig(
          desiredC, resolvedC, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      clearInvocations(events);
      server.failNextHealth(healthFailure("candidate B failed"));
      server.failNextHealth(healthFailure("rollback A failed"));

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.LEFT_OFFLINE, result.disposition());
      assertSame(desiredC, result.configuration());
      assertSame(desiredC, manager.currentConfig());
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertNotNull(result.failure());
      assertEquals(ModeTransitionException.Reason.CONFIG_APPLY_FAILED, result.failure().reason());
      Throwable combined = result.failure().getCause();
      assertNotNull(combined);
      assertTrue(combined.getMessage().contains("rollback A failed"));
      assertTrue(combined.getCause().getMessage().contains("rollback A failed"));
      assertEquals(1, combined.getSuppressed().length);
      assertTrue(combined.getSuppressed()[0].getMessage().contains("candidate B failed"));
      verify(events, times(1)).onConfigApplyFailure(any());
    }
  }

  @Test
  void insufficientVramRefusesBeforeStopOrCacheClearAndEmitsOneFailure() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(1, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    InferenceTelemetryEvents events = mock(InferenceTelemetryEvents.class);
    installGlobal(resolvedA);

    try (MockedConstruction<GpuCapabilitiesService> gpuConstruction =
            mockConstruction(
                GpuCapabilitiesService.class,
                (gpu, context) -> when(gpu.snapshot()).thenReturn(gpuSnapshot(1L)));
        var server = new FakeServer();
        MockedConstruction<TokenEndpointOps> tokenConstruction =
            mockConstruction(TokenEndpointOps.class);
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, events)) {
      manager.switchToOnlineMode();
      LlamaServerOps serverOps = server.mock();
      TokenEndpointOps tokenOps = tokenConstruction.constructed().getFirst();
      clearInvocations(serverOps, tokenOps, events);

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.UNCHANGED, result.disposition());
      assertNotNull(result.failure());
      assertEquals(ModeTransitionException.Reason.INSUFFICIENT_VRAM, result.failure().reason());
      verify(serverOps, never()).stopLlamaServer();
      verify(tokenOps, never()).clearCaches();
      verify(events, times(1)).onConfigApplyFailure(any());
      assertSame(a, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(1, gpuConstruction.constructed().size());
    }
  }

  @Test
  void unknownVramStillAllowsExplicitGpuCandidate() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(1, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (MockedConstruction<GpuCapabilitiesService> gpuConstruction =
            mockConstruction(
                GpuCapabilitiesService.class,
                (gpu, context) -> when(gpu.snapshot()).thenReturn(gpuSnapshot(null)));
        var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.APPLIED, result.disposition());
      assertSame(b, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(1, gpuConstruction.constructed().size());
      assertEquals(2, server.starts.size());
      assertEquals(1, server.starts.getLast().effectiveGpuLayers());
    }
  }

  @Test
  void failedIncumbentStopNeverStartsCandidate() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.failNextStop(new IllegalStateException("incumbent A survived stop"));

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.LEFT_OFFLINE, result.disposition());
      assertSame(a, manager.currentConfig());
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertEquals(1, server.starts.size(), "candidate B must never start after failed A stop");
      assertSame(a, server.active.get().context().inference());
    }
  }

  @Test
  void failedCandidateCleanupDoesNotAttemptIncumbentRestart() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.onHealth =
          result -> {
            if (result.context().inference() == b) {
              server.failNextStop(new IllegalStateException("candidate B cleanup failed"));
            }
          };
      server.failNextHealth(healthFailure("candidate B failed"));

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.LEFT_OFFLINE, result.disposition());
      assertSame(a, manager.currentConfig());
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertEquals(2, server.starts.size(), "A must not restart while candidate B cleanup failed");
      assertSame(b, server.active.get().context().inference());
    }
  }

  @Test
  void wrongManagedCandidateHashIsRefusedAndIncumbentRestored() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.wrongNextHash.set(true);

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.ROLLED_BACK_TO_A, result.disposition());
      assertNotNull(result.failure());
      assertSame(a, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(3, server.starts.size());
    }
  }

  @Test
  void applyOnlyRejectsInvalidWithoutChangeAndRetainsValidResolvedSnapshotForLaterStart()
      throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig invalid = new InferenceConfig(
        directory.resolve("missing-server.exe"),
        directory.resolve("missing-model.gguf"), null, 18081, 4096, 0, false);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    ResolvedConfig resolvedC = resolved("c", false);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      ModeTransitionException invalidFailure = assertThrows(
          ModeTransitionException.class,
          () -> manager.applyResolvedConfig(
              invalid, resolvedB, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY));
      assertEquals(ModeTransitionException.Reason.INVALID_CONFIG, invalidFailure.reason());
      assertSame(a, manager.currentConfig());
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());

      var configured = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      assertEquals(ConfigApplyDisposition.CONFIGURED, configured.disposition());
      assertSame(b, manager.currentConfig());
      installGlobal(resolvedC);

      manager.switchToOnlineMode();

      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(1, server.starts.size());
      assertSame(b, server.starts.getFirst().context().inference());
      assertSame(resolvedB, server.starts.getFirst().context().resolved());
    }
  }

  @Test
  void vduEnterAndExitPreserveCapturedResolvedContextAndStrictPolicy() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.applyResolvedConfig(b, resolvedB, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      manager.switchToOnlineMode();

      manager.enterVduMode();
      manager.exitVduMode();

      assertSame(b, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(3, server.starts.size());
      LlamaServerOps.StartRequest enter = server.starts.get(1);
      LlamaServerOps.StartRequest exit = server.starts.get(2);
      assertTrue(enter.context().inference().vduMode());
      assertFalse(exit.context().inference().vduMode());
      assertSame(resolvedB, enter.context().resolved());
      assertSame(resolvedB, exit.context().resolved());
      assertEquals(
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          enter.adoptionPolicy());
      assertEquals(
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          exit.adoptionPolicy());
    }
  }

  @Test
  void rollbackRetriesFormerGpuIncumbentWithoutSecondAdmissionGate() throws Exception {
    InferenceConfig a = config(1, 4096);
    InferenceConfig b = config(2, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    long enoughVram = HardwareProfile.MINIMUM_VRAM_FOR_GGUF;
    installGlobal(resolvedA);

    try (MockedConstruction<GpuCapabilitiesService> gpuConstruction =
            mockConstruction(
                GpuCapabilitiesService.class,
                (gpu, context) ->
                    when(gpu.snapshot())
                        .thenReturn(
                            gpuSnapshot(enoughVram),
                            gpuSnapshot(enoughVram),
                            gpuSnapshot(enoughVram - 1)));
        var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.failNextHealth(healthFailure("candidate B failed"));

      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(ConfigApplyDisposition.ROLLED_BACK_TO_A, result.disposition());
      assertSame(a, manager.currentConfig());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      GpuCapabilitiesService gpu = gpuConstruction.constructed().getFirst();
      verify(gpu, times(2)).snapshot();
      assertEquals(3, server.starts.size());
      assertSame(a, server.starts.get(2).context().inference());
    }
  }

  @Test
  void recoveryRejectsReplacedOwnersAndCannotRestartAfterPreservingChildOnClose() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
      try {
        manager.switchToOnlineMode();
        server.recovery.accept(() -> false);
        verify(server.mock(), never()).recoverActiveServer();
        server.recovery.accept(() -> true);
        verify(server.mock(), times(1)).recoverActiveServer();
      } finally {
        manager.setStopServerOnClose(false);
        manager.close();
      }
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertNotNull(server.active.get(), "preserved child ownership remains available for adoption");
      server.recovery.accept(() -> true);
      verify(server.mock(), times(1)).recoverActiveServer();
    }
  }

  @Test
  void queuedRecoveryWaitsForApplyThenRejectsItsReplacedPhysicalOwner() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    var candidateHealth = new CountDownLatch(1);
    var releaseHealth = new CountDownLatch(1);
    var recoveryEntered = new CountDownLatch(1);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      manager.switchToOnlineMode();
      var incumbent = server.active.get();
      server.onHealth = result -> {
        if (result.context().inference() != b) return;
        candidateHealth.countDown();
        try {
          if (!releaseHealth.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Test did not release candidate health");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      };
      var apply = tasks.submit(() -> manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS));
      try {
        assertTrue(candidateHealth.await(5, TimeUnit.SECONDS));
        var recovery = tasks.submit(() -> {
          recoveryEntered.countDown();
          server.recovery.accept(() -> server.active.get() == incumbent);
        });
        assertTrue(recoveryEntered.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> recovery.get(100, TimeUnit.MILLISECONDS),
            "recovery must wait for the manager lifecycle lock even while apply is transitioning");
        releaseHealth.countDown();
        assertEquals(ConfigApplyDisposition.APPLIED, apply.get(5, TimeUnit.SECONDS).disposition());
        recovery.get(5, TimeUnit.SECONDS);
        verify(server.mock(), never()).recoverActiveServer();
        assertSame(b, manager.currentConfig());
      } finally {
        releaseHealth.countDown();
      }
    }
  }

  @Test
  void failedColdStartupCleansItsUnhealthyChildBeforeReturning() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      server.failNextHealth(healthFailure("unhealthy startup"));
      assertThrows(ModeTransitionException.class, manager::switchToOnlineMode);
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertNull(server.active.get());
      verify(server.mock()).stopLlamaServer();
    }
  }

  @Test
  void failedColdStartupCleanupRetainsOwnershipAndReportsBothFailuresOffline() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      server.failNextHealth(healthFailure("unhealthy startup"));
      server.onHealth = ignored -> server.failNextStop(new IllegalStateException("child still alive"));
      var failure = assertThrows(ModeTransitionException.class, manager::switchToOnlineMode);
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertNotNull(server.active.get());
      assertTrue(failure.getMessage().contains("unhealthy startup"));
      assertTrue(failure.getMessage().contains("child still alive"));
      assertEquals(1, failure.getCause().getSuppressed().length);
    }
  }

  @Test
  void failedStopForIndexingCannotRepublishOnlineWithoutHealth() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var incumbent = server.active.get();
      server.failNextStop(new IllegalStateException("child still alive"));
      assertThrows(ModeTransitionException.class, manager::switchToIndexingMode);
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertSame(incumbent, server.active.get(), "failed termination retains cleanup ownership");
      assertEquals(1, server.starts.size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void exhaustedRecoveryCleansChildOrRetainsFailedCleanupOwnershipOffline(boolean refuseStop)
      throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var incumbent = server.active.get();
      if (refuseStop) server.failNextStop(new IllegalStateException("child still alive"));
      server.terminal.accept(() -> true);
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      verify(server.mock()).stopLlamaServer();
      if (refuseStop) {
        assertSame(incumbent, server.active.get());
        assertTrue(manager.lastFailure().orElseThrow().detail().contains("child still alive"));
      } else {
        assertNull(server.active.get());
      }
    }
  }

  private InferenceLifecycleManager manager(
      io.justsearch.core.execution.TestEngineExecutors executors,
      InferenceConfig config,
      ResolvedConfig resolved,
      InferenceTelemetryEvents events) {
    return new InferenceLifecycleManager(
        executors, config, events, ManagedChildRegistry.noop(), resolved);
  }

  private ResolvedConfig resolved(String identity, boolean gpuAllowed) {
    return TestResolvedConfigHelper.fromEntries(
        java.util.Map.of(
            "justsearch.llm.reasoning_budget", Integer.toString(identity.charAt(0)),
            "policy.gpu_acceleration_enabled", Boolean.toString(gpuAllowed)));
  }

  private void installGlobal(ResolvedConfig config) {
    ConfigStore.setGlobal(new ConfigStore(config));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void applyOnlyVisionCapabilityDescribesServingIncumbent(boolean incumbentHasVision)
      throws Exception {
    InferenceConfig base = config(0, 4096);
    Path projection = Files.writeString(directory.resolve("mmproj.gguf"), "test projection");
    InferenceConfig vision = new InferenceConfig(base.serverExecutable(), base.modelPath(),
        projection, base.serverPort(), base.contextSize(), base.gpuLayers(), false);
    InferenceConfig a = incumbentHasVision ? vision : base;
    InferenceConfig b = incumbentHasVision ? base : vision;
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var result = manager.applyResolvedConfig(
          b, resolvedB, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      assertEquals(ConfigApplyDisposition.CONFIGURED, result.disposition());
      assertSame(b, manager.currentConfig());
      assertSame(a, server.active.get().context().inference());
      assertEquals(incumbentHasVision, manager.hasVisionCapability());
    }
  }

  @Test
  void failedDetachCleanupRetainsCandidateOwnershipAndCannotReadoptExternal() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var incumbent = new LlamaServerOps.StartResult(
          new LlamaServerConfigContext(a, resolvedA),
          LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL,
          LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL, null);
      server.active.set(incumbent);
      when(server.mock().isExternalServerActive()).thenReturn(true);
      var candidateFailure = healthFailure("detach candidate unhealthy");
      var cleanupFailure = new IllegalStateException("detach child remains alive");
      server.failNextHealth(candidateFailure);
      server.onHealth = result -> server.failNextStop(cleanupFailure);

      var failure = assertThrows(ModeTransitionException.class, manager::detachExternalServer);

      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertSame(a, manager.currentConfig());
      assertEquals(2, server.starts.size(), "cleanup refusal must prevent external readoption");
      var retained = server.active.get();
      assertNotNull(retained);
      assertSame(resolvedA, retained.context().resolved());
      assertTrue(retained.context().inference().serverPort() != a.serverPort());
      assertEquals(LlamaServerOps.StartDisposition.LAUNCHED_MANAGED, retained.disposition());
      assertEquals(LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          retained.adoptionPolicy());
      assertTrue(failure.getMessage().contains("detach"));
      assertNotNull(failure.getCause());
      assertTrue(java.util.Arrays.asList(failure.getCause().getSuppressed()).contains(candidateFailure));
      assertSame(cleanupFailure, failure.getCause().getCause());
    }
  }

  private InferenceConfig config(int gpuLayers, int contextSize) throws Exception {
    Path executable = directory.resolve("llama-server.exe");
    Path model = directory.resolve("model.gguf");
    if (Files.notExists(executable)) Files.writeString(executable, "test executable");
    if (Files.notExists(model)) Files.writeString(model, "test model");
    return new InferenceConfig(executable, model, null, 18081, contextSize, gpuLayers, false);
  }

  private static String expectedHash(InferenceConfig config, ResolvedConfig resolved) {
    int effectiveLayers = resolved.ai().gpuAccelerationAllowed() ? config.gpuLayers() : 0;
    return ManagedLlamaConfigIdentity.declaredHash(config, resolved, effectiveLayers);
  }

  private static ModeTransitionException healthFailure(String detail) {
    return new ModeTransitionException(ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT, detail);
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

  private static final class FakeServer implements AutoCloseable {
    private final AtomicReference<LlamaServerOps.StartResult> active = new AtomicReference<>();
    private final List<LlamaServerOps.StartRequest> starts = new ArrayList<>();
    private final ArrayDeque<ModeTransitionException> healthFailures = new ArrayDeque<>();
    private final AtomicReference<RuntimeException> stopFailure = new AtomicReference<>();
    private final AtomicBoolean wrongNextHash = new AtomicBoolean();
    private final MockedConstruction<LlamaServerOps> construction;
    private Consumer<LlamaServerOps.StartRequest> onStart = request -> {};
    private Consumer<LlamaServerOps.StartResult> onHealth = result -> {};
    private Consumer<BooleanSupplier> recovery;
    private Consumer<BooleanSupplier> terminal;

    @SuppressWarnings("unchecked") // Capture the typed manager callback at the constructor seam.
    private FakeServer() {
      construction =
          mockConstruction(
              LlamaServerOps.class,
              (server, context) -> {
                recovery = (Consumer<BooleanSupplier>) context.arguments().get(6);
                terminal = (Consumer<BooleanSupplier>) context.arguments().get(7);
                when(server.startLlamaServer(any()))
                    .thenAnswer(
                        invocation -> {
                          LlamaServerOps.StartRequest request = invocation.getArgument(0);
                          starts.add(request);
                          onStart.accept(request);
                          String hash = wrongNextHash.getAndSet(false)
                              ? "wrong-managed-hash"
                              : expectedHash(request.context().inference(), request.context().resolved());
                          var result =
                              new LlamaServerOps.StartResult(
                                  request.context(),
                                  request.adoptionPolicy(),
                                  LlamaServerOps.StartDisposition.LAUNCHED_MANAGED,
                                  hash);
                          active.set(result);
                          return result;
                        });
                doAnswer(
                        invocation -> {
                          RuntimeException failure = stopFailure.getAndSet(null);
                          if (failure != null) throw failure;
                          active.set(null);
                          return null;
                        })
                    .when(server)
                    .stopLlamaServer();
                when(server.activeStartResult())
                    .thenAnswer(invocation -> Optional.ofNullable(active.get()));
                doAnswer(
                        invocation -> {
                          LlamaServerOps.StartResult result = invocation.getArgument(0);
                          onHealth.accept(result);
                          ModeTransitionException failure = healthFailures.pollFirst();
                          if (failure != null) throw failure;
                          return null;
                        })
                    .when(server)
                    .waitForServerHealth(any());
              });
    }

    private LlamaServerOps mock() {
      return construction.constructed().getFirst();
    }

    private void failNextHealth(ModeTransitionException failure) {
      healthFailures.addLast(failure);
    }

    private void failNextStop(RuntimeException failure) {
      stopFailure.set(failure);
    }

    @Override
    public void close() {
      construction.close();
    }
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.InferenceLifecycleManager.ConfigApplyDisposition;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
  void preparedCandidateStaysPrivateUntilAssignmentOnlyInstallAndFencesRecovery()
      throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var incumbent = server.active.get();

      var prepared = manager.prepareResolvedConfig(b, resolvedB);

      assertSame(a, manager.currentConfig());
      assertEquals(4096, manager.configuredContextTokens(),
          "candidate B must not become the serving projection before outer commit");
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(expectedHash(b, resolvedB), prepared.declaredConfigHash());
      assertSame(b, server.active.get().context().inference());
      assertThrows(IllegalStateException.class, () -> manager.countTokens("held request"),
          "token endpoints must not reach the private candidate");
      assertThrows(IllegalStateException.class,
          () -> manager.askQuestion("context", "question", 64),
          "chat must refuse before it can enqueue against the private candidate");
      server.failure.accept(() -> server.active.get() == incumbent);

      prepared.withLifecycleLock(() -> {
        prepared.validateForCommit();
        prepared.installAfterSettingsCommit();
      });
      prepared.retireAfterSettingsCommit();

      assertSame(b, manager.currentConfig());
      assertEquals(8192, manager.configuredContextTokens());
      server.failure.accept(() -> true);
    }
  }

  @Test
  void preparedModelSwitchPublishesCandidateIdentityInsteadOfIncumbentProps() throws Exception {
    InferenceConfig a = config(0, 4096);
    Path bModel = directory.resolve("candidate-b.gguf");
    Files.writeString(bModel, "test candidate");
    InferenceConfig b = new InferenceConfig(a.serverExecutable(), bModel, null,
        a.serverPort(), 8192, 0, false);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.propsObserver.onModelIdObserved("model.gguf", server.active.get().context());
      assertEquals("model.gguf", manager.lastKnownModelId());
      server.onHealth = result -> {
        if (result.context().inference() == b) {
          server.propsObserver.onModelIdObserved("candidate-b.gguf", result.context());
          assertEquals("model.gguf", manager.lastKnownModelId(),
              "private B's props must not publish before settings commitment");
        }
      };

      var prepared = manager.prepareResolvedConfig(b, resolvedB);
      assertEquals("model.gguf", manager.lastKnownModelId(),
          "candidate preparation must not expose B before settings commitment");
      prepared.withLifecycleLock(prepared::installAfterSettingsCommit);
      prepared.retireAfterSettingsCommit();

      assertEquals("candidate-b.gguf", manager.lastKnownModelId());
      assertSame(b, server.active.get().context().inference());
    }
  }

  @Test
  void preparedCandidateDeathRefusesCommitAndRestoresIncumbent() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var prepared = manager.prepareResolvedConfig(b, resolvedB);
      server.candidateAlive.set(false);
      assertThrows(IllegalStateException.class,
          () -> prepared.withLifecycleLock(prepared::validateForCommit));
      server.candidateAlive.set(true);
      prepared.abort();
      assertSame(a, manager.currentConfig());
      assertSame(a, server.active.get().context().inference());
    }
  }

  @Test
  void precommitCrashCallbackInvalidatesCandidateWithoutRecoveringIt() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var prepared = manager.prepareResolvedConfig(b, resolvedB);
      server.failure.accept(() -> true);
      assertThrows(IllegalStateException.class,
          () -> prepared.withLifecycleLock(prepared::validateForCommit));
      prepared.abort();
    }
  }

  @Test
  void coldEnablePublishesModeAtCommitBeforeRetirement() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      var prepared = manager.prepareResolvedConfig(b, resolvedB, true);

      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertSame(a, manager.currentConfig());
      assertSame(b, server.active.get().context().inference());
      prepared.withLifecycleLock(prepared::installAfterSettingsCommit);
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertSame(b, manager.currentConfig());

      prepared.retireAfterSettingsCommit();

      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertSame(b, server.active.get().context().inference());
      assertEquals(1, server.starts.size());
    }
  }

  @Test
  void restartIfOnlineRefreshRetainsOfflineConfigurationWithoutStartingServer() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      var prepared = manager.prepareResolvedConfig(b, resolvedB, true, true);
      assertFalse(prepared.targetsOnline());
      assertNull(server.active.get());
      assertTrue(server.starts.isEmpty());
      assertSame(a, manager.currentConfig());

      prepared.withLifecycleLock(prepared::installAfterSettingsCommit);
      prepared.retireAfterSettingsCommit();
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertSame(b, manager.currentConfig());
      assertTrue(server.starts.isEmpty());
    }
  }

  @Test
  void disableRetainsIncumbentUntilCommitThenRetiresIt() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var incumbent = server.active.get();

      var prepared = manager.prepareResolvedConfig(b, resolvedB, false);

      assertSame(incumbent, server.active.get());
      assertSame(a, manager.currentConfig());
      assertNull(prepared.declaredConfigHash());
      prepared.withLifecycleLock(prepared::installAfterSettingsCommit);
      assertSame(incumbent, server.active.get());
      assertSame(b, manager.currentConfig());
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());

      prepared.retireAfterSettingsCommit();

      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertNull(server.active.get());
      verify(server.mock(), times(1)).stopLlamaServer();
    }
  }

  @Test
  void lifecycleCallbackFencesRecoveryBeforePublicationLockAcquisition() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    var ownerLocked = new CountDownLatch(1);
    var releaseOwner = new CountDownLatch(1);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      manager.switchToOnlineMode();
      var prepared = manager.prepareResolvedConfig(b, resolvedB);
      var guarded = tasks.submit(() -> prepared.withLifecycleLock(() -> {
        ownerLocked.countDown();
        try {
          if (!releaseOwner.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
        prepared.installAfterSettingsCommit();
      }));
      assertTrue(ownerLocked.await(5, TimeUnit.SECONDS));
      var recovery = tasks.submit(() -> server.failure.accept(() -> true));
      assertThrows(TimeoutException.class, () -> recovery.get(100, TimeUnit.MILLISECONDS));
      releaseOwner.countDown();
      guarded.get(5, TimeUnit.SECONDS);
      recovery.get(5, TimeUnit.SECONDS);
      prepared.retireAfterSettingsCommit();
    } finally {
      releaseOwner.countDown();
    }
  }

  @Test
  void abortPreparedCandidateStopsBAndRestoresCapturedA() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();

      var prepared = manager.prepareResolvedConfig(b, resolvedB);
      prepared.abort();

      assertSame(a, manager.currentConfig());
      assertSame(a, server.active.get().context().inference());
      assertSame(resolvedA, server.active.get().context().resolved());
      assertEquals(3, server.starts.size());
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
    }
  }

  @Test
  void recoveryQueuedDuringPreparationCannotOvertakeTheStagedCandidate() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    var candidateHealth = new CountDownLatch(1);
    var releaseHealth = new CountDownLatch(1);
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

      var preparing = tasks.submit(() -> manager.prepareResolvedConfig(b, resolvedB));
      assertTrue(candidateHealth.await(5, TimeUnit.SECONDS));
      var recovery = tasks.submit(() -> server.failure.accept(() -> server.active.get() == incumbent));
      assertThrows(
          TimeoutException.class,
          () -> recovery.get(100, TimeUnit.MILLISECONDS),
          "recovery must wait for candidate preparation to leave the lifecycle lock");

      releaseHealth.countDown();
      var prepared = preparing.get(5, TimeUnit.SECONDS);
      recovery.get(5, TimeUnit.SECONDS);
      prepared.abort();
    } finally {
      releaseHealth.countDown();
    }
  }

  @Test
  void failedPrecommitCandidateRestoresABeforeReturningFailure() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);

    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.failNextHealth(healthFailure("candidate B failed"));

      var failure = assertThrows(
          ModeTransitionException.class, () -> manager.prepareResolvedConfig(b, resolvedB));

      assertTrue(failure.getMessage().contains("candidate B failed"));
      assertSame(a, manager.currentConfig());
      assertSame(a, server.active.get().context().inference());
      assertEquals(3, server.starts.size());
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
  void managedFailureRejectsReplacedOwnerAndCannotMutateAfterClose() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
      try {
        manager.switchToOnlineMode();
        server.failure.accept(() -> false);
        assertEquals(Mode.ONLINE, manager.getCurrentMode());
        server.failure.accept(() -> true);
        assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      } finally {
        manager.setStopServerOnClose(false);
        manager.close();
      }
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      assertNotNull(server.active.get(), "preserved child ownership remains available for adoption");
      server.failure.accept(() -> true);
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
    }
  }

  @Test
  void queuedFailureSignalWaitsForApplyThenRejectsItsReplacedPhysicalOwner() throws Exception {
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
          server.failure.accept(() -> server.active.get() == incumbent);
        });
        assertTrue(recoveryEntered.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> recovery.get(100, TimeUnit.MILLISECONDS),
            "recovery must wait for the manager lifecycle lock even while apply is transitioning");
        releaseHealth.countDown();
        assertEquals(ConfigApplyDisposition.APPLIED, apply.get(5, TimeUnit.SECONDS).disposition());
        recovery.get(5, TimeUnit.SECONDS);
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

  @Test
  void managedFailureOnlyDemotesAndLeavesPhysicalCleanupToAdmittedRecovery() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      var incumbent = server.active.get();
      server.failure.accept(() -> true);
      assertEquals(Mode.OFFLINE, manager.getCurrentMode());
      verify(server.mock(), never()).stopLlamaServer();
      assertSame(incumbent, server.active.get());
    }
  }

  @Test
  void admittedRecoveryRestartsServingAButNeverNewDesiredB() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      manager.applyResolvedConfig(b, resolvedB, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "B", 0));

      var result = manager.recoverComponent(request, ignored -> "A");

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertEquals(2, server.starts.size(), "one admission must cause exactly one launch");
      assertSame(a, server.starts.getLast().context().inference());
      assertSame(resolvedA, server.starts.getLast().context().resolved());
      assertEquals(1, result.observation().recoveryAttempts());
    }
  }

  @Test
  void admittedRecoveryDrainsARealRequestBeforeOnePhysicalReplacement() throws Exception {
    var requestEntered = new CountDownLatch(1);
    var releaseRequest = new CountDownLatch(1);
    HttpServer tokenServer = blockingTokenServer(requestEntered, releaseRequest);
    InferenceConfig base = config(0, 4096);
    InferenceConfig a = new InferenceConfig(base.serverExecutable(), base.modelPath(), null,
        tokenServer.getAddress().getPort(), base.contextSize(), base.gpuLayers(), false);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      manager.switchToOnlineMode();
      var requestUse = tasks.submit(() -> manager.countTokens("held request"));
      assertTrue(requestEntered.await(5, TimeUnit.SECONDS));
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));
      var recovery = tasks.submit(() -> manager.recoverComponent(request, ignored -> "A"));
      assertTrue(request.awaitBegun(5, TimeUnit.SECONDS));

      assertThrows(TimeoutException.class, () -> recovery.get(100, TimeUnit.MILLISECONDS));
      assertEquals(1, request.current().recoveryAttempts());
      verify(server.mock(), never()).stopLlamaServer();
      assertEquals(1, server.starts.size());

      releaseRequest.countDown();
      requestUse.get(5, TimeUnit.SECONDS);
      var result = recovery.get(5, TimeUnit.SECONDS);
      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      verify(server.mock(), times(1)).stopLlamaServer();
      assertEquals(2, server.starts.size(), "the admission launches exactly one replacement");
      assertEquals(1, result.observation().recoveryAttempts());
    } finally {
      releaseRequest.countDown();
      tokenServer.stop(0);
    }
  }

  @Test
  void cancelledRecoveryWaitingForARealRequestNeverStopsOrLaunches() throws Exception {
    var requestEntered = new CountDownLatch(1);
    var releaseRequest = new CountDownLatch(1);
    HttpServer tokenServer = blockingTokenServer(requestEntered, releaseRequest);
    InferenceConfig base = config(0, 4096);
    InferenceConfig a = new InferenceConfig(base.serverExecutable(), base.modelPath(), null,
        tokenServer.getAddress().getPort(), base.contextSize(), base.gpuLayers(), false);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      manager.switchToOnlineMode();
      var requestUse = tasks.submit(() -> manager.countTokens("held request"));
      assertTrue(requestEntered.await(5, TimeUnit.SECONDS));
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));
      var recovery = tasks.submit(() -> manager.recoverComponent(request, ignored -> "A"));
      assertTrue(request.awaitBegun(5, TimeUnit.SECONDS));

      request.cancel();
      releaseRequest.countDown();
      requestUse.get(5, TimeUnit.SECONDS);
      var result = recovery.get(5, TimeUnit.SECONDS);

      assertEquals(ComponentRecoveryAction.Outcome.SUPERSEDED, result.outcome());
      assertEquals(1, request.current().recoveryAttempts());
      verify(server.mock(), never()).stopLlamaServer();
      assertEquals(1, server.starts.size(), "cancelled drain must not launch a replacement");
    } finally {
      releaseRequest.countDown();
      tokenServer.stop(0);
    }
  }

  @Test
  void explicitIndexingStopDuringDrainSupersedesTheSameRetainedStartRequest() throws Exception {
    var requestEntered = new CountDownLatch(1);
    var releaseRequest = new CountDownLatch(1);
    HttpServer tokenServer = blockingTokenServer(requestEntered, releaseRequest);
    InferenceConfig base = config(0, 4096);
    InferenceConfig a = new InferenceConfig(base.serverExecutable(), base.modelPath(), null,
        tokenServer.getAddress().getPort(), base.contextSize(), base.gpuLayers(), false);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      manager.switchToOnlineMode();
      var requestUse = tasks.submit(() -> manager.countTokens("held request"));
      assertTrue(requestEntered.await(5, TimeUnit.SECONDS));
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));
      var recovery = tasks.submit(() -> manager.recoverComponent(request, ignored -> "A"));
      assertTrue(request.awaitBegun(5, TimeUnit.SECONDS));
      assertTrue(server.recoveryReserved.await(5, TimeUnit.SECONDS));
      LlamaServerOps.StartRequest retainedRecovery = server.retained.get();
      assertNotNull(retainedRecovery);

      manager.switchToIndexingMode(TransitionReason.USER_SWITCH);
      assertSame(retainedRecovery, server.retained.get(),
          "the explicit stop deliberately leaves the same retry context retained");
      assertEquals(Mode.INDEXING, manager.getCurrentMode());
      releaseRequest.countDown();
      requestUse.get(5, TimeUnit.SECONDS);
      var result = recovery.get(5, TimeUnit.SECONDS);

      assertEquals(ComponentRecoveryAction.Outcome.SUPERSEDED, result.outcome());
      assertEquals(Mode.INDEXING, manager.getCurrentMode());
      verify(server.mock(), times(1)).stopLlamaServer();
      assertEquals(1, server.starts.size(),
          "recovery must not reopen A after the explicit same-context stop");
    } finally {
      releaseRequest.countDown();
      tokenServer.stop(0);
    }
  }

  @Test
  void interruptedRequestDrainCountsFailureWithoutPhysicalReplacement() throws Exception {
    var requestEntered = new CountDownLatch(1);
    var releaseRequest = new CountDownLatch(1);
    HttpServer tokenServer = blockingTokenServer(requestEntered, releaseRequest);
    InferenceConfig base = config(0, 4096);
    InferenceConfig a = new InferenceConfig(base.serverExecutable(), base.modelPath(), null,
        tokenServer.getAddress().getPort(), base.contextSize(), base.gpuLayers(), false);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    var recoveryResult = new AtomicReference<ComponentRecoveryAction.Result>();
    var recoveryFailure = new AtomicReference<Throwable>();
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      manager.switchToOnlineMode();
      var requestUse = tasks.submit(() -> manager.countTokens("held request"));
      assertTrue(requestEntered.await(5, TimeUnit.SECONDS));
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));
      Thread recovery = Thread.ofVirtual().start(() -> {
        try {
          recoveryResult.set(manager.recoverComponent(request, ignored -> "A"));
        } catch (Throwable failure) {
          recoveryFailure.set(failure);
        }
      });
      assertTrue(request.awaitBegun(5, TimeUnit.SECONDS));

      recovery.interrupt();
      recovery.join(5_000);
      assertFalse(recovery.isAlive());
      assertNull(recoveryFailure.get());
      assertEquals(ComponentRecoveryAction.Outcome.FAILED, recoveryResult.get().outcome());
      assertEquals(1, recoveryResult.get().observation().recoveryAttempts());
      assertTrue(recoveryResult.get().observation().evidence().contains("drain interrupted"));
      verify(server.mock(), never()).stopLlamaServer();
      assertEquals(1, server.starts.size());

      releaseRequest.countDown();
      requestUse.get(5, TimeUnit.SECONDS);
    } finally {
      releaseRequest.countDown();
      tokenServer.stop(0);
    }
  }

  @Test
  void explicitIndexingDuringFailedPrelaunchDrainSupersedesRetainedInitialRequest()
      throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var lease = requestGateLease(manager);
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var attemptedA = new LlamaServerOps.StartRequest(
          new LlamaServerConfigContext(a, resolvedA),
          LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
      server.retained.set(attemptedA);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));
      var recovery = tasks.submit(() -> manager.recoverComponent(request, ignored -> "A"));
      assertTrue(request.awaitBegun(5, TimeUnit.SECONDS));
      assertTrue(server.recoveryReserved.await(5, TimeUnit.SECONDS));

      manager.switchToIndexingMode(TransitionReason.USER_SWITCH);
      assertSame(attemptedA, server.retained.get());
      lease.close();
      var result = recovery.get(5, TimeUnit.SECONDS);

      assertEquals(ComponentRecoveryAction.Outcome.SUPERSEDED, result.outcome());
      assertEquals(Mode.INDEXING, manager.getCurrentMode());
      verify(server.mock(), times(1)).stopLlamaServer();
      assertEquals(0, server.starts.size(),
          "recovery must not open failed initial A after explicit indexing intent");
    }
  }

  @Test
  void timeoutPublicationDoesNotCancelLateSameOwnerReady() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));
      server.onHealth = ignored -> request.publishTimeout();

      var result = manager.recoverComponent(request, ignored -> "A");

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertEquals(ComponentState.READY, result.observation().state());
      assertEquals(1, result.observation().recoveryAttempts());
    }
  }

  @Test
  void failedRetryKeepsClosedLifecycleCauseAndAddsTypedFailureEvidence() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.failure.accept(() -> true);
      server.failNextHealth(healthFailure("recovery health timeout"));
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));

      var result = manager.recoverComponent(request, ignored -> "A");

      assertEquals(ComponentRecoveryAction.Outcome.FAILED, result.outcome());
      assertEquals("inference.crashed", result.observation().reasonCode());
      assertTrue(LifecycleReasonCode.allowedCodes().contains(result.observation().reasonCode()));
      assertTrue(result.observation().evidence().contains("health_timeout"));
    }
  }

  @Test
  void retainedFailedInitialAIsPreferredAfterDesiredChangesToB() throws Exception {
    InferenceConfig a = config(0, 4096);
    InferenceConfig b = config(0, 8192);
    ResolvedConfig resolvedA = resolved("a", true);
    ResolvedConfig resolvedB = resolved("b", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      var attemptedA = new LlamaServerOps.StartRequest(
          new LlamaServerConfigContext(a, resolvedA),
          LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
      server.retained.set(attemptedA);
      manager.applyResolvedConfig(b, resolvedB, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "B", 0));

      var result = manager.recoverComponent(request, ignored -> "A");

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertEquals(1, server.starts.size());
      assertSame(a, server.starts.getFirst().context().inference());
      assertSame(resolvedA, server.starts.getFirst().context().resolved());
    }
  }

  @Test
  void foreignExternalOwnerRefusesBeforeAdmission() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      server.active.set(new LlamaServerOps.StartResult(
          new LlamaServerConfigContext(a, resolvedA),
          LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL,
          LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL,
          null));
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));

      var result = manager.recoverComponent(request, ignored -> "A");

      assertEquals(ComponentRecoveryAction.Outcome.REFUSED, result.outcome());
      assertEquals(0, request.current().recoveryAttempts());
      assertEquals(0, server.starts.size());
    }
  }

  @Test
  void appliedVersionMismatchRefusesWithoutSpendingAttemptOrStoppingOwner() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "stale", "stale", 0));

      var result = manager.recoverComponent(request, ignored -> "physical-A");

      assertEquals(ComponentRecoveryAction.Outcome.REFUSED, result.outcome());
      assertEquals(0, request.current().recoveryAttempts());
      verify(server.mock(), never()).stopLlamaServer();
      assertEquals(1, server.starts.size());
    }
  }

  @Test
  void cancellationAfterPhysicalClosePreventsLaunchButKeepsSpentAttempt() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      manager.switchToOnlineMode();
      server.failure.accept(() -> true);
      var request = new TestRecoveryRequest(component(ComponentState.FAILED, "A", "A", 0));
      server.onStop = request::cancel;

      var result = manager.recoverComponent(request, ignored -> "A");

      assertEquals(ComponentRecoveryAction.Outcome.SUPERSEDED, result.outcome());
      assertEquals(1, request.current().recoveryAttempts());
      assertEquals(1, server.starts.size(), "cancelled recovery must not launch after close");
    }
  }

  @Test
  void autonomousActivationCannotStealRetainedFailureButExplicitUserActivationCan()
      throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop())) {
      server.retained.set(new LlamaServerOps.StartRequest(
          new LlamaServerConfigContext(a, resolvedA),
          LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL));
      when(server.mock().componentRecoveryPending()).thenAnswer(ignored -> server.retained.get() != null);

      var refused = assertThrows(
          ModeTransitionException.class,
          () -> manager.switchToOnlineMode(TransitionReason.AUTO_START));
      assertEquals(ModeTransitionException.Reason.ONLINE_START_FAILED, refused.reason());
      assertTrue(manager.componentRecoveryPending());
      assertEquals(0, server.starts.size());

      var procedureRefused = assertThrows(
          ModeTransitionException.class,
          () -> manager.switchToOnlineMode(TransitionReason.VDU_ENTER));
      assertEquals(ModeTransitionException.Reason.ONLINE_START_FAILED, procedureRefused.reason());
      assertEquals(0, server.starts.size());

      manager.switchToOnlineMode(TransitionReason.USER_SWITCH);
      assertEquals(Mode.ONLINE, manager.getCurrentMode());
      assertEquals(1, server.starts.size());
    }
  }

  @Test
  void queuedVduEnterCannotLaunchAfterAutonomousOwnerFails() throws Exception {
    InferenceConfig a = config(0, 4096);
    ResolvedConfig resolvedA = resolved("a", true);
    installGlobal(resolvedA);
    var healthEntered = new CountDownLatch(1);
    var releaseHealth = new CountDownLatch(1);
    try (var server = new FakeServer();
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var manager = manager(executors, a, resolvedA, InferenceTelemetryEvents.noop());
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      when(server.mock().componentRecoveryPending())
          .thenAnswer(ignored -> server.retained.get() != null);
      server.failNextHealth(healthFailure("initial owner failed"));
      server.onHealth = ignored -> {
        healthEntered.countDown();
        try {
          if (!releaseHealth.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("test did not release initial health wait");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      };

      var initial = tasks.submit(() -> {
        manager.switchToOnlineMode(TransitionReason.AUTO_START);
        return null;
      });
      assertTrue(healthEntered.await(5, TimeUnit.SECONDS));
      var vdu = tasks.submit(() -> {
        manager.switchToOnlineMode(TransitionReason.VDU_ENTER);
        return null;
      });
      assertThrows(TimeoutException.class, () -> vdu.get(100, TimeUnit.MILLISECONDS),
          "VDU enter must queue behind the current physical owner");

      releaseHealth.countDown();
      assertInstanceOf(ModeTransitionException.class,
          assertThrows(ExecutionException.class, () -> initial.get(5, TimeUnit.SECONDS)).getCause());
      var refused = assertInstanceOf(ModeTransitionException.class,
          assertThrows(ExecutionException.class, () -> vdu.get(5, TimeUnit.SECONDS)).getCause());
      assertEquals(ModeTransitionException.Reason.ONLINE_START_FAILED, refused.reason());
      assertEquals(1, server.starts.size(), "queued VDU must not launch desired configuration");
    } finally {
      releaseHealth.countDown();
    }
  }

  private static EngineComponentSnapshot.Component component(
      ComponentState state, String applied, String desired, int attempts) {
    var spec = new ComponentSpec("generative", false, Set.of(),
        ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofSeconds(180), 2);
    return new EngineComponentSnapshot.Component(
        spec, state, state == ComponentState.READY ? null : "inference.crashed",
        Instant.EPOCH, 1L, applied, desired, null, attempts, "test");
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
            "justsearch.data.dir", directory.resolve("data").toString(),
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

  private static HttpServer blockingTokenServer(
      CountDownLatch requestEntered, CountDownLatch releaseRequest) throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/tokenize", exchange -> {
      requestEntered.countDown();
      try {
        if (!releaseRequest.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("test did not release token request");
        }
        byte[] response = "{\"tokens\":[1]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    return server;
  }

  private static GenerativeRequestGate.Lease requestGateLease(
      InferenceLifecycleManager manager) throws Exception {
    var field = InferenceLifecycleManager.class.getDeclaredField("requestGate");
    field.setAccessible(true);
    return ((GenerativeRequestGate) field.get(manager)).acquire();
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
    private final AtomicReference<LlamaServerOps.StartRequest> retained = new AtomicReference<>();
    private final List<LlamaServerOps.StartRequest> starts = new ArrayList<>();
    private final ArrayDeque<ModeTransitionException> healthFailures = new ArrayDeque<>();
    private final AtomicReference<RuntimeException> stopFailure = new AtomicReference<>();
    private final AtomicBoolean wrongNextHash = new AtomicBoolean();
    private final AtomicBoolean candidateAlive = new AtomicBoolean(true);
    private final MockedConstruction<LlamaServerOps> construction;
    private Consumer<LlamaServerOps.StartRequest> onStart = request -> {};
    private Consumer<LlamaServerOps.StartResult> onHealth = result -> {};
    private Runnable onStop = () -> {};
    private final CountDownLatch recoveryReserved = new CountDownLatch(1);
    private PropsObserver propsObserver;
    private Consumer<BooleanSupplier> failure;

    @SuppressWarnings("unchecked") // Capture the typed manager callback at the constructor seam.
    private FakeServer() {
      construction =
          mockConstruction(
              LlamaServerOps.class,
              (server, context) -> {
                propsObserver = (PropsObserver) context.arguments().get(5);
                failure = (Consumer<BooleanSupplier>) context.arguments().get(6);
                when(server.startLlamaServer(any()))
                    .thenAnswer(
                        invocation -> {
                          LlamaServerOps.StartRequest request = invocation.getArgument(0);
                          retained.set(request);
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
                          onStop.run();
                          return null;
                        })
                    .when(server)
                    .stopLlamaServer();
                when(server.activeStartResult())
                    .thenAnswer(invocation -> Optional.ofNullable(active.get()));
                when(server.recoveryStartRequest())
                    .thenAnswer(invocation -> {
                      var owner = active.get();
                      if (owner != null) {
                        if (owner.disposition() == LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL) {
                          return Optional.empty();
                        }
                        return Optional.of(new LlamaServerOps.StartRequest(
                            owner.context(), owner.adoptionPolicy()));
                      }
                      return Optional.ofNullable(retained.get());
                    });
                when(server.reserveRecoveryStart(any()))
                    .thenAnswer(invocation -> {
                      LlamaServerOps.StartRequest request = invocation.getArgument(0);
                      var owner = active.get();
                      boolean reserved;
                      if (owner != null) {
                        if (owner.disposition() == LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL
                            || owner.context() != request.context()
                            || owner.adoptionPolicy() != request.adoptionPolicy()) return false;
                        retained.set(request);
                        reserved = true;
                      } else {
                        reserved = retained.get() == request;
                      }
                      if (reserved) recoveryReserved.countDown();
                      return reserved;
                    });
                when(server.ownsRecoveryAttempt(any()))
                    .thenAnswer(invocation -> {
                      LlamaServerOps.StartRequest request = invocation.getArgument(0);
                      var owner = active.get();
                      return owner != null
                          ? owner.disposition() != LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL
                              && owner.context() == request.context()
                              && owner.adoptionPolicy() == request.adoptionPolicy()
                          : retained.get() == request;
                    });
                doAnswer(invocation -> {
                  LlamaServerOps.StartRequest request = invocation.getArgument(0);
                  LlamaServerOps.StartResult result = invocation.getArgument(1);
                  if (active.get() != result || retained.get() != request) {
                    throw new IllegalStateException("owner changed");
                  }
                  retained.set(null);
                  return null;
                }).when(server).acceptVerifiedStart(any(), any());
                doAnswer(invocation -> {
                  retained.set(invocation.getArgument(0));
                  return null;
                }).when(server).retainAttemptedStartRequest(any());
                when(server.activeManagedCandidateAlive(any()))
                    .thenAnswer(invocation -> candidateAlive.get()
                        && active.get() == invocation.getArgument(0));
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
                doAnswer(
                        invocation -> {
                          LlamaServerOps.StartResult result = invocation.getArgument(0);
                          onHealth.accept(result);
                          ModeTransitionException failure = healthFailures.pollFirst();
                          if (failure != null) throw failure;
                          return null;
                        })
                    .when(server)
                    .waitForServerHealthOnce(any());
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

  private static final class TestRecoveryRequest implements ComponentRecoveryAction.Request {
    private final EngineComponentSnapshot.Component expected;
    private final AtomicReference<EngineComponentSnapshot.Component> current;
    private volatile EngineComponentSnapshot.Component admitted;
    private volatile boolean cancelled;
    private final CountDownLatch begun = new CountDownLatch(1);

    private TestRecoveryRequest(EngineComponentSnapshot.Component expected) {
      this.expected = expected;
      this.current = new AtomicReference<>(expected);
    }

    @Override public EngineComponentSnapshot.Component expected() { return expected; }
    @Override public EngineComponentSnapshot.Component current() { return current.get(); }
    @Override public Optional<EngineComponentSnapshot.Component> admitted() {
      return Optional.ofNullable(admitted);
    }
    @Override public boolean begin() {
      var starting = copy(current.get(), ComponentState.STARTING, "inference.starting",
          current.get().recoveryAttempts() + 1, "admitted");
      if (!current.compareAndSet(expected, starting)) return false;
      admitted = starting;
      begun.countDown();
      return true;
    }
    @Override public Optional<EngineComponentSnapshot.Component> complete(
        EngineComponentSnapshot.Component expectedCurrent, ComponentState state,
        String reasonCode, String evidence) {
      var terminal = copy(expectedCurrent, state, reasonCode,
          expectedCurrent.recoveryAttempts(), evidence);
      return current.compareAndSet(expectedCurrent, terminal)
          ? Optional.of(terminal) : Optional.empty();
    }
    @Override public boolean cancelled() { return cancelled; }

    private void cancel() { cancelled = true; }

    private boolean awaitBegun(long timeout, TimeUnit unit) throws InterruptedException {
      return begun.await(timeout, unit);
    }

    private void publishTimeout() {
      var before = current.get();
      current.set(copy(before, ComponentState.FAILED, "component.start_timeout",
          before.recoveryAttempts(), "deadline observed"));
    }

    private static EngineComponentSnapshot.Component copy(
        EngineComponentSnapshot.Component source, ComponentState state, String reason,
        int attempts, String evidence) {
      return new EngineComponentSnapshot.Component(
          source.spec(), state, reason, Instant.now(), System.nanoTime(),
          source.appliedVersion(), source.desiredVersion(), source.lastCompose(), attempts, evidence);
    }
  }
}

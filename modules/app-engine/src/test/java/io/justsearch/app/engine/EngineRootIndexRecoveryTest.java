/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.HeldInferenceCompositionFixture;
import io.justsearch.indexerworker.server.InferenceSurface;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EngineRootIndexRecoveryTest {
  @Test
  void encoderRecoveryRetainsTheExistingIndexUntilItsOwnerReturns() throws Exception {
    var root = root();
    var closer = new AtomicReference<Thread>();
    var closeCompleted = new CountDownLatch(1);
    var closeCompletedInsideRecovery = new AtomicBoolean();
    var closeFailure = new AtomicReference<Throwable>();
    try (var _ = root.processResources(); root) {
      var server = mock(KnowledgeServer.class);
      var request = mock(ComponentRecoveryAction.Request.class);
      set(root, "server", server);
      when(server.awaitClosed(anyLong())).thenReturn(true);
      when(server.recoverEncoders(request)).thenAnswer(ignored -> {
        var closeAttempted = new CountDownLatch(1);
        closer.set(Thread.ofVirtual().start(() -> {
          closeAttempted.countDown();
          try {
            root.closeForRecovery();
          } catch (Throwable failure) {
            closeFailure.set(failure);
          } finally {
            closeCompleted.countDown();
          }
        }));
        assertTrue(closeAttempted.await(5, TimeUnit.SECONDS));
        closeCompletedInsideRecovery.set(closeCompleted.await(100, TimeUnit.MILLISECONDS));
        return ComponentRecoveryAction.Result.REFUSED;
      });
      assertEquals(ComponentRecoveryAction.Result.REFUSED, root.recoverEncoders(request));
      assertFalse(closeCompletedInsideRecovery.get(),
          "Root close must wait for the exact encoder recovery owner");
      assertTrue(closeCompleted.await(5, TimeUnit.SECONDS));
      assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
      verify(server).recoverEncoders(request);
    } finally {
      if (closer.get() != null) closer.get().join(5_000);
    }
  }

  @Test
  void timeoutAloneDoesNotPreventSameAttemptLateReady() throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = (mayOpen) -> {
        harness.handle.transition(ComponentState.FAILED, "component.start_timeout", "late");
        set(root, "server", harness.replacement);
        return true;
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody());

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertEquals(ComponentState.READY, result.observation().state());
      assertEquals(1, result.observation().recoveryAttempts());
      verify(harness.replacement).acceptIndexRecovery();
      verify(harness.replacement).awaitIndexRecoveryQueryWitness(anyLong());
      verify(harness.replacement, never()).awaitIndexRecoveryModels(anyLong());
    }
  }

  @Test
  void desiredConfigurationChangeAfterAdmissionSupersedesHandover() throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = (mayOpen) -> {
        harness.handle.setDesiredVersion("desired-b");
        set(root, "server", harness.replacement);
        return true;
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody());

      assertEquals(ComponentRecoveryAction.Result.SUPERSEDED, result);
      assertEquals("desired-b", harness.handle.snapshot().desiredVersion());
      verify(harness.replacement).closeForRecovery();
      verify(harness.replacement, never()).acceptIndexRecovery();
    }
  }

  @Test
  void configurationChangeAfterPhysicalValidationLosesTerminalCas() throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = (mayOpen) -> {
        set(root, "server", harness.replacement);
        return true;
      };
      when(harness.replacement.finalizeIndexRecovery(harness.context)).thenAnswer(ignored -> {
        harness.handle.setDesiredVersion("desired-after-validation");
        return Optional.of(harness.context);
      });

      var result = root.recoverIndex(harness.request, harness.recoveryBody());

      assertEquals(ComponentRecoveryAction.Result.SUPERSEDED, result);
      assertEquals("desired-after-validation", harness.handle.snapshot().desiredVersion());
      verify(harness.replacement).closeForRecovery();
      verify(harness.replacement, never()).acceptIndexRecovery();
    }
  }

  @Test
  void prepublicationCallbackGatesReadyAndServingUntilStructuralBindingCompletes()
      throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = mayOpen -> {
        set(root, "server", harness.replacement);
        return true;
      };
      var callbackEntered = new CountDownLatch(1);
      var releaseCallback = new CountDownLatch(1);
      var bindingComplete = new AtomicBoolean();
      var readyObserverSawBinding = new AtomicBoolean();
      var result = new AtomicReference<ComponentRecoveryAction.Result>();
      var failure = new AtomicReference<Throwable>();
      try (var _ = root.components().subscribe(snapshot -> {
        boolean ready = snapshot.components().stream().anyMatch(component ->
            component.spec().name().equals("index")
                && component.state() == ComponentState.READY
                && component.recoveryAttempts() == 1);
        if (ready) readyObserverSawBinding.set(bindingComplete.get());
      })) {
        Thread recovery = Thread.ofVirtual().start(() -> {
          try {
            result.set(root.recoverIndex(harness.request, harness.recoveryBody(() -> {
              callbackEntered.countDown();
              try {
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
              bindingComplete.set(true);
            })));
          } catch (Throwable thrown) {
            failure.set(thrown);
          }
        });
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
        assertEquals(ComponentState.STARTING, harness.handle.snapshot().state());
        verify(harness.replacement, never()).armIndexRecoveryServing(
            org.mockito.ArgumentMatchers.any());
        releaseCallback.countDown();
        recovery.join(5_000);
        assertFalse(recovery.isAlive());
      } finally {
        releaseCallback.countDown();
      }

      assertNull(failure.get(), String.valueOf(failure.get()));
      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.get().outcome());
      assertTrue(readyObserverSawBinding.get(),
          "synchronous READY observers must see completed structural binding");
    }
  }

  @Test
  void prepublicationCallbackFailurePublishesFailedAndRetainsTheGatedReplacement()
      throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = mayOpen -> {
        set(root, "server", harness.replacement);
        return true;
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody(() -> {
        throw new IllegalStateException("structural binding failed");
      }));

      assertEquals(ComponentRecoveryAction.Outcome.FAILED, result.outcome());
      assertEquals(ComponentState.FAILED, harness.handle.snapshot().state());
      assertTrue(harness.handle.snapshot().evidence().contains("structural binding failed"));
      assertSame(harness.replacement, server(root));
      verify(harness.replacement, never()).armIndexRecoveryServing(
          org.mockito.ArgumentMatchers.any());
      verify(harness.replacement, never()).acceptIndexRecovery();
      verify(harness.replacement, never()).closeForRecovery();
    }
  }

  @Test
  void lineageChangeDuringPrepublicationCallbackPreventsReadyAndRetiresReplacement()
      throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = mayOpen -> {
        set(root, "server", harness.replacement);
        return true;
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody(
          () -> harness.handle.setDesiredVersion("desired-after-binding")));

      assertEquals(ComponentRecoveryAction.Result.SUPERSEDED, result);
      assertEquals("desired-after-binding", harness.handle.snapshot().desiredVersion());
      verify(harness.replacement, never()).armIndexRecoveryServing(
          org.mockito.ArgumentMatchers.any());
      verify(harness.replacement).closeForRecovery();
      verify(harness.replacement, never()).acceptIndexRecovery();
    }
  }

  @Test
  void cancellationDuringPrepublicationCallbackPreventsReadyAndRetiresReplacement()
      throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = mayOpen -> {
        set(root, "server", harness.replacement);
        return true;
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody(
          () -> harness.cancelled.set(true)));

      assertEquals(ComponentRecoveryAction.Result.SUPERSEDED, result);
      verify(harness.replacement, never()).armIndexRecoveryServing(
          org.mockito.ArgumentMatchers.any());
      verify(harness.replacement).closeForRecovery();
      verify(harness.replacement, never()).acceptIndexRecovery();
    }
  }

  @Test
  void lostTerminalCasRetiresCompletedPhysicalReplacement(@TempDir Path tempDir)
      throws Exception {
    ResolvedConfig snapshot = disabledModelConfig(tempDir);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    var root = root();
    var replacement = new AtomicReference<KnowledgeServer>();
    try (var _ = root.processResources(); root) {
      var incumbent = physicalServer(root, snapshot);
      incumbent.start();
      assertTrue(incumbent.awaitIndexRecoveryModels(10_000));
      set(root, "server", incumbent);
      var index = root.indexComponent();
      index.transition(ComponentState.FAILED, "worker.lost", "physical owner failed");
      var request = new TestRequest(index, new AtomicBoolean(),
          () -> index.setDesiredVersion("superseding-configuration"));

      var result = root.recoverIndex(request, new KnowledgeServerBootstrap.RecoveryBody() {
        @Override
        public boolean run(java.util.function.BooleanSupplier mayOpen) throws Exception {
          incumbent.closeForRecovery();
          if (!mayOpen.getAsBoolean()) return false;
          var context = (KnowledgeServer.IndexStartContext) field(
              root, "retainedIndexStartContext");
          var started = physicalServer(root, snapshot);
          started.bindIndexStartContext(context);
          started.start();
          assertTrue(started.awaitIndexRecoveryModels(10_000));
          replacement.set(started);
          set(root, "server", started);
          return true;
        }

        @Override
        public io.justsearch.app.api.lifecycle.LifecycleReasonCode fatalReasonCode() {
          return null;
        }

        @Override public String fatalDetail() { return null; }
      });

      assertEquals(ComponentRecoveryAction.Result.SUPERSEDED, result);
      var retired = replacement.get();
      assertNotNull(retired);
      assertTrue(retired.awaitClosed(1_000));
      assertNull(field(root, "server"));
      assertNull(field(retired, "indexRecoveryServingAdmission"));
      assertThrows(IllegalStateException.class, retired::captureServingView,
          "a superseding registry row cannot inherit the replacement's physical view");
    } finally {
      restoreGlobal(previous);
    }
  }

  @Test
  void cancellationAfterClosePreventsReplacementOpen() throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = (mayOpen) -> {
        set(root, "server", null);
        harness.cancelled.set(true);
        assertFalse(mayOpen.getAsBoolean());
        return false;
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody());

      assertEquals(ComponentRecoveryAction.Result.SUPERSEDED, result);
      assertNull(server(root));
    }
  }

  @Test
  void failedQueryWitnessResolutionCompletesAdmittedAttemptFailed() throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = (mayOpen) -> {
        set(root, "server", harness.replacement);
        return true;
      };
      when(harness.replacement.finalizeIndexRecovery(harness.context))
          .thenThrow(new KnowledgeServer.IndexRecoveryWitnessException("resolver failed"));

      var result = root.recoverIndex(harness.request, harness.recoveryBody());

      assertEquals(ComponentRecoveryAction.Outcome.FAILED, result.outcome());
      assertEquals(ComponentState.FAILED, harness.handle.snapshot().state());
      assertEquals(1, harness.handle.snapshot().recoveryAttempts());
    }
  }

  @Test
  void wrappedPhysicalFailureRetainsConcreteRootCauseInTerminalEvidence() throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      harness.body = ignored -> {
        throw new IOException("Failed to start KnowledgeServer",
            new IOException("index root lock is owned by another process"));
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody());

      assertEquals(ComponentRecoveryAction.Outcome.FAILED, result.outcome());
      assertTrue(result.observation().evidence().contains("index root lock"));
      assertTrue(result.observation().evidence().contains("Failed to start KnowledgeServer"));
    }
  }

  @Test
  void failedInitialStartInstallsPhysicalAppliedVersionWithoutLosingDesiredIntent()
      throws Exception {
    var root = root();
    try (var _ = root.processResources(); root) {
      var handle = root.indexComponent();
      handle.setDesiredVersion("desired-b");
      handle.transition(ComponentState.FAILED,
          io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
          "initial start failed");
      var request = new TestRequest(handle, new AtomicBoolean());
      ResolvedConfig configuration = ResolvedConfig.builder().contributeEnvRegistry().build();
      QueryRoleSelection disabled = new QueryRoleSelection(
          QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
      var observation = new InferenceSurface.ComponentObservation(Optional.of("query-a"),
          Set.of(), Set.of(), Optional.of(disabled));
      var retained = new KnowledgeServer.IndexStartContext(configuration, null, null, null, null,
          disabled, observation, null, false);
      var admitted = retained.forRecovery(null);
      var actual = new KnowledgeServer.IndexStartContext(configuration, null, null, null, null,
          disabled, observation, "physical-a", true);
      var replacement = mock(KnowledgeServer.class);
      set(root, "retainedIndexStartContext", retained);
      when(replacement.awaitIndexRecoveryQueryWitness(anyLong())).thenReturn(true);
      when(replacement.finalizeIndexRecovery(admitted)).thenReturn(Optional.of(actual));
      doNothing().when(replacement).acceptIndexRecovery();
      when(replacement.awaitClosed(anyLong())).thenReturn(true);

      var result = root.recoverIndex(request, new KnowledgeServerBootstrap.RecoveryBody() {
        @Override public boolean run(java.util.function.BooleanSupplier mayOpen) throws Exception {
          set(root, "server", replacement);
          return mayOpen.getAsBoolean();
        }
        @Override public io.justsearch.app.api.lifecycle.LifecycleReasonCode fatalReasonCode() {
          return null;
        }
        @Override public String fatalDetail() { return null; }
      });

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertEquals("physical-a", result.observation().appliedVersion());
      assertEquals("desired-b", result.observation().desiredVersion());
    }
  }

  @Test
  void actualRootSerializesConcurrentCloseDuringBootstrapRecoveryBody() throws Exception {
    var root = root();
    Thread concurrentClose = null;
    var closeAttempted = new CountDownLatch(1);
    var closeCompleted = new CountDownLatch(1);
    var closeCompletedInsideBody = new AtomicBoolean();
    var closeFailure = new AtomicReference<Throwable>();
    try (var _ = root.processResources(); root) {
      var harness = new Harness(root);
      AtomicReference<Thread> closer = new AtomicReference<>();
      harness.body = (mayOpen) -> {
        Thread thread = Thread.ofVirtual().start(() -> {
          closeAttempted.countDown();
          try {
            root.closeForRecovery();
          } catch (Throwable failure) {
            closeFailure.set(failure);
          } finally {
            closeCompleted.countDown();
          }
        });
        closer.set(thread);
        assertTrue(closeAttempted.await(5, TimeUnit.SECONDS));
        closeCompletedInsideBody.set(closeCompleted.await(100, TimeUnit.MILLISECONDS));
        set(root, "server", harness.replacement);
        return true;
      };

      var result = root.recoverIndex(harness.request, harness.recoveryBody());
      concurrentClose = closer.get();
      concurrentClose.join(5_000);

      assertFalse(closeCompletedInsideBody.get(),
          "concurrent close must wait for the actual Root recovery owner");
      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertTrue(closeCompleted.await(0, TimeUnit.MILLISECONDS));
      assertFalse(concurrentClose.isAlive());
      assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
    } finally {
      if (concurrentClose != null) concurrentClose.join(5_000);
    }
  }

  @Test
  void readyPublicationUsesQueryWitnessWhileNativeCompositionRemainsFenced(
      @TempDir Path tempDir) throws Exception {
    ResolvedConfig snapshot = disabledModelConfig(tempDir);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    var root = root();
    var replacement = new AtomicReference<HeldInferenceCompositionFixture>();
    try (var _ = root.processResources(); root) {
      var incumbent = physicalServer(root, snapshot);
      incumbent.start();
      assertTrue(incumbent.awaitIndexRecoveryModels(10_000));
      set(root, "server", incumbent);
      var index = root.indexComponent();
      index.transition(ComponentState.FAILED, "worker.lost", "physical owner failed");
      var request = new TestRequest(index, new AtomicBoolean());

      try {
        var result = root.recoverIndex(request, new KnowledgeServerBootstrap.RecoveryBody() {
          @Override
          public boolean run(java.util.function.BooleanSupplier mayOpen) throws Exception {
            incumbent.closeForRecovery();
            if (!mayOpen.getAsBoolean()) return false;
            var context = (KnowledgeServer.IndexStartContext) field(
                root, "retainedIndexStartContext");
            var fixture = new HeldInferenceCompositionFixture(
                root.executors(), WorkerConfig.load(snapshot), root.indexComponent(),
                root.encoderComponent(), snapshot);
            var started = fixture.server();
            started.bindIndexStartContext(context);
            started.start();
            replacement.set(fixture);
            set(root, "server", started);
            return true;
          }

          @Override
          public io.justsearch.app.api.lifecycle.LifecycleReasonCode fatalReasonCode() {
            return null;
          }

          @Override
          public String fatalDetail() {
            return null;
          }
        });

        HeldInferenceCompositionFixture fixture = replacement.get();
        assertNotNull(fixture);
        KnowledgeServer started = fixture.server();
        assertNotNull(started);
        assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
        assertEquals(ComponentState.READY, result.observation().state());
        assertTrue(fixture.witnessPublished());
        assertFalse(fixture.deferredComposition().isDone(),
            "index READY must not inherit the native assembly deadline");
        assertEquals(ComponentState.STARTING, root.encoderComponent().snapshot().state());
        assertNotNull(field(started, "recoveryStartContext"),
            "accepted physical owner stays fenced while native assembly is incomplete");
        assertNotNull(field(started, "indexRecoveryServingAdmission"),
            "the exact READY owner needs a transient read-admission witness");
        try (var serving = started.captureServingView()) {
          assertNotNull(serving,
              "the exact READY owner must serve while optional native assembly remains pending");
        }
        QueryRoleSelection prior = new QueryRoleSelection(
            QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
        assertThrows(IllegalStateException.class, () -> started.prepareQueryRoleSettings(
            new UiSettings(), snapshot, Set.of(), prior));
        assertFalse(started.beginUnrecordedBuildingLiveAsync("generation-b", () -> {})
            .get(5, TimeUnit.SECONDS));

        fixture.releaseComposition();
        assertTrue(started.awaitIndexRecoveryModels(10_000));
        fixture.deferredComposition().join();
        fixture.awaitCompositionCallbacks();
        assertEquals(ComponentState.ABSENT, root.encoderComponent().snapshot().state());
        assertNull(field(started, "recoveryStartContext"));
        assertNull(field(started, "indexRecoveryServingAdmission"));
        try (var serving = started.captureServingView()) {
          assertNotNull(serving);
        }
      } finally {
        HeldInferenceCompositionFixture fixture = replacement.get();
        if (fixture != null) fixture.releaseComposition();
      }
    } finally {
      restoreGlobal(previous);
    }
  }

  @Test
  void bootstrapRecoveryReadyListenerCanServeWhileNativeMutationFenceRemains(
      @TempDir Path tempDir) throws Exception {
    ResolvedConfig snapshot = disabledModelConfig(tempDir);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    var starts = new AtomicInteger();
    var held = new AtomicReference<HeldInferenceCompositionFixture>();
    EngineRoot.ServerFactory factory = new EngineRoot.ServerFactory() {
      @Override
      public KnowledgeServer create(io.justsearch.core.scheduling.GpuSchedulingGauge gauge,
          io.justsearch.core.execution.EngineExecutorRegistry executors,
          RecordedIngestionLifecycle ingestion, ComponentHandle index,
          ComponentHandle encoders) {
        return create(gauge, executors, ingestion, index, encoders, snapshot);
      }

      @Override
      public ResolvedConfig captureConfiguration() {
        return snapshot;
      }

      @Override
      public KnowledgeServer create(io.justsearch.core.scheduling.GpuSchedulingGauge gauge,
          io.justsearch.core.execution.EngineExecutorRegistry executors,
          RecordedIngestionLifecycle ingestion, ComponentHandle index,
          ComponentHandle encoders, ResolvedConfig exact) {
        if (starts.incrementAndGet() == 1) {
          var worker = WorkerConfig.load(exact);
          return new KnowledgeServer(executors, worker,
              new InProcessWorkerSignalBus(gauge, worker.dataDir().resolve("runtime")),
              ManagedChildRegistry.noop(), ingestion, index, encoders, exact);
        }
        var fixture = new HeldInferenceCompositionFixture(
            executors, WorkerConfig.load(exact), ingestion, index, encoders, exact);
        held.set(fixture);
        return fixture.server();
      }
    };
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        factory, 1_000, 100, ignored -> {}, () -> {}, OperationAuthority.inMemory());
    var config = new KnowledgeServerConfig(false, snapshot.paths().dataDir(), tempDir, tempDir,
        1_000L, 1_000L, 1, 2_000L, 1_000L, 300_000L, 100, 2_000L, 0);
    var bootstrap = new KnowledgeServerBootstrap(root.executors(), config, null,
        root.components(), root.indexComponent(), root, false, root.publicationLock());
    var readyListenerCaptured = new AtomicBoolean();
    var readyListenerFailure = new AtomicReference<RuntimeException>();
    var readySubscription = root.components().subscribe(observation -> {
      boolean recoveredReady = observation.components().stream()
          .anyMatch(component -> component.spec().name().equals("index")
              && component.state() == ComponentState.READY
              && component.recoveryAttempts() == 1);
      if (!recoveredReady) return;
      try (var serving = root.captureServingView()) {
        readyListenerCaptured.set(serving != null);
      } catch (RuntimeException failure) {
        readyListenerFailure.set(failure);
      }
    });
    AutoCloseable releaseHeldComposition = () -> {
      HeldInferenceCompositionFixture fixture = held.get();
      if (fixture != null) fixture.releaseComposition();
    };
    try (var _ = root.processResources(); root; bootstrap; readySubscription;
        releaseHeldComposition) {
      bootstrap.startInitial(ignored -> {});
      var initial = server(root);
      assertTrue(initial.awaitIndexRecoveryModels(10_000));
      root.indexComponent().transition(ComponentState.FAILED, "worker.lost",
          "installed recovery fixture");

      var result = bootstrap.recoverIndex(
          new TestRequest(root.indexComponent(), new AtomicBoolean()));

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertTrue(readyListenerCaptured.get(),
          "the synchronous READY observer must capture the exact accepted physical view");
      assertNull(readyListenerFailure.get());
      var fixture = held.get();
      assertNotNull(fixture);
      assertFalse(fixture.deferredComposition().isDone());
      assertNotNull(field(fixture.server(), "recoveryStartContext"));
      assertNotNull(field(fixture.server(), "indexRecoveryServingAdmission"));
      try (var serving = fixture.server().captureServingView()) {
        assertNotNull(serving,
            "READY serving cannot inherit the optional native-composition deadline");
      }
      QueryRoleSelection retainedQuery = new QueryRoleSelection(
          QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
      assertThrows(IllegalStateException.class, () -> fixture.server().prepareQueryRoleSettings(
          new UiSettings(), snapshot, Set.of(), retainedQuery));
      assertFalse(fixture.server().beginUnrecordedBuildingLiveAsync(
          "generation-b", () -> {}).get(5, TimeUnit.SECONDS),
          "the retained context must still fence native and generation mutations");

      fixture.releaseComposition();
      assertTrue(fixture.server().awaitIndexRecoveryModels(10_000));
      fixture.deferredComposition().join();
      fixture.awaitCompositionCallbacks();
      assertNull(field(fixture.server(), "recoveryStartContext"));
      assertNull(field(fixture.server(), "indexRecoveryServingAdmission"));
      try (var serving = fixture.server().captureServingView()) {
        assertNotNull(serving);
      }
    } finally {
      restoreGlobal(previous);
    }
  }

  private static EngineRoot root() {
    return new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        (gauge, executors, ingestion, indexComponent, encoderComponent) -> {
          throw new AssertionError("test installs its physical owner directly");
        }, 1_000, 100, ignored -> {}, () -> {}, OperationAuthority.inMemory());
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static KnowledgeServer server(EngineRoot root) throws Exception {
    Field field = EngineRoot.class.getDeclaredField("server");
    field.setAccessible(true);
    return (KnowledgeServer) field.get(root);
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static KnowledgeServer physicalServer(EngineRoot root, ResolvedConfig snapshot) {
    return new KnowledgeServer(root.executors(), WorkerConfig.load(snapshot), null,
        ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), root.indexComponent(),
        root.encoderComponent(), snapshot);
  }

  private static void restoreGlobal(ConfigStore previous) throws ReflectiveOperationException {
    if (previous != null) {
      ConfigStore.setGlobal(previous);
      return;
    }
    var clear = ConfigStore.class.getDeclaredMethod("clearGlobal");
    clear.setAccessible(true);
    clear.invoke(null);
  }

  private static ResolvedConfig disabledModelConfig(Path tempDir) {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), tempDir.resolve("data").toString());
    values.put(EnvRegistry.INDEX_BASE_PATH.configKey(), tempDir.resolve("index").toString());
    values.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    values.put(EnvRegistry.RERANK_CHUNKS_ENABLED.configKey(), "false");
    values.put(EnvRegistry.SPARSE_MODEL.configKey(), "splade");
    for (var key : new EnvRegistry[] {
        EnvRegistry.AI_EMBED_ENABLED,
        EnvRegistry.SPLADE_ENABLED,
        EnvRegistry.NER_ENABLED,
        EnvRegistry.BGE_M3_ENABLED,
        EnvRegistry.RERANK_ENABLED,
        EnvRegistry.CITATION_SCORER_ENABLED
    }) {
      values.put(key.configKey(), "false");
    }
    var builder = ResolvedConfig.builder();
    values.forEach(builder::putDefault);
    return builder.build();
  }

  @FunctionalInterface
  private interface Body {
    boolean run(java.util.function.BooleanSupplier mayOpen) throws Exception;
  }

  private static final class Harness {
    private final ComponentHandle handle;
    private final KnowledgeServer incumbent = mock(KnowledgeServer.class);
    private final KnowledgeServer replacement = mock(KnowledgeServer.class);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final TestRequest request;
    private final KnowledgeServer.IndexStartContext context;
    private Body body;

    private Harness(EngineRoot root) throws Exception {
      handle = root.indexComponent();
      handle.setDesiredVersion("applied-a");
      handle.setAppliedVersion("applied-a");
      handle.transition(ComponentState.FAILED, "worker.lost", "failed owner");
      request = new TestRequest(handle, cancelled);
      ResolvedConfig configuration = ResolvedConfig.builder().contributeEnvRegistry().build();
      QueryRoleSelection disabled = new QueryRoleSelection(
          QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
      var observation = new InferenceSurface.ComponentObservation(Optional.of("query-a"),
          Set.of(), Set.of(), Optional.of(disabled));
      context = new KnowledgeServer.IndexStartContext(configuration, null, null, null, null,
          disabled, observation, "applied-a", true);
      when(incumbent.reserveIndexRecovery(request)).thenAnswer(ignored -> {
        if (!request.begin()) return Optional.empty();
        return Optional.of(context);
      });
      when(replacement.awaitIndexRecoveryQueryWitness(anyLong())).thenReturn(true);
      when(replacement.finalizeIndexRecovery(context)).thenReturn(Optional.of(context));
      doNothing().when(replacement).acceptIndexRecovery();
      when(incumbent.awaitClosed(anyLong())).thenReturn(true);
      when(replacement.awaitClosed(anyLong())).thenReturn(true);
      doNothing().when(incumbent).close();
      doNothing().when(replacement).close();
      set(root, "server", incumbent);
    }

    private KnowledgeServerBootstrap.RecoveryBody recoveryBody() {
      return recoveryBody(() -> {});
    }

    private KnowledgeServerBootstrap.RecoveryBody recoveryBody(Runnable preparePublication) {
      return new KnowledgeServerBootstrap.RecoveryBody() {
        @Override public boolean run(java.util.function.BooleanSupplier mayOpen) throws Exception {
          return body.run(mayOpen);
        }
        @Override public void preparePublication() { preparePublication.run(); }
        @Override public io.justsearch.app.api.lifecycle.LifecycleReasonCode fatalReasonCode() {
          return null;
        }
        @Override public String fatalDetail() { return null; }
      };
    }
  }

  private static final class TestRequest implements ComponentRecoveryAction.Request {
    private final ComponentHandle handle;
    private final AtomicBoolean cancelled;
    private final Runnable beforeComplete;
    private final EngineComponentSnapshot.Component expected;
    private final AtomicReference<EngineComponentSnapshot.Component> admitted =
        new AtomicReference<>();

    private TestRequest(ComponentHandle handle, AtomicBoolean cancelled) {
      this(handle, cancelled, () -> {});
    }

    private TestRequest(ComponentHandle handle, AtomicBoolean cancelled, Runnable beforeComplete) {
      this.handle = handle;
      this.cancelled = cancelled;
      this.beforeComplete = beforeComplete;
      expected = handle.snapshot();
    }

    @Override public EngineComponentSnapshot.Component expected() { return expected; }
    @Override public EngineComponentSnapshot.Component current() { return handle.snapshot(); }
    @Override public Optional<EngineComponentSnapshot.Component> admitted() {
      return Optional.ofNullable(admitted.get());
    }
    @Override public boolean begin() {
      var publication = handle.tryBeginRecovery(expected, "component.recovering", "test");
      publication.ifPresent(admitted::set);
      return publication.isPresent();
    }
    @Override public Optional<EngineComponentSnapshot.Component> complete(
        EngineComponentSnapshot.Component current, ComponentState state,
        String reasonCode, String evidence) {
      beforeComplete.run();
      return handle.tryTransitionIfUnchanged(current, state, reasonCode, evidence);
    }
    @Override public boolean cancelled() { return cancelled.get(); }
  }
}

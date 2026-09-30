/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgeServerQuerySettingsOwnerTest {
  @Test
  void queryOnlyPublicationRetainsIssuedAThenRetiresItWithoutReplacingIndexOwner(
      @TempDir Path dir) throws Exception {
    var desired = new ResolvedConfigBuilder()
        .putDefault("justsearch.data.dir", dir.toString()).build();
    var projection = EncoderConfigurationProjection.from(desired);
    var indexSurface = new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
        new InferenceSurface.ComponentObservation(Optional.of(projection.indexDigest()),
            Set.of(), Set.of()));
    var index = new EncoderSet(indexSurface, new EncoderSet.ModelIdentity(
        IndexFingerprint.ModelFingerprint.present("test"),
        IndexFingerprint.ModelFingerprint.present("test"),
        IndexFingerprint.ModelFingerprint.present("test"), false, 768));
    var querySurface = new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
        new InferenceSurface.ComponentObservation(Optional.of(projection.queryDigest()),
            Set.of(), Set.of()));
    var queryA = new QueryRoleSet(querySurface);
    var prior = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    var runtime = mock(RunningRuntime.class);
    var producer = mock(DefaultWorkerAppServices.class);
    var successor = mock(DefaultWorkerAppServices.class);
    when(successor.ingestService()).thenReturn(mock(WorkerIngestService.class));
    when(producer.prepareQueryServingSuccessor(any(), any(), any())).thenReturn(successor);
    var transfer = mock(DefaultWorkerAppServices.ProducerTransfer.class);
    when(producer.prepareProducerTransferTo(successor)).thenReturn(transfer);
    try (var executors = new TestEngineExecutors()) {
      var encoderComponent = mock(ComponentHandle.class);
      var spec = new ComponentSpec("encoders", false, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ZERO, 0);
      when(encoderComponent.snapshot()).thenReturn(new EngineComponentSnapshot.Component(
          spec, ComponentState.READY, null, Instant.now(), 0L, null, null, null, 0, null));
      var server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(dir), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
          RecordedIngestionLifecycle.denied(), null, encoderComponent);
      server.signalBus = mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class);
      server.infraCtx = new InfraContext(WorkerBootFixture.workerConfig(dir),
          mock(io.justsearch.indexerworker.queue.JobQueue.class), () -> runtime, () -> runtime,
          server.signalBus, null, null, dir, dir.resolve("active"), () -> null, 5_000L);
      var old = newServingView(producer, runtime, dir.resolve("active"));
      attach(old, "attachEncoderSet", EncoderSet.class, index);
      attach(old, "attachQueryRoleSet", QueryRoleSet.class, queryA);
      set(server, "servingView", old);
      server.appServices = producer;
      try (var issuedA = server.captureServingView()) {
        assertSame(producer, issuedA.services());
        var aborted = server.prepareQueryRoleSettings(new UiSettings(), desired, Set.of(), prior);
        aborted.abort();
        verify(successor).close();
        try (var stillA = server.captureServingView()) {
          assertSame(producer, stillA.services());
        }

        clearInvocations(successor);
        var prepared = server.prepareQueryRoleSettings(new UiSettings(), desired, Set.of(), prior);
        prepared.withOwnerLocks(() -> {
          prepared.validate();
          prepared.install();
        });
        prepared.notifyObservers();
        prepared.retire();
        verify(transfer).install();
        try (var nowB = server.captureServingView()) {
          assertSame(successor, nowB.services());
          assertSame(index, nowB.encoderSet());
        }
        verify(producer, never()).close();
        assertFalse(queryA.isClosed());
      }
      verify(producer).close();
      assertTrue(queryA.isClosed());
      assertFalse(index.isClosed());
      server.close();
    }
  }

  @Test
  void besideServesAWhileCandidateComposes(@TempDir Path dir) throws Exception {
    try (var f = new QueryFixture(dir, 2048L);
        var composition = f.composition()) {
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any())).thenAnswer(call -> {
            try (var view = f.server.captureServingView()) {
              assertSame(f.producer, view.services());
              assertSame(f.index, view.encoderSet());
            }
            assertFalse(f.queryA.isClosed());
            return f.surface;
          });
      var prepared = f.prepare();
      assertEquals(io.justsearch.core.component.ComposeEvidence.Mode.BESIDE,
          prepared.composition().mode());
      prepared.abort();
      f.assertA();
    }
  }

  @Test
  void inPlaceReleasesOwnCaptureAndKeepsIndexAndProducerDuringCompose(@TempDir Path dir)
      throws Exception {
    try (var f = new QueryFixture(dir, 512L);
        var composition = f.composition()) {
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any())).thenAnswer(call -> {
            try (var view = f.server.captureServingView()) {
              assertSame(f.degraded, view.services());
              assertSame(f.index, view.encoderSet());
              assertNull(querySet(view));
            }
            assertTrue(f.queryA.isClosed());
            assertFalse(f.index.isClosed());
            verify(f.producer, never()).prepareProducerTransferTo(any());
            verify(f.producer, never()).close();
            verify(f.component).prepareReplacement(argThat(row ->
                row.state() == ComponentState.RELOADING));
            verify(f.degraded).wireSearchReranker(null);
            return f.surface;
          });
      var prepared = assertTimeout(Duration.ofSeconds(3), () -> f.prepare());
      assertEquals(io.justsearch.core.component.ComposeEvidence.Mode.IN_PLACE,
          prepared.composition().mode());
      prepared.withOwnerLocks(() -> { prepared.validate(); prepared.install(); });
      prepared.notifyObservers();
      prepared.retire();
      verify(f.transfer).install();
      try (var view = f.server.captureServingView()) {
        assertSame(f.candidate, view.services());
        assertSame(f.index, view.encoderSet());
      }
      assertFalse(f.index.isClosed());
    }
  }

  @Test
  void inPlaceWaitsForIssuedARequestBeforeRetiringQuery(@TempDir Path dir) throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      var issued = f.server.captureServingView();
      var released = new java.util.concurrent.CountDownLatch(1);
      var holder = new Thread(() -> {
        try {
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
          while (System.nanoTime() < deadline) {
            try (var view = f.server.captureServingView()) {
              if (view.services() == f.degraded) {
                assertFalse(f.queryA.isClosed());
                issued.close();
                released.countDown();
                return;
              }
            }
            Thread.sleep(5);
          }
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      holder.start();
      var prepared = f.prepare();
      assertTrue(released.await(2, java.util.concurrent.TimeUnit.SECONDS));
      holder.join();
      assertTrue(f.queryA.isClosed());
      prepared.abort();
      f.assertRestored();
    }
  }

  @Test
  void inPlaceIgnoresOlderServiceCleanupAfterItsQueryCallsDrain(@TempDir Path dir)
      throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      var older = newServingView(mock(DefaultWorkerAppServices.class), mock(RunningRuntime.class),
          dir.resolve("older"));
      attach(older, "attachEncoderSet", EncoderSet.class, f.index);
      attach(older, "attachQueryRoleSet", QueryRoleSet.class, f.queryA);
      var cleanupCalls = new java.util.concurrent.atomic.AtomicInteger();
      set(older, "retiring", true);
      set(older, "retireCleanup", (Runnable) () -> {
        cleanupCalls.incrementAndGet();
        throw new IllegalStateException("unrelated service cleanup refused");
      });
      retiredViews(f.server).add(older);
      try {
        var prepared = f.prepare();
        assertEquals(0, cleanupCalls.get(),
            "query retirement must not run an older application's cleanup");
        assertTrue(retiredViews(f.server).contains(older),
            "the older service remains registered for its maintenance retry");
        prepared.abort();
        f.assertRestored();
      } finally {
        retiredViews(f.server).remove(older);
        releaseModelSets(older);
      }
    }
  }

  @Test
  void inPlaceOlderIssuedQueryTimeoutRestoresUntouchedA(@TempDir Path dir) throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      var older = newServingView(mock(DefaultWorkerAppServices.class), mock(RunningRuntime.class),
          dir.resolve("older-held"));
      attach(older, "attachEncoderSet", EncoderSet.class, f.index);
      attach(older, "attachQueryRoleSet", QueryRoleSet.class, f.queryA);
      set(older, "retiring", true);
      set(older, "holders", 1);
      retiredViews(f.server).add(older);
      try {
        assertTimeout(Duration.ofSeconds(7), () ->
            assertThrows(IllegalStateException.class, f::prepare));
        assertFalse(f.queryA.isClosed());
        f.assertA();
        assertTrue(retiredViews(f.server).contains(older),
            "timed-out older cleanup must remain eligible for maintenance");
      } finally {
        set(older, "holders", 0);
        retiredViews(f.server).remove(older);
        releaseModelSets(older);
      }
    }
  }

  @Test
  void retirementTimeoutRepublishesUntouchedA(@TempDir Path dir) throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition();
        var held = f.server.captureServingView()) {
      assertThrows(IllegalStateException.class, f::prepare);
      assertFalse(f.queryA.isClosed());
      f.assertA();
      composition.verify(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any()), never());
    }
  }

  @Test
  void composeRefusalRestoresExactAWithoutComposerRegistration(@TempDir Path dir) throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any())).thenThrow(new IllegalStateException("B rejected")).thenReturn(f.surface);
      var refused = assertThrows(IllegalStateException.class, f::prepare);
      assertEquals("B rejected", refused.getMessage());
      f.assertRestored();
      composition.verify(() -> InferenceCompositionRoot.composeQueryRoles(
          argThat(projection -> projection.config() == f.configuration), eq(f.selection), any(),
          any(), any()), times(2));
    }
  }

  @Test
  void everyLaterPrecommitRefusalRestoresA(@TempDir Path dir) throws Exception {
    for (String reason : List.of("later owner rejected", "validation rejected", "cancelled",
        "settings replacement failed")) {
      try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
        var prepared = f.prepare();
        prepared.abort(new IllegalStateException(reason));
        f.assertRestored();
        prepared.abort(); // Idempotent, no second A compose or producer transfer.
        composition.verify(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
            any(), any()), times(2));
      }
    }
  }

  @Test
  void failedRestorationRetainsBothReasonsForExistingRecovery(@TempDir Path dir) throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any())).thenThrow(new IllegalStateException("B rejected"))
          .thenThrow(new IllegalStateException("A rejected"));
      assertThrows(IllegalStateException.class, f::prepare);
      verify(f.component).transition(eq(ComponentState.UNAVAILABLE),
          eq("component.recovery_failed"), argThat(evidence ->
              evidence.contains("B rejected") && evidence.contains("A rejected")));
      var field = KnowledgeServer.class.getDeclaredField("encoderRecoveryReservation");
      field.setAccessible(true);
      assertNotNull(field.get(f.server));
      try (var lexical = f.server.captureServingView()) {
        assertSame(f.degraded, lexical.services());
        assertSame(f.index, lexical.encoderSet());
      }
      assertFalse(f.index.isClosed());
    }
  }

  @Test
  void queryRecoveryCasLossReleasesReservationAndPreservesNewerOwner(@TempDir Path dir)
      throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      var recoveredSessions = mock(io.justsearch.ort.SessionHandle.class);
      when(recoveredSessions.retirementStatus())
          .thenReturn(io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED);
      var recoveredSurface = new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.of(new io.justsearch.reranker.RerankerAssembly(recoveredSessions,
              new io.justsearch.reranker.RerankerShape(512, false),
              mock(io.justsearch.reranker.RerankerTokenizer.class))),
          Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(recoveredSessions),
          f.surface.componentObservation());
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any())).thenThrow(new IllegalStateException("B rejected"))
          .thenThrow(new IllegalStateException("A rejected"))
          .thenReturn(recoveredSurface);
      assertThrows(IllegalStateException.class, f::prepare);
      var before = f.component.snapshot();
      var failed = new EngineComponentSnapshot.Component(before.spec(), ComponentState.UNAVAILABLE,
          "component.recovery_failed", before.stateSince(), before.stateSinceMonotonicNanos(),
          before.appliedVersion(), before.desiredVersion(), before.lastCompose(), 1, "B and A rejected");
      var admitted = new EngineComponentSnapshot.Component(before.spec(), ComponentState.STARTING,
          "component.recovering", before.stateSince(), before.stateSinceMonotonicNanos(),
          before.appliedVersion(), before.desiredVersion(), before.lastCompose(), 2, "restoring A");
      var newer = new EngineComponentSnapshot.Component(before.spec(), ComponentState.READY,
          null, before.stateSince(), before.stateSinceMonotonicNanos(), "newer-applied",
          "newer-desired", before.lastCompose(), 2, "newer lifecycle owner");
      var current = new java.util.concurrent.atomic.AtomicReference<>(failed);
      var began = new java.util.concurrent.atomic.AtomicBoolean();
      when(f.component.snapshot()).thenAnswer(ignored -> current.get());
      var installed = new java.util.concurrent.atomic.AtomicReference<
          EngineComponentSnapshot.Component>();
      var batch = mock(io.justsearch.core.component.EngineComponentRegistry.PreparedBatch.class);
      when(f.component.prepareReplacement(any())).thenAnswer(call -> {
        installed.set(call.getArgument(0));
        return batch;
      });
      when(batch.snapshot()).thenAnswer(ignored ->
          new EngineComponentSnapshot(1, List.of(installed.get())));
      var request = new io.justsearch.core.component.ComponentRecoveryAction.Request() {
        @Override public EngineComponentSnapshot.Component expected() { return failed; }
        @Override public EngineComponentSnapshot.Component current() { return current.get(); }
        @Override public Optional<EngineComponentSnapshot.Component> admitted() {
          return began.get() ? Optional.of(admitted) : Optional.empty();
        }
        @Override public boolean begin() {
          if (!began.compareAndSet(false, true)) return false;
          current.set(admitted);
          return true;
        }
        @Override public Optional<EngineComponentSnapshot.Component> complete(
            EngineComponentSnapshot.Component expectedCurrent, ComponentState state,
            String reasonCode, String evidence) {
          assertEquals(installed.get(), expectedCurrent);
          assertEquals(ComponentState.READY, state);
          current.set(newer);
          return Optional.empty();
        }
        @Override public boolean cancelled() { return false; }
      };
      set(f.server, "running", true);
      try {
        assertEquals(io.justsearch.core.component.ComponentRecoveryAction.Outcome.SUPERSEDED,
            f.server.recoverEncoders(request).outcome());
        f.assertRestored();
        assertSame(newer, current.get());
        assertTrue(began.get());
        assertEquals(io.justsearch.core.component.ComponentRecoveryAction.Outcome.REFUSED,
            f.server.recoverEncoders(request).outcome(),
            "the one-shot admission cannot be redispatched after a newer owner wins");
        composition.verify(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
            any(), any()), times(3));
        Field retained = KnowledgeServer.class.getDeclaredField("encoderRecoveryReservation");
        retained.setAccessible(true);
        assertNull(retained.get(f.server));
        verify(f.producer, never()).pauseProducerForCutover(anyLong());
      } finally { set(f.server, "running", false); }
    }
  }

  @Test
  void failedCandidateCleanupIsRetainedByQueryRecovery(@TempDir Path dir) throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      var prepared = f.prepare();
      doThrow(new java.io.IOException("B service close refused")).when(f.candidate).close();
      var cause = new IllegalStateException("later owner rejected");
      assertDoesNotThrow(() -> prepared.abort(cause));
      assertEquals(1, cause.getSuppressed().length);
      verify(f.component).transition(eq(ComponentState.UNAVAILABLE),
          eq("component.recovery_failed"), argThat(evidence ->
              evidence.contains("later owner rejected") && evidence.contains("close failed")));
      Field retained = KnowledgeServer.class.getDeclaredField("encoderRecoveryReservation");
      retained.setAccessible(true);
      assertNotNull(retained.get(f.server));
      assertThrows(IllegalStateException.class, f::prepare);
      doNothing().when(f.candidate).close();
    }
  }

  @Test
  void retainedQueryRecoveryShutdownClosesDegradedAndRetriesCandidate(@TempDir Path dir)
      throws Exception {
    try (var f = new QueryFixture(dir, 512L); var composition = f.composition()) {
      var prepared = f.prepare();
      doThrow(new java.io.IOException("B service close refused")).doNothing()
          .when(f.candidate).close();
      assertDoesNotThrow(() -> prepared.abort(new IllegalStateException("later owner rejected")));

      f.server.close();

      verify(f.producer).close();
      verify(f.degraded).close();
      verify(f.candidate, times(2)).close();
      assertTrue(f.queryA.isClosed());
    }
  }

  @Test
  void unknownMemoryAndInsufficientReleaseRefuseBeforeRetirement(@TempDir Path dir)
      throws Exception {
    for (Long free : java.util.Arrays.asList(null, 1L)) {
      try (var f = new QueryFixture(dir, free); var composition = f.composition()) {
        composition.when(() -> InferenceCompositionRoot.sourceQueryReleasableBytes(any()))
            .thenReturn(256L);
        assertThrows(io.justsearch.app.api.settings.SettingsCommitOwner.Refused.class, f::prepare);
        f.assertA();
        assertFalse(f.queryA.isClosed());
        verify(f.producer, never()).prepareQueryServingSuccessor(any(), any(), any());
      }
    }
  }

  static final class QueryFixture implements AutoCloseable {
    final TestEngineExecutors executors = new TestEngineExecutors();
    final KnowledgeServer server;
    final io.justsearch.configuration.resolved.ResolvedConfig configuration;
    final EncoderSet index;
    final QueryRoleSet queryA;
    final InferenceSurface surface;
    final QueryRoleSelection selection;
    final DefaultWorkerAppServices producer = mock(DefaultWorkerAppServices.class);
    final DefaultWorkerAppServices degraded = mock(DefaultWorkerAppServices.class);
    final DefaultWorkerAppServices candidate = mock(DefaultWorkerAppServices.class);
    final DefaultWorkerAppServices restored = mock(DefaultWorkerAppServices.class);
    final DefaultWorkerAppServices.ProducerTransfer transfer =
        mock(DefaultWorkerAppServices.ProducerTransfer.class);
    final ComponentHandle component = mock(ComponentHandle.class);

    QueryFixture(Path dir, Long free) throws Exception { this(dir, free, false); }

    QueryFixture(Path dir, Long free, boolean cpuFallback) throws Exception {
      Path models = dir.resolve("source-reranker");
      java.nio.file.Files.createDirectories(models);
      Path model = models.resolve("model_fp16.onnx");
      Path tokenizer = models.resolve("tokenizer.json");
      java.nio.file.Files.writeString(model, "source-model", java.nio.charset.StandardCharsets.UTF_8);
      java.nio.file.Files.writeString(tokenizer, "source-tokenizer", java.nio.charset.StandardCharsets.UTF_8);
      selection = new QueryRoleSelection(QueryRoleSelection.Role.selected("model_fp16.onnx",
          witness(model), witness(tokenizer), io.justsearch.configuration.model.ModelPrecision.FP16,
          io.justsearch.configuration.model.ExecutionProvider.CUDA), QueryRoleSelection.Role.disabled());
      configuration = new ResolvedConfigBuilder().putDefault("justsearch.data.dir", dir.toString())
          .build();
      var projection = EncoderConfigurationProjection.from(configuration);
      var fallback = mock(io.justsearch.reranker.RerankerAssembly.class);
      var sessions = mock(io.justsearch.ort.SessionHandle.class);
      when(fallback.sessions()).thenReturn(sessions);
      when(sessions.retirementStatus()).thenReturn(io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED);
      surface = new InferenceSurface(Optional.empty(), Optional.empty(),
          cpuFallback ? Optional.of(fallback) : Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), null,
          cpuFallback ? List.of(sessions) : List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(projection.queryDigest()),
              Set.of(io.justsearch.ort.EncoderRole.RERANKER), Set.of(), Optional.of(selection)));
      queryA = new QueryRoleSet(surface);
      var generation = mock(GenerationModelSelection.class);
      var selected = EncoderConfigurationProjection.from(configuration, generation);
      index = new EncoderSet(new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(selected.indexDigest()),
              Set.of(), Set.of())), new EncoderSet.ModelIdentity(
              IndexFingerprint.ModelFingerprint.present("test"),
              IndexFingerprint.ModelFingerprint.present("test"),
              IndexFingerprint.ModelFingerprint.present("test"), false, 768));
      var spec = new ComponentSpec("encoders", false, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ZERO, 0);
      when(component.snapshot()).thenReturn(new EngineComponentSnapshot.Component(spec,
          ComponentState.READY, null, Instant.now(), 0L, projection.digest(), projection.digest(),
          null, 0, null));
      when(component.prepareReplacement(any())).thenReturn(
          mock(io.justsearch.core.component.EngineComponentRegistry.PreparedBatch.class));
      server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(dir), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(),
          null, component, configuration, () -> configuration,
          new java.util.concurrent.locks.ReentrantReadWriteLock(),
          () -> new io.justsearch.core.component.DeviceMemoryLine(4096L, free));
      server.signalBus = mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class);
      var runtime = mock(RunningRuntime.class);
      server.infraCtx = new InfraContext(WorkerBootFixture.workerConfig(dir),
          mock(io.justsearch.indexerworker.queue.JobQueue.class), () -> runtime, () -> runtime,
          server.signalBus, null, null, dir, dir.resolve("active"), () -> null, 5_000L);
      for (var fresh : List.of(degraded, candidate, restored)) {
        when(fresh.ingestService()).thenReturn(mock(WorkerIngestService.class));
        when(producer.prepareProducerTransferTo(fresh)).thenReturn(transfer);
      }
      when(producer.prepareQueryServingSuccessor(any(), any(), any()))
          .thenReturn(degraded, candidate, restored);
      var old = newServingView(producer, runtime, dir.resolve("active"));
      attach(old, "attachEncoderSet", EncoderSet.class, index);
      attach(old, "attachQueryRoleSet", QueryRoleSet.class, queryA);
      set(server, "servingView", old);
      set(server, "initialModelSelection", generation);
      set(server, "initialEncoderSet", index);
      set(server, "initialQueryRoleSet", queryA);
      set(server, "startupConfiguration", configuration);
      server.appServices = producer;
    }

    org.mockito.MockedStatic<InferenceCompositionRoot> composition() {
      var mocked = mockStatic(InferenceCompositionRoot.class);
      mocked.when(() -> InferenceCompositionRoot.estimateQueryFootprintBytes(any(), any(), any()))
          .thenReturn(1024L);
      mocked.when(() -> InferenceCompositionRoot.sourceQueryReleasableBytes(any())).thenReturn(1024L);
      mocked.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(), any()))
          .thenReturn(surface);
      return mocked;
    }

    KnowledgeServer.PreparedQueryRoleSettings prepare() {
      return server.prepareQueryRoleSettings(new UiSettings(), configuration, Set.of(), selection);
    }

    void assertA() {
      try (var view = server.captureServingView()) {
        assertSame(producer, view.services());
        assertSame(index, view.encoderSet());
      }
    }

    void assertRestored() {
      try (var view = server.captureServingView()) {
        // A restore needs a fresh immutable service; producer ownership transfers only now.
        assertTrue(view.services() == candidate || view.services() == restored);
        assertSame(index, view.encoderSet());
        assertEquals(selection, querySet(view).surfaceForOwner().componentObservation()
            .querySelection().orElseThrow());
      }
      assertTrue(queryA.isClosed());
      assertFalse(index.isClosed());
    }

    @Override public void close() throws Exception {
      server.close();
      executors.close();
    }
  }

  private static io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile witness(
      Path path) throws Exception {
    byte[] bytes = java.nio.file.Files.readAllBytes(path);
    return new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
        path.toAbsolutePath().normalize(), java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length);
  }

  private static QueryRoleSet querySet(KnowledgeServer.ServingLease lease) {
    try {
      Field captured = lease.getClass().getDeclaredField("captured");
      captured.setAccessible(true);
      Object view = captured.get(lease);
      Field roles = view.getClass().getDeclaredField("queryRoleSet");
      roles.setAccessible(true);
      return (QueryRoleSet) roles.get(view);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  private static Object newServingView(DefaultWorkerAppServices services,
      RunningRuntime runtime, Path path) throws Exception {
    Class<?> type = Class.forName(KnowledgeServer.class.getName() + "$ServingView");
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(services, runtime, runtime, path);
  }

  private static void attach(Object view, String name, Class<?> type, Object value)
      throws Exception {
    var method = view.getClass().getDeclaredMethod(name, type);
    method.setAccessible(true);
    method.invoke(view, value);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> retiredViews(KnowledgeServer server) throws Exception {
    Field field = KnowledgeServer.class.getDeclaredField("retiredServingViews");
    field.setAccessible(true);
    return (List<Object>) field.get(server);
  }

  private static void releaseModelSets(Object view) throws Exception {
    var method = view.getClass().getDeclaredMethod("releaseModelSets");
    method.setAccessible(true);
    method.invoke(view);
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }
}

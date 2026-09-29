/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.IndexRootLock;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

/** Real index-owner lifecycle proof for recovery and failed-start cleanup. */
@Timeout(180)
final class KnowledgeServerRecoveryCloseTest {

  @Test
  void capturedRecoveryHelperPartitionsIndexAWithCurrentQueryB(@TempDir Path tempDir)
      throws Exception {
    var base = new HashMap<String, String>();
    base.put(EnvRegistry.DATA_DIR.configKey(), tempDir.resolve("data").toString());
    base.put(EnvRegistry.INDEX_BASE_PATH.configKey(), tempDir.resolve("index").toString());
    base.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    base.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "false");
    base.put(EnvRegistry.SPLADE_ENABLED.configKey(), "false");
    base.put(EnvRegistry.NER_ENABLED.configKey(), "false");
    base.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "false");
    base.put(EnvRegistry.RERANK_ENABLED.configKey(), "false");
    base.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false");
    var configA = TestResolvedConfigHelper.fromEntries(base);
    var queryB = new HashMap<>(base);
    queryB.put(EnvRegistry.RERANK_MAX_SEQ_LEN.configKey(), "384");
    var configB = TestResolvedConfigHelper.fromEntries(queryB);
    var capturedA = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
    var capturedB = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
    InferenceCompositionRoot.compose(EncoderConfigurationProjection.from(configA),
        HardwareProfile.cpuOnly(), null, null, () -> false,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null,
        ignored -> {}, capturedA::set);
    InferenceCompositionRoot.compose(EncoderConfigurationProjection.from(configB),
        HardwareProfile.cpuOnly(), null, null, () -> false,
        io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP, null, null,
        ignored -> {}, capturedB::set);

    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors, WorkerConfig.load(configA), null);
      var absent = io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint
          .notConfigured();
      var identity = new EncoderSet.ModelIdentity(absent, absent, absent, false, 768);
      Method compose = KnowledgeServer.class.getDeclaredMethod("composeCapturedModels",
          IndexCompositionPlan.class, EncoderConfigurationProjection.class,
          InferenceSurface.ComponentObservation.class, EncoderSet.ModelIdentity.class);
      compose.setAccessible(true);
      Object models = compose.invoke(server, capturedA.get().indexPlan(),
          capturedB.get().queryProjection(), capturedB.get().queryObservation(), identity);
      Method ownerAccessor = models.getClass().getDeclaredMethod("owner");
      Method queryAccessor = models.getClass().getDeclaredMethod("queryOwner");
      Method close = models.getClass().getDeclaredMethod("close");
      ownerAccessor.setAccessible(true);
      queryAccessor.setAccessible(true);
      close.setAccessible(true);
      try {
        var owner = (EncoderSet) ownerAccessor.invoke(models);
        var queryOwner = (QueryRoleSet) queryAccessor.invoke(models);
        assertEquals(capturedA.get().indexPlan().projection().indexDigest(),
            owner.surfaceForOwner().componentObservation().configurationDigest().orElseThrow());
        assertEquals(capturedB.get().queryProjection().queryDigest(),
            queryOwner.surfaceForOwner().componentObservation().configurationDigest()
                .orElseThrow());
      } finally {
        close.invoke(models);
      }
    }
  }

  @Test
  void physicalEncoderRecoveryPublishesLexicalThenExactCapturedOwner(@TempDir Path tempDir)
      throws Exception {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), tempDir.resolve("data").toString());
    values.put(EnvRegistry.INDEX_BASE_PATH.configKey(), tempDir.resolve("index").toString());
    values.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    values.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "false");
    values.put(EnvRegistry.SPLADE_ENABLED.configKey(), "false");
    values.put(EnvRegistry.NER_ENABLED.configKey(), "true");
    values.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "false");
    values.put(EnvRegistry.RERANK_ENABLED.configKey(), "false");
    values.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false");
    var configuration = TestResolvedConfigHelper.fromEntries(values);
    var projection = EncoderConfigurationProjection.from(configuration);
    var querySelection = new io.justsearch.app.api.settings.QueryRoleSelection(
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
    var fullObservation = new InferenceSurface.ComponentObservation(
        Optional.of(projection.digest()), Set.of(io.justsearch.ort.EncoderRole.NER), Set.of(),
        Optional.of(querySelection));
    var sourceSurface = new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(),
        mock(io.justsearch.ort.PolicySnapshot.class), List.of(), fullObservation);
    var partition = sourceSurface.partitionQueryRoles(projection);
    var absent = io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint
        .notConfigured();
    var identity = new EncoderSet.ModelIdentity(absent, absent, absent, false, 768);
    var sourceOwner = new EncoderSet(partition.index(), identity);
    var sourceNer = sourceOwner.own(mock(NerService.class));
    sourceOwner.bindNer(sourceNer);
    sourceOwner.releaseModelReady();
    var sourceQuery = new QueryRoleSet(partition.query());
    var recoveredSurface = new InferenceSurface(Optional.empty(),
        Optional.of(mock(NerAssembly.class)), Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), mock(io.justsearch.ort.PolicySnapshot.class), List.of(), fullObservation);
    var newlyMissingObservation = new InferenceSurface.ComponentObservation(
        Optional.of(projection.digest()), Set.of(io.justsearch.ort.EncoderRole.NER),
        Set.of(io.justsearch.ort.EncoderRole.NER), Optional.of(querySelection));
    var newlyMissingSurface = new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        mock(io.justsearch.ort.PolicySnapshot.class), List.of(), newlyMissingObservation);
    var plan = new IndexCompositionPlan(projection, HardwareProfile.cpuOnly(),
        io.justsearch.ort.RuntimePolicy.defaults(),
        Map.of(io.justsearch.ort.EncoderRole.NER,
            new IndexCompositionPlan.RolePlan(true, null, null, null, List.of(), null)), false);

    Object sourceView = null;
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents();
        var composition = mockStatic(InferenceCompositionRoot.class);
        var indexProjection = mockStatic(IndexConfigurationProjection.class);
        MockedConstruction<NerService> constructedNer = mockConstruction(NerService.class)) {
      var encoder = components.handle("encoders");
      var producer = mock(DefaultWorkerAppServices.class);
      var lexical = mock(WorkerAppServices.class);
      var runtime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      when(producer.prepareTextOnlyEncoderRecoveryView(runtime)).thenReturn(lexical);
      when(producer.pauseProducerForCutover(org.mockito.ArgumentMatchers.anyLong()))
          .thenReturn(true);
      var compositionAttempts = new java.util.concurrent.atomic.AtomicInteger();
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      composition.when(() -> InferenceCompositionRoot.composeCaptured(any(), any(), any(),
          any(), any())).thenAnswer(invocation -> {
            int attempt = compositionAttempts.getAndIncrement();
            if (attempt == 0) cancelled.set(true);
            return attempt == 1 ? newlyMissingSurface : recoveredSurface;
          });
      indexProjection.when(() -> IndexConfigurationProjection.digest(any(), any(), any(), any(),
          any(), any(), org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn("index-a");
      var server = new KnowledgeServer(executors, WorkerConfig.load(configuration), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, encoder,
          configuration);
      setField(server, "running", true);
      setField(server, "searchLifecycle", runtime);
      setField(server, "ingestLifecycle", runtime);
      var identityStore = mock(
          io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore.class);
      when(identityStore.deletionGraceMs()).thenReturn(0L);
      setField(server, "documentIdentityStore", identityStore);
      server.appServices = producer;
      server.publishServingView(producer);
      Object view = field(server, "servingView");
      sourceView = view;
      Method attachEncoder = view.getClass().getDeclaredMethod("attachEncoderSet", EncoderSet.class);
      Method attachQuery = view.getClass().getDeclaredMethod("attachQueryRoleSet", QueryRoleSet.class);
      attachEncoder.setAccessible(true);
      attachQuery.setAccessible(true);
      attachEncoder.invoke(view, sourceOwner);
      attachQuery.invoke(view, sourceQuery);
      setField(server, "initialEncoderSet", sourceOwner);
      setField(server, "initialQueryRoleSet", sourceQuery);
      setField(server, "initialIndexCompositionPlan", plan);
      encoder.setDesiredVersion(projection.digest());
      encoder.setAppliedVersion(projection.digest());
      encoder.transition(ComponentState.FAILED, "encoder.failed", "test failure");
      doAnswer(invocation -> {
        encoder.transition(ComponentState.FAILED,
            io.justsearch.app.api.lifecycle.LifecycleReasonCode.COMPONENT_START_DEADLINE.code(),
            "deadline elapsed during exact composition");
        return null;
      }).when(producer).wireRecoveredEncoders(
          org.mockito.ArgumentMatchers.isNull(), any(), org.mockito.ArgumentMatchers.isNull(),
      org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), any());

      try (var heldNative = server.captureServingView()) {
        assertSame(sourceOwner, heldNative.encoderSet(),
            "held serving lease must retain the source native owner");
        var drainRefusal = server.recoverEncoders(new RecoveryRequest(encoder, () ->
            assertThrows(IllegalStateException.class, server::captureServingView,
                "admission must fence new native leases before the lexical view publishes")));
        assertEquals(ComponentRecoveryAction.Outcome.FAILED, drainRefusal.outcome());
        assertEquals(1, encoder.snapshot().recoveryAttempts());
        assertEquals(0, compositionAttempts.get(),
            "a held native view must refuse before exact recomposition");
      }

      var cancelledResult = server.recoverEncoders(
          new RecoveryRequest(encoder, () -> {}, cancelled::get));
      assertEquals(ComponentRecoveryAction.Outcome.FAILED, cancelledResult.outcome());
      assertEquals(ComponentState.FAILED, encoder.snapshot().state());
      assertEquals(2, encoder.snapshot().recoveryAttempts());
      assertEquals(1, constructedNer.constructed().size());
      org.mockito.Mockito.verify(constructedNer.constructed().getFirst()).close();
      try (var lexicalServing = server.captureServingView()) {
        assertNull(lexicalServing.encoderSet(),
            "cancellation after retirement must leave lexical service without reopening native");
      }

      cancelled.set(false);
      var newlyMissing = server.recoverEncoders(new RecoveryRequest(encoder));
      assertEquals(ComponentRecoveryAction.Outcome.FAILED, newlyMissing.outcome());
      assertEquals(ComponentState.FAILED, encoder.snapshot().state());
      assertEquals(3, encoder.snapshot().recoveryAttempts());
      assertTrue(encoder.snapshot().evidence().contains("newly missing roles"));

      var result = server.recoverEncoders(new RecoveryRequest(encoder));

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertEquals(ComponentState.READY, encoder.snapshot().state());
      assertEquals(4, encoder.snapshot().recoveryAttempts());
      assertEquals("Exact captured encoder plan recomposed", encoder.snapshot().evidence(),
          "the same admitted attempt may publish late READY after its deadline row");
      assertTrue(sourceOwner.isClosed(), "the failed native owner must retire");
      try (var serving = server.captureServingView()) {
        assertSame(producer, serving.services());
        assertTrue(serving.encoderSet() != sourceOwner,
            "recovery publishes a fresh physical owner");
      }
      org.mockito.Mockito.verify(producer).parkProducerModelsForEncoderRecovery();
      org.mockito.Mockito.verify(producer).wireRecoveredEncoders(
          org.mockito.ArgumentMatchers.isNull(), any(), org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), any());
      org.mockito.Mockito.verify(producer).resumeProducerAfterCutover();
    } finally {
      if (sourceView != null) releaseServingModelSets(sourceView);
      if (!sourceQuery.isClosed()) sourceQuery.close();
      if (!sourceOwner.isClosed()) sourceOwner.close();
    }
  }

  @Test
  void failedInitialCompositionRecoversFromPreNativePlanWithoutAnIncumbentOwner(
      @TempDir Path tempDir) throws Exception {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), tempDir.resolve("data").toString());
    values.put(EnvRegistry.INDEX_BASE_PATH.configKey(), tempDir.resolve("index").toString());
    values.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    values.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "false");
    values.put(EnvRegistry.SPLADE_ENABLED.configKey(), "false");
    values.put(EnvRegistry.NER_ENABLED.configKey(), "true");
    values.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "false");
    values.put(EnvRegistry.RERANK_ENABLED.configKey(), "false");
    values.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false");
    var configuration = TestResolvedConfigHelper.fromEntries(values);
    var projection = EncoderConfigurationProjection.from(configuration);
    var querySelection = new io.justsearch.app.api.settings.QueryRoleSelection(
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
    var observation = new InferenceSurface.ComponentObservation(
        Optional.of(projection.digest()), Set.of(io.justsearch.ort.EncoderRole.NER), Set.of(),
        Optional.of(querySelection));
    var recoveredSurface = new InferenceSurface(Optional.empty(),
        Optional.of(mock(NerAssembly.class)), Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), mock(io.justsearch.ort.PolicySnapshot.class), List.of(), observation);
    var plan = new IndexCompositionPlan(projection, HardwareProfile.cpuOnly(),
        io.justsearch.ort.RuntimePolicy.defaults(),
        Map.of(io.justsearch.ort.EncoderRole.NER,
            new IndexCompositionPlan.RolePlan(true, null, null, null, List.of(), null)), false);
    var absent = io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint
        .notConfigured();
    var identity = new EncoderSet.ModelIdentity(absent, absent, absent, false, 768);

    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents();
        var composition = mockStatic(InferenceCompositionRoot.class);
        var indexProjection = mockStatic(IndexConfigurationProjection.class);
        MockedConstruction<NerService> constructedNer = mockConstruction(NerService.class)) {
      var encoder = components.handle("encoders");
      var producer = mock(DefaultWorkerAppServices.class);
      var runtime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      when(producer.pauseProducerForCutover(org.mockito.ArgumentMatchers.anyLong()))
          .thenReturn(true);
      composition.when(() -> InferenceCompositionRoot.composeCaptured(any(), any(), any(),
          any(), any())).thenReturn(recoveredSurface);
      indexProjection.when(() -> IndexConfigurationProjection.digest(any(), any(), any(), any(),
          any(), any(), org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn("index-a");
      var server = new KnowledgeServer(executors, WorkerConfig.load(configuration), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, encoder,
          configuration);
      setField(server, "running", true);
      setField(server, "searchLifecycle", runtime);
      setField(server, "ingestLifecycle", runtime);
      server.appServices = producer;
      server.publishServingView(producer);
      setField(server, "initialIndexCompositionPlan", plan);
      setField(server, "initialModelIdentity", identity);
      var identityStore = mock(
          io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore.class);
      when(identityStore.deletionGraceMs()).thenReturn(0L);
      setField(server, "documentIdentityStore", identityStore);
      setField(server, "resolvedBootQueryObservation",
          recoveredSurface.partitionQueryRoles(projection).query().componentObservation());
      encoder.setDesiredVersion(projection.digest());
      encoder.transition(ComponentState.FAILED, "encoder_service_wiring_failed",
          "initial native assembly failed after capture");

      var result = server.recoverEncoders(new RecoveryRequest(encoder));

      assertEquals(ComponentRecoveryAction.Outcome.RECOVERED, result.outcome());
      assertEquals(ComponentState.READY, encoder.snapshot().state());
      assertEquals(projection.digest(), encoder.snapshot().appliedVersion(),
          "ownerless recovery must atomically install its verified physical digest");
      assertEquals(1, encoder.snapshot().recoveryAttempts());
      assertEquals(1, constructedNer.constructed().size());
      try (var serving = server.captureServingView()) {
        assertSame(producer, serving.services());
        assertNotNull(serving.encoderSet(),
            "ownerless failed initial composition must publish its first exact owner");
      }
      composition.verify(() -> InferenceCompositionRoot.composeCaptured(
          org.mockito.ArgumentMatchers.same(plan), any(), any(), any(), any()));
    }
  }

  @Test
  void knownMissingInitialRoleRecoversDegradedWithoutBecomingRetryable(
      @TempDir Path tempDir) throws Exception {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), tempDir.resolve("data").toString());
    values.put(EnvRegistry.INDEX_BASE_PATH.configKey(), tempDir.resolve("index").toString());
    values.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    values.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "false");
    values.put(EnvRegistry.SPLADE_ENABLED.configKey(), "false");
    values.put(EnvRegistry.NER_ENABLED.configKey(), "true");
    values.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "false");
    values.put(EnvRegistry.RERANK_ENABLED.configKey(), "false");
    values.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false");
    var configuration = TestResolvedConfigHelper.fromEntries(values);
    var projection = EncoderConfigurationProjection.from(configuration);
    var querySelection = new io.justsearch.app.api.settings.QueryRoleSelection(
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
        io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
    var observation = new InferenceSurface.ComponentObservation(
        Optional.of(projection.digest()), Set.of(io.justsearch.ort.EncoderRole.NER),
        Set.of(io.justsearch.ort.EncoderRole.NER), Optional.of(querySelection));
    var recoveredSurface = new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        mock(io.justsearch.ort.PolicySnapshot.class), List.of(), observation);
    var plan = new IndexCompositionPlan(projection, HardwareProfile.cpuOnly(),
        io.justsearch.ort.RuntimePolicy.defaults(),
        Map.of(io.justsearch.ort.EncoderRole.NER,
            IndexCompositionPlan.RolePlan.unavailable(true)), false);
    var absent = io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint
        .notConfigured();
    var identity = new EncoderSet.ModelIdentity(absent, absent, absent, false, 768);

    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents();
        var composition = mockStatic(InferenceCompositionRoot.class);
        var indexProjection = mockStatic(IndexConfigurationProjection.class)) {
      var encoder = components.handle("encoders");
      var producer = mock(DefaultWorkerAppServices.class);
      var runtime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      when(producer.pauseProducerForCutover(org.mockito.ArgumentMatchers.anyLong()))
          .thenReturn(true);
      composition.when(() -> InferenceCompositionRoot.composeCaptured(any(), any(), any(),
          any(), any())).thenReturn(recoveredSurface);
      indexProjection.when(() -> IndexConfigurationProjection.digest(any(), any(), any(), any(),
          any(), any(), org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn("index-a");
      var server = new KnowledgeServer(executors, WorkerConfig.load(configuration), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, encoder,
          configuration);
      setField(server, "running", true);
      setField(server, "searchLifecycle", runtime);
      setField(server, "ingestLifecycle", runtime);
      server.appServices = producer;
      server.publishServingView(producer);
      setField(server, "initialIndexCompositionPlan", plan);
      setField(server, "initialModelIdentity", identity);
      var identityStore = mock(
          io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore.class);
      when(identityStore.deletionGraceMs()).thenReturn(0L);
      setField(server, "documentIdentityStore", identityStore);
      setField(server, "resolvedBootQueryObservation",
          recoveredSurface.partitionQueryRoles(projection).query().componentObservation());
      encoder.setDesiredVersion(projection.digest());
      encoder.transition(ComponentState.FAILED,
          io.justsearch.app.api.lifecycle.LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code(),
          "initial NER model was already unavailable");

      var result = server.recoverEncoders(new RecoveryRequest(encoder));

      assertEquals(ComponentRecoveryAction.Outcome.DEGRADED, result.outcome());
      assertEquals(ComponentState.UNAVAILABLE, encoder.snapshot().state());
      assertNull(encoder.snapshot().reasonCode(),
          "coherent known-missing restoration must clear the retryable failure reason");
      assertEquals("missing_roles=NER", encoder.snapshot().evidence());
      assertEquals(1, encoder.snapshot().recoveryAttempts());
      try (var serving = server.captureServingView()) {
        assertSame(producer, serving.services());
        assertNotNull(serving.encoderSet());
      }
    }
  }

  @Test
  void recoveryDispositionIsDerivedFromPromotedPhysicalState() throws Exception {
    String operation = "01890f2a-7b3c-7def-8123-456789abcdef";
    var owner = new IndexGenerationManager.BootOwnership.Recorded(
        operation, "source-a", "bulk", "a".repeat(64), true);
    var state = new IndexGenerationManager.State(2,
        IndexGenerationManager.recordedGenerationId(operation), null, "source-a",
        IndexGenerationManager.MigrationState.IDLE.name(), null, null, null, 1L,
        null, null, null);

    assertEquals(IndexGenerationManager.BootDisposition.PROMOTED,
        KnowledgeServer.currentBootDisposition(owner, state));
  }

  @Test
  void reservationRetainsKnownDisabledQueryObservationBeforePhysicalClose(
      @TempDir Path tempDir) throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null, snapshot);
      try {
        server.start();
        assertTrue(server.awaitIndexRecoveryModels(10_000));
        index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
        var request = new RecoveryRequest(index);

        var context = server.reserveIndexRecovery(request).orElseThrow();

        assertTrue(context.recoveryAttempt());
        assertEquals(index.snapshot().appliedVersion(), context.priorAppliedDigest());
        assertNotNull(context.generationState());
        assertNotNull(context.generationManifest());
        assertEquals(io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
            context.queryObservation().querySelection().orElseThrow().reranker());
        assertEquals(ComponentState.STARTING, index.snapshot().state());
        server.closeForRecovery();
        assertTrue(server.awaitClosed(0));
      } finally {
        if (!server.awaitClosed(0)) server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void partialCloseRetryReusesExactReservationWithoutRecapturing(@TempDir Path tempDir)
      throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null, snapshot);
      try {
        server.start();
        assertTrue(server.awaitIndexRecoveryModels(10_000));
        index.transition(ComponentState.FAILED, "index.failed", "first failure");
        var first = server.reserveIndexRecovery(new RecoveryRequest(index)).orElseThrow();

        index.transition(ComponentState.FAILED, "component.recovery_failed", "partial close");
        var second = server.reserveIndexRecovery(new RecoveryRequest(index)).orElseThrow();

        assertSame(first, second,
            "a counted retry after partial close must retain the pre-close physical capsule");
        assertEquals(2, index.snapshot().recoveryAttempts());
      } finally {
        server.closeForRecovery();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void synchronousAdmissionCloseCannotCertifyStaleReservation(@TempDir Path tempDir)
      throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null, snapshot);
      try {
        server.start();
        assertTrue(server.awaitIndexRecoveryModels(10_000));
        index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
        var request = new RecoveryRequest(index, () -> {
          try {
            server.closeForRecovery();
          } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        });

        assertTrue(server.reserveIndexRecovery(request).isEmpty());
        assertTrue(request.admitted().isPresent(), "admission was counted before its observer ran");
        assertTrue(server.awaitClosed(0));
        assertEquals(1, index.snapshot().recoveryAttempts());
      } finally {
        if (!server.awaitClosed(0)) server.closeForRecovery();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void deferredInitializerCannotPublishAfterRecoveryReservation(@TempDir Path tempDir)
      throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var encoder = components.handle("encoders");
      var server = spy(new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, encoder,
          snapshot));
      var initializer = new AtomicReference<Runnable>();
      doAnswer(invocation -> {
        initializer.set(invocation.getArgument(0));
        server.deferredModelInit = new CompletableFuture<>();
        return null;
      }).when(server).startDeferredModelInitialization(any());
      var disabled = new io.justsearch.app.api.settings.QueryRoleSelection(
          io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
          io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
      server.bindBootQueryRoleSelection(disabled);
      try {
        server.start();
        Object published = server.appServices();
        index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
        var context = server.reserveIndexRecovery(new RecoveryRequest(index)).orElseThrow();

        initializer.get().run();

        assertSame(published, server.appServices(),
            "late deferred initialization must not replace the reserved serving owner");
        assertNull(context.queryObservation(),
            "reservation used the exact captured boot input before deferred resolution");
        assertEquals(ComponentState.ABSENT, encoder.snapshot().state(),
            "late initialization must not narrate a new encoder start");
      } finally {
        server.deferredModelInit.complete(null);
        server.closeForRecovery();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void changedGenerationManifestRefusesRecoveryWithoutRepair(@TempDir Path tempDir)
      throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var incumbent = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null, snapshot);
      incumbent.start();
      assertTrue(incumbent.awaitIndexRecoveryModels(10_000));
      index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
      var context = incumbent.reserveIndexRecovery(new RecoveryRequest(index)).orElseThrow();
      incumbent.closeForRecovery();

      var manifest = context.generationManifest();
      var changed = new IndexGenerationManager.GenerationManifest(manifest.format_version(),
          manifest.generation_id(), manifest.source(), manifest.created_at_ms() + 1,
          manifest.target_index_fingerprint(), manifest.models(), manifest.sparse_model(),
          manifest.vector_dimension(), manifest.projection_source_ids());
      Path manifestPath = snapshot.paths().indexBasePath().resolve("indices")
          .resolve(context.generationState().active_generation())
          .resolve(".justsearch-index-generation.json");
      byte[] changedBytes = tools.jackson.databind.json.JsonMapper.builder().build()
          .writeValueAsBytes(changed);
      Files.write(manifestPath, changedBytes);

      var replacement = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null, snapshot);
      replacement.bindIndexStartContext(context);
      try {
        IOException refused = assertThrows(IOException.class, replacement::start);
        assertTrue(hasCause(refused, KnowledgeServer.IndexRecoverySupersededException.class));
        assertArrayEquals(changedBytes, Files.readAllBytes(manifestPath),
            "recovery inspection must not repair or adopt changed physical state");
      } finally {
        if (!replacement.awaitClosed(0)) replacement.closeForRecovery();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void changedGenerationStateRefusesRecoveryWithoutRepair(@TempDir Path tempDir)
      throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var incumbent = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null, snapshot);
      incumbent.start();
      assertTrue(incumbent.awaitIndexRecoveryModels(10_000));
      index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
      var context = incumbent.reserveIndexRecovery(new RecoveryRequest(index)).orElseThrow();
      incumbent.closeForRecovery();

      var state = context.generationState();
      var changed = new IndexGenerationManager.State(state.format_version(),
          state.active_generation(), state.building_generation(), state.previous_generation(),
          state.migration_state(), state.migration_paused(), state.pause_reason(),
          state.paused_at_ms(), state.updated_at_ms() + 1, state.auto_rebuild_key(),
          state.auto_rebuild_count(), state.auto_rebuild_first_ms());
      Path statePath = snapshot.paths().indexBasePath().resolve("state.json");
      byte[] changedBytes = tools.jackson.databind.json.JsonMapper.builder().build()
          .writeValueAsBytes(changed);
      Files.write(statePath, changedBytes);

      var replacement = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null, snapshot);
      replacement.bindIndexStartContext(context);
      try {
        IOException refused = assertThrows(IOException.class, replacement::start);
        assertTrue(hasCause(refused, KnowledgeServer.IndexRecoverySupersededException.class));
        assertArrayEquals(changedBytes, Files.readAllBytes(statePath),
            "recovery inspection must not repair changed authoritative state");
      } finally {
        if (!replacement.awaitClosed(0)) replacement.closeForRecovery();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void recoveryKeepsEncoderAbsentAndReloadsExactDisabledQueryWitness(@TempDir Path tempDir)
      throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var encoder = components.handle("encoders");
      var incumbent = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, encoder,
          snapshot);
      incumbent.start();
      assertTrue(incumbent.awaitIndexRecoveryModels(10_000));
      assertEquals(ComponentState.ABSENT, encoder.snapshot().state());
      index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
      var context = incumbent.reserveIndexRecovery(new RecoveryRequest(index)).orElseThrow();
      incumbent.closeForRecovery();
      assertEquals(ComponentState.ABSENT, encoder.snapshot().state(),
          "physical close must not invent an encoder failure for disabled roles");

      var replacement = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, encoder,
          snapshot);
      replacement.bindIndexStartContext(context);
      try {
        replacement.start();
        assertTrue(replacement.awaitIndexRecoveryModels(10_000));
        var actual = replacement.finalizeIndexRecovery(context).orElseThrow();
        assertEquals(context.queryObservation().querySelection(),
            actual.queryObservation().querySelection());
        assertEquals(ComponentState.ABSENT, encoder.snapshot().state());
        assertThrows(IllegalStateException.class, replacement::captureServingView,
            "query settings cannot capture a physical predecessor before terminal CAS");
        var losingCurrent = index.snapshot();
        replacement.armIndexRecoveryServing(losingCurrent);
        index.setDesiredVersion("changed-before-terminal-cas");
        assertFalse(index.transitionIfUnchanged(
            losingCurrent, ComponentState.READY, null, null));
        replacement.disarmIndexRecoveryServing();
        assertThrows(IllegalStateException.class, replacement::captureServingView,
            "a lost terminal CAS cannot admit the retained physical view");
        var expectedCurrent = index.snapshot();
        replacement.armIndexRecoveryServing(expectedCurrent);
        assertTrue(index.transitionIfUnchanged(expectedCurrent, ComponentState.READY, null, null));
        replacement.confirmIndexRecoveryServing(index.snapshot());
        index.transition(ComponentState.UNAVAILABLE, "index.failed", "transient health narration");
        try (var accepted = replacement.captureServingView()) {
          assertNotNull(accepted,
              "the confirmed physical handover survives later mutable lifecycle narration");
        }
        replacement.acceptIndexRecovery();
        try (var accepted = replacement.captureServingView()) {
          assertNotNull(accepted);
        }
      } finally {
        replacement.closeForRecovery();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void acceptedRecoveryRetainsPhysicalFenceUntilDeferredCompositionCompletes(
      @TempDir Path tempDir) throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null, snapshot);
      var disabled = new io.justsearch.app.api.settings.QueryRoleSelection(
          io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
          io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
      var observation = new InferenceSurface.ComponentObservation(Optional.of("query-a"),
          Set.of(), Set.of(), Optional.of(disabled));
      var context = new KnowledgeServer.IndexStartContext(snapshot, null, null, null, null,
          disabled, observation, null, true);
      var composition = new CompletableFuture<Void>();
      server.bindIndexStartContext(context);
      server.deferredModelInit = composition;
      setField(server, "running", true);

      server.acceptIndexRecovery();
      assertSame(context, field(server, "recoveryStartContext"),
          "terminal index publication must retain the physical fence during native assembly");

      composition.complete(null);
      assertNull(field(server, "recoveryStartContext"),
          "the exact deferred owner releases the fence when its composition settles");
    }
  }

  @Test
  void staleOrClosingDeferredCompletionCannotClearCurrentRecoveryFence(
      @TempDir Path tempDir) throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index,
          components.handle("encoders"), snapshot);
      var disabled = new io.justsearch.app.api.settings.QueryRoleSelection(
          io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled(),
          io.justsearch.app.api.settings.QueryRoleSelection.Role.disabled());
      var observation = new InferenceSurface.ComponentObservation(Optional.of("query-a"),
          Set.of(), Set.of(), Optional.of(disabled));
      var first = new KnowledgeServer.IndexStartContext(snapshot, null, null, null, null,
          disabled, observation, "physical-a", true);
      var current = new KnowledgeServer.IndexStartContext(snapshot, null, null, null, null,
          disabled, observation, "physical-b", true);
      var firstComposition = new CompletableFuture<Void>();
      server.bindIndexStartContext(first);
      server.deferredModelInit = firstComposition;
      setField(server, "running", true);
      index.transition(ComponentState.STARTING, "component.recovering", "first");

      server.armIndexRecoveryServing(index.snapshot());
      server.acceptIndexRecovery();
      setField(server, "recoveryStartContext", current);
      firstComposition.complete(null);
      assertSame(current, field(server, "recoveryStartContext"),
          "a stale accepted owner cannot clear a newer physical recovery fence");
      assertNotNull(field(server, "indexRecoveryServingAdmission"),
          "a stale completion cannot silently clear the retained admission phase");
      assertThrows(IllegalStateException.class, server::captureServingView,
          "a stale terminal arm cannot admit a newer recovery context");

      var closingComposition = new CompletableFuture<Void>();
      server.deferredModelInit = closingComposition;
      server.armIndexRecoveryServing(index.snapshot());
      server.acceptIndexRecovery();
      setField(server, "closeStarted", true);
      closingComposition.complete(null);
      assertSame(current, field(server, "recoveryStartContext"),
          "a completion racing ordered close cannot release the physical fence");
      assertNotNull(field(server, "indexRecoveryServingAdmission"),
          "ordered close retains the exact phase witness for physical cleanup");
      assertThrows(IllegalStateException.class, server::captureServingView,
          "ordered close revokes an armed serving admission");
    }
  }

  @Test
  void unknownPublishedQueryObservationRefusesBeforeAdmission(@TempDir Path tempDir)
      throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = realServer(executors, snapshot, index, RecordedIngestionLifecycle.denied());
      try {
        server.start();
        index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
        var before = index.snapshot();

        assertTrue(server.reserveIndexRecovery(new RecoveryRequest(index)).isEmpty());
        assertEquals(before, index.snapshot());
        assertNotNull(server.appServices(), "refusal must precede every physical close effect");
      } finally {
        server.closeForRecovery();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void recoveryCloseReleasesRealIndexLockAndRetainsFailedAndStartingObservations(
      @TempDir Path tempDir) throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = realServer(executors, snapshot, index, RecordedIngestionLifecycle.denied());
      try {
        server.start();
        var started = index.snapshot();
        assertNotNull(server.appServices(), "physical services must exist before recovery close");
        assertThrows(IOException.class, () -> assertIndexLockAvailable(snapshot),
            "the real index root must be locked before recovery close");
        assertEquals(ComponentState.STARTING, started.state(),
            "the bootstrap health owner has not yet published READY");
        assertNotNull(started.appliedVersion(), "real index owner must publish its applied digest");

        index.transition(ComponentState.FAILED, "index.failed", "physical owner failed");
        var failed = index.snapshot();
        server.closeForRecovery();
        assertTrue(server.awaitClosed(0));
        var afterFailedClose = index.snapshot();
        assertEquals(ComponentState.FAILED, afterFailedClose.state());
        assertEquals(failed.appliedVersion(), afterFailedClose.appliedVersion());
        assertEquals(failed.recoveryAttempts(), afterFailedClose.recoveryAttempts());
        assertIndexLockAvailable(snapshot);
      } finally {
        if (!server.awaitClosed(0)) server.close();
      }

      var retry = realServer(executors, snapshot, index, RecordedIngestionLifecycle.denied());
      try {
        retry.start();
        index.transition(ComponentState.FAILED, "index.failed", "second physical owner failed");
        var admitted = index.tryBeginRecovery(index.snapshot(), "component.recovering", "retry")
            .orElseThrow();
        retry.closeForRecovery();
        assertTrue(retry.awaitClosed(0));
        var afterStartingClose = index.snapshot();
        assertEquals(ComponentState.STARTING, afterStartingClose.state());
        assertEquals(admitted.appliedVersion(), afterStartingClose.appliedVersion());
        assertEquals(admitted.recoveryAttempts(), afterStartingClose.recoveryAttempts());
        assertIndexLockAvailable(snapshot);
      } finally {
        if (!retry.awaitClosed(0)) retry.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void normalCloseReleasesRealIndexLockAndPublishesAbsentWithoutAppliedDigest(
      @TempDir Path tempDir) throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = realServer(executors, snapshot, index, RecordedIngestionLifecycle.denied());
      try {
        server.start();
        assertNotNull(index.snapshot().appliedVersion());
        server.close();
        assertTrue(server.awaitClosed(0));
        assertEquals(ComponentState.ABSENT, index.snapshot().state());
        assertNull(index.snapshot().appliedVersion());
        assertIndexLockAvailable(snapshot);
      } finally {
        if (!server.awaitClosed(0)) server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void failedStartupAfterRealOwnerAcquisitionRetainsFailedRowAndReleasesLock(
      @TempDir Path tempDir) throws Exception {
    var snapshot = disabledModelConfig(tempDir);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    var failure = new IllegalStateException("recorded attachment refused after index open");
    try (var executors = new TestEngineExecutors();
        var components = TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var lifecycle = failingAttachment(failure);
      var server = realServer(executors, snapshot, index, lifecycle);
      try {
        var startup = assertThrows(IOException.class, server::start);
        assertSame(failure, startup.getCause());
        assertEquals(ComponentState.FAILED, index.snapshot().state());
        var failedContext = server.failedIndexStartContext();
        assertNotNull(failedContext.generationState(),
            "failure after layout resolution must retain its exact generation state");
        assertNotNull(failedContext.generationManifest(),
            "failed cleanup must not downgrade the resolved generation witness");
        assertTrue(server.awaitClosed(0), "failed startup must finish physical cleanup");
        assertIndexLockAvailable(snapshot);
      } finally {
        if (!server.awaitClosed(0)) server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  private static KnowledgeServer realServer(TestEngineExecutors executors,
      ResolvedConfig snapshot, ComponentHandle index, RecordedIngestionLifecycle lifecycle) {
    var server = spy(new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
        ManagedChildRegistry.noop(), lifecycle, index, null, snapshot));
    doNothing().when(server).startDeferredModelInitialization(any());
    return server;
  }

  private static void assertIndexLockAvailable(ResolvedConfig snapshot) throws Exception {
    try (var lock = new IndexRootLock(snapshot.paths().indexBasePath())) {
      lock.acquire();
    }
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (type.isInstance(current)) return true;
      if (current == current.getCause()) return false;
    }
    return false;
  }

  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void releaseServingModelSets(Object servingView)
      throws ReflectiveOperationException {
    Method release = servingView.getClass().getDeclaredMethod("releaseModelSets");
    release.setAccessible(true);
    release.invoke(servingView);
  }

  private static void setField(Object owner, String name, Object value)
      throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
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
    return TestResolvedConfigHelper.fromEntries(values);
  }

  private static RecordedIngestionLifecycle failingAttachment(Throwable failure) {
    return new RecordedIngestionLifecycle() {
      @Override
      public JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey) {
        return JobQueue.RecordedClaimDecision.DENY;
      }

      @Override
      public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
          java.util.function.BooleanSupplier workerOnline) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new java.io.UncheckedIOException((IOException) failure);
      }
    };
  }

  private static final class RecoveryRequest implements ComponentRecoveryAction.Request {
    private final ComponentHandle handle;
    private final EngineComponentSnapshot.Component expected;
    private final AtomicReference<EngineComponentSnapshot.Component> admitted =
        new AtomicReference<>();
    private final Runnable afterAdmission;
    private final java.util.function.BooleanSupplier cancelled;

    private RecoveryRequest(ComponentHandle handle) {
      this(handle, () -> {}, () -> false);
    }

    private RecoveryRequest(ComponentHandle handle, Runnable afterAdmission) {
      this(handle, afterAdmission, () -> false);
    }

    private RecoveryRequest(ComponentHandle handle, Runnable afterAdmission,
        java.util.function.BooleanSupplier cancelled) {
      this.handle = handle;
      this.afterAdmission = afterAdmission;
      this.cancelled = cancelled;
      expected = handle.snapshot();
    }

    @Override public EngineComponentSnapshot.Component expected() { return expected; }
    @Override public EngineComponentSnapshot.Component current() { return handle.snapshot(); }
    @Override public Optional<EngineComponentSnapshot.Component> admitted() {
      return Optional.ofNullable(admitted.get());
    }
    @Override public boolean begin() {
      var started = handle.tryBeginRecovery(expected, "component.recovering", "test");
      started.ifPresent(admitted::set);
      if (started.isPresent()) afterAdmission.run();
      return started.isPresent();
    }
    @Override public Optional<EngineComponentSnapshot.Component> complete(
        EngineComponentSnapshot.Component current, ComponentState state,
        String reasonCode, String evidence) {
      return handle.tryTransitionIfUnchanged(current, state, reasonCode, evidence);
    }
    @Override public boolean cancelled() { return cancelled.getAsBoolean(); }
  }
}

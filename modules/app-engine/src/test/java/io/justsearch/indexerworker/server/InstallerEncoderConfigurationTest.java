/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.app.engine.DefaultEngineComponentRegistry;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.ort.EncoderRole;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real settings transaction, registry batch and B owners; model execution is covered by live checks. */
final class InstallerEncoderConfigurationTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000207";
  private static final String CAP = "justsearch.embed.gpu_mem_mb";

  @Test
  void generationCapChangesPublishThroughBWithoutCallingAsQueryOnlyOwner(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, false, false)) {
      var projection = assertDoesNotThrow(() -> fixture.prepare(fixture.b),
          "B already owns these sessions; A's guarded query-only composer cannot prepare them");
      fixture.include(projection);
      assertEquals(0, fixture.queryPreparations.get());
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
      assertSame(fixture.a, fixture.config.get());
      fixture.config.publicationLock().writeLock().lock();
      try {
        projection.admitBeforePointer();
        assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
        projection.afterPointerCommitted();
      } finally { fixture.config.publicationLock().writeLock().unlock(); }
      projection.afterRuntimePublished();
      assertEquals(new SettingsWitness(1, KEY), fixture.settings.inspect().witness());
      assertSame(fixture.b, fixture.config.get());
      assertEquals(2054, fixture.config.get().ai().embedding().gpuMemMb());
      assertEquals(fixture.projection.digest(), fixture.encoder.snapshot().appliedVersion());
      fixture.owner.releaseAfterTerminal(1);
    }
  }

  @Test
  void selectedMetadataForDisabledRolesIsNotSettingsDrift(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, false, false, true)) {
      assertNotEquals(EncoderConfigurationProjection.from(fixture.b).digest(), fixture.projection.digest(),
          "generation selection also binds metadata for disabled BGE-M3");
      var prepared = assertDoesNotThrow(() -> fixture.prepare(fixture.b));
      assertEquals(0, fixture.queryPreparations.get());
      prepared.abortBeforePointer();
      fixture.owner.releaseAfterTerminal(1);
    }
  }

  @Test
  void changedAutomaticCapCannotBeCertifiedByIndexIdentityDigest(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, false, false)) {
      ResolvedConfig drifted = fixture.configuration(2055);
      var drift = EncoderConfigurationProjection.from(drifted);
      assertEquals(fixture.projection.indexDigest(), drift.indexDigest(), "model identity excludes automatic caps");
      assertNotEquals(fixture.projection.indexSessionDigest(), drift.indexSessionDigest());
      var failure = assertThrows(IllegalStateException.class, () -> fixture.prepare(drifted));
      assertTrue(failure.getMessage().contains("B's physical configuration"), failure::toString);
      assertEquals(0, fixture.queryPreparations.get());
      assertSame(fixture.a, fixture.config.get());
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
    }
  }

  @Test
  void settingsOutsideBsPhysicalProjectionCannotBeConsumed(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, false, false)) {
      var desired = fixture.configuration(2054, Map.of("justsearch.splade.evidence_path",
          dir.resolve("other-evidence.json").toString()));
      var failure = assertThrows(IllegalStateException.class, () -> fixture.prepare(desired));
      assertTrue(failure.getMessage().contains("unprepared encoder"), failure::toString);
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
    }
  }

  @Test
  void plannerDiagnosticsMustMatchTheComposedCandidate(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, false, false)) {
      var desired = fixture.configuration(2054, Map.of("justsearch.gpu.memory_plan", "other-plan"));
      var failure = assertThrows(IllegalStateException.class, () -> fixture.prepare(desired));
      assertTrue(failure.getMessage().contains("unprepared encoder"), failure::toString);
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
    }
  }

  @Test
  void unknownObservationsDoNotCertifyGenerationSettings(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, true, false)) {
      var failure = assertThrows(IllegalStateException.class, () -> fixture.prepare(fixture.b));
      assertTrue(failure.getMessage().contains("B's physical configuration"), failure::toString);
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
    }
  }

  @Test
  void missingRequestedRoleDoesNotCertifyGenerationSettings(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, false, true)) {
      var failure = assertThrows(IllegalStateException.class, () -> fixture.prepare(fixture.b));
      assertTrue(failure.getMessage().contains("realized role observations"), failure::toString);
      assertEquals(new SettingsWitness(0, null), fixture.settings.inspect().witness());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Path directory;
    final TestEngineExecutors executors = new TestEngineExecutors();
    final DefaultEngineComponentRegistry components;
    final ComponentHandle encoder;
    final AtomicInteger queryPreparations = new AtomicInteger();
    final UiSettings settingsCandidate = new UiSettings();
    final UiSettingsStore settings;
    final ResolvedConfig a;
    final ResolvedConfig b;
    final ConfigStore config;
    final KnowledgeServer server;
    final EncoderConfigurationProjection projection;
    final EncoderSet index;
    final QueryRoleSet query;
    final SettingsCommitCoordinator owner;
    final RecordedInstallerGenerationPlan plan;

    Fixture(Path directory, boolean unknown, boolean missing) throws Exception {
      this(directory, unknown, missing, false);
    }

    Fixture(Path directory, boolean unknown, boolean missing, boolean selected) throws Exception {
      this.directory = directory;
      a = configuration(1024);
      b = configuration(2054);
      config = new ConfigStore(a);
      var budget = new RetainedStateBudget();
      budget.declare("attempted-configurations", 1, "test");
      components = new DefaultEngineComponentRegistry(budget, config.publicationLock());
      encoder = components.register(new ComponentSpec("encoders", false,
          InferenceCompositionRoot.componentDependencies(), ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY,
          java.time.Duration.ZERO, 2));
      settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
      var selectedModels = Map.of("embedding", new io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact(
          directory.resolve("embedding/model.onnx").toString(), "0".repeat(64)));
      var selection = GenerationModelSelection.accepted(selectedModels, b.ai().sparseModel(), 768);
      projection = selected ? EncoderConfigurationProjection.from(b, selection) : EncoderConfigurationProjection.from(b);
      var indexObservation = unknown ? InferenceSurface.ComponentObservation.unknown()
          : new InferenceSurface.ComponentObservation(Optional.of(projection.indexDigest()),
              Set.of(EncoderRole.EMBEDDING), missing ? Set.of(EncoderRole.EMBEDDING) : Set.of());
      var indexSurface = new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(), indexObservation);
      var absent = IndexFingerprint.ModelFingerprint.notConfigured();
      index = new EncoderSet(indexSurface, new EncoderSet.ModelIdentity(absent, absent, absent, false, 768));
      if (!missing) {
        var embedding = mock(EmbeddingService.class);
        when(embedding.isAvailable()).thenReturn(true);
        index.bindEmbedding(index.own(embedding));
      }
      var querySurface = new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(projection.queryDigest()), Set.of(), Set.of()));
      query = new QueryRoleSet(querySurface);
      server = new KnowledgeServer(executors, io.justsearch.indexerworker.WorkerConfig.load(b), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(),
          null, encoder);
      if (selected) {
        var acceptedField = KnowledgeServer.class.getDeclaredField("recordedCandidate");
        acceptedField.setAccessible(true);
        acceptedField.set(server, new RecordedIngestionLifecycle.RecordedCandidate(b,
            new IndexTargetSnapshot("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", "{}"), selectedModels));
      }
      Class<?> models = Class.forName(KnowledgeServer.class.getName() + "$CandidateModels");
      var constructor = models.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Object candidate = constructor.newInstance(index, query, null,
          new IndexCompositionPlan(projection, io.justsearch.configuration.model.HardwareProfile.cpuOnly(),
              io.justsearch.ort.RuntimePolicy.defaults(), Map.of(), false));
      // A serves one runtime while B owns the ingest runtime, as during real cutover.
      var producer = mock(DefaultWorkerAppServices.class);
      var sourceRuntime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      var greenRuntime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      Class<?> viewType = Class.forName(KnowledgeServer.class.getName() + "$ServingView");
      var viewConstructor = viewType.getDeclaredConstructors()[0];
      viewConstructor.setAccessible(true);
      Object view = viewConstructor.newInstance(producer, sourceRuntime, greenRuntime, directory);
      var viewField = KnowledgeServer.class.getDeclaredField("servingView");
      viewField.setAccessible(true);
      viewField.set(server, view);
      server.appServices = producer;
      var field = KnowledgeServer.class.getDeclaredField("candidateModels");
      field.setAccessible(true);
      field.set(server, candidate);
      var composer = new FixedSettingsComponentComposer(components);
      composer.register("encoders", (settings, desired, keys) -> {
        queryPreparations.incrementAndGet();
        server.prepareQueryRoleSettings(settings, desired, keys, null);
        throw new AssertionError("A must refuse this generation-bound query-only preparation");
      });
      composer.seal();
      owner = new SettingsCommitCoordinator(settings, config, () -> {},
          candidateSettings -> OperationResult.success("prepared"), () -> false, composer);
      owner.inspectRecovery(List.of());
      String sha = "0".repeat(64);
      var provenance = new RecordedInstallerGenerationPlan.AcquisitionProvenance(
          RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY, "fixture", sha);
      Path model = directory.resolve("model.onnx").toAbsolutePath();
      plan = new RecordedInstallerGenerationPlan(KEY, "source-a", new RecordedRootPlan("source-a", List.of()),
          new IndexTargetSnapshot("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", "{}"),
          new SettingsWitness(0, null), RecordedInstallerGenerationPlan.CandidateSettings.fromJson(
              tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(settingsCandidate)),
          List.of(new RecordedInstallerGenerationPlan.ModelIdentity("embedding", "fixture", model, sha, 1, provenance)),
          List.of(new RecordedInstallerGenerationPlan.AssetIdentity("embedding/model", model, sha, 1, provenance)),
          RecordedInstallerGenerationPlan.ChatSelection.none(), provenance);
    }

    ResolvedConfig configuration(int cap) {
      return configuration(cap, Map.of());
    }

    ResolvedConfig configuration(int cap, Map<String, String> overrides) {
      var builder = ResolvedConfig.builder()
          .putDefault("justsearch.data.dir", directory.toAbsolutePath().toString())
          .putDefault("justsearch.ai.embed.enabled", "true")
          .putDefault("justsearch.embed.onnx.model_path", directory.resolve("embedding").toAbsolutePath().toString())
          .put("justsearch.gpu.memory_plan", ResolvedConfigBuilder.ORDINAL_AUTO_DETECT,
              "auto_detect", "fixture_gpu_facts", cap == 1024 ? "no_gpu" : "p12")
          .put(CAP, ResolvedConfigBuilder.ORDINAL_AUTO_DETECT, "auto_detect", "fixture_gpu_facts", Integer.toString(cap));
      overrides.forEach((key, value) -> builder.put(key, ResolvedConfigBuilder.ORDINAL_JVM_ARG,
          "test", "intervening_configuration", value));
      return builder.build();
    }

    SettingsCommitOwner.PreparedGenerationProjection prepare(ResolvedConfig desired) throws Exception {
      // The baseline has no B proof entry point. Exercise its real ordinary route so fail-before
      // is the wrong physical-owner refusal, never a missing-symbol or compilation failure.
      var control = new SettingsCommitOwner.AttemptControl() {
        @Override public boolean admitCommit(SettingsCommitOwner.Receipt receipt) { return true; }
        @Override public void committed(SettingsCommitOwner.Receipt receipt) {}
        @Override public void uncertain() { fail("settings became uncertain"); }
      };
      var reservation = owner.reserve(1, KEY, new SettingsWitness(0, null));
      try (var resolver = mockStatic(io.justsearch.app.services.config.ConfigStoreRebuilder.class, CALLS_REAL_METHODS)) {
        resolver.when(() -> io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(
            any(UiSettings.class), any(), any(ResolvedConfig.class))).thenReturn(desired);
      Class<?> proofType;
      try { proofType = Class.forName(SettingsCommitOwner.class.getName() + "$GenerationEncoders"); }
      catch (ClassNotFoundException baseline) {
        return owner.prepareInstallerGenerationProjection(reservation, settingsCandidate, control, plan);
      }
      var proofMethod = KnowledgeServer.class.getDeclaredMethod("installerEncoderProof");
      proofMethod.setAccessible(true);
      Object proof = proofMethod.invoke(server);
      var prepare = SettingsCommitCoordinator.class.getMethod("prepareInstallerGenerationProjection",
          SettingsCommitOwner.Reservation.class, UiSettings.class, SettingsCommitOwner.AttemptControl.class,
          RecordedInstallerGenerationPlan.class, proofType);
      try { return (SettingsCommitOwner.PreparedGenerationProjection)
          prepare.invoke(owner, reservation, settingsCandidate, control, plan, proof); }
      catch (InvocationTargetException failure) {
        if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
        throw failure;
      }
      }
    }

    void include(SettingsCommitOwner.PreparedGenerationProjection prepared) throws Exception {
      var method = KnowledgeServer.class.getDeclaredMethod("encoderCompositionObservation", EncoderSet.class,
          QueryRoleSet.class, ResolvedConfig.class, GenerationModelSelection.class);
      method.setAccessible(true);
      prepared.includeComponentObservation((io.justsearch.core.component.EngineComponentSnapshot.Component)
          method.invoke(server, index, query, b, null));
    }

    @Override public void close() throws Exception {
      try { server.close(); }
      finally {
        query.close();
        index.close();
        components.close();
        executors.close();
      }
    }
  }
}

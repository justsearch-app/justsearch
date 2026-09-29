/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.ort.EncoderRole;
import io.justsearch.reranker.CrossEncoderReranker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeServerComponentObservationTest {
  @TempDir Path dir;

  @Test
  void startupProgressCannotReviveAnExpiredPhysicalOpen() throws Exception {
    var config = mock(io.justsearch.indexerworker.WorkerConfig.class);
    when(config.dataDir()).thenReturn(dir);
    try (var executors = new TestEngineExecutors();
        var components = io.justsearch.core.component.TestEngineComponents.fourComponents()) {
      var index = components.handle("index");
      var server = new KnowledgeServer(executors, config, null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), index, null);
      try {
        index.transition(ComponentState.STARTING, "worker.starting", "initial open");
        server.recordIndexStartupWait("index root lock");
        org.junit.jupiter.api.Assertions.assertEquals("index root lock", index.snapshot().evidence());
        index.transition(ComponentState.FAILED, "worker.spawn.failed", "deadline: index root lock");
        var expired = index.snapshot();
        server.recordIndexStartupWait("Lucene generation");
        org.junit.jupiter.api.Assertions.assertEquals(expired, index.snapshot(),
            "an intermediate startup milestone is not proof of restored readiness");
      } finally {
        server.close();
      }
    }
  }

  @Test
  void completeCompositionRequiresAvailableWiredService() throws Exception {
    observe(Set.of(EncoderRole.EMBEDDING), Set.of(), true, true,
        ComponentState.READY, null, true);
  }

  @Test
  void composedButUnavailableServiceCannotCertifyAppliedConfiguration() throws Exception {
    observe(Set.of(EncoderRole.EMBEDDING), Set.of(), false, true,
        ComponentState.UNAVAILABLE, "missing_roles=EMBEDDING", false);
  }

  @Test
  void partialCompositionPreservesDesiredButNotFullyAppliedVersion() throws Exception {
    observe(Set.of(EncoderRole.EMBEDDING, EncoderRole.NER), Set.of(EncoderRole.NER),
        true, true, ComponentState.UNAVAILABLE, "missing_roles=NER", false);
  }

  @Test
  void intentionallyDisabledEncodersHaveAppliedConfigWithoutReadiness() throws Exception {
    observe(Set.of(), Set.of(), false, true,
        ComponentState.ABSENT, "no_encoder_roles_requested", true);
  }

  @Test
  void unknownLegacySurfaceCannotClaimReadinessOrVersion() throws Exception {
    observe(Set.of(), Set.of(), false, false,
        ComponentState.UNAVAILABLE, "encoder_observation_unknown", false);
  }

  @Test
  void unknownQueryOwnerCannotCertifyIndexOwnerVersion() throws Exception {
    observe(Set.of(EncoderRole.EMBEDDING), Set.of(), true, true, Optional.empty(),
        ComponentState.UNAVAILABLE, "encoder_observation_unknown", false);
  }

  @Test
  void mismatchedQueryOwnerCannotCertifyIndexOwnerVersion() throws Exception {
    observe(Set.of(EncoderRole.EMBEDDING), Set.of(), true, true, Optional.of("other"),
        ComponentState.UNAVAILABLE, "encoder_owner_digest_mismatch", false);
  }

  @Test
  void queryOnlyChangeCombinesRetainedIndexAndNewQueryVersion() throws Exception {
    var a = new ResolvedConfigBuilder().putSettings("justsearch.rerank.top_k", "20").build();
    var b = new ResolvedConfigBuilder().putSettings("justsearch.rerank.top_k", "21").build();
    var aProjection = EncoderConfigurationProjection.from(a);
    var bProjection = EncoderConfigurationProjection.from(b);
    org.junit.jupiter.api.Assertions.assertEquals(aProjection.indexDigest(), bProjection.indexDigest());
    org.junit.jupiter.api.Assertions.assertNotEquals(aProjection.queryDigest(), bProjection.queryDigest());
    var component = mock(ComponentHandle.class);
    var config = mock(io.justsearch.indexerworker.WorkerConfig.class);
    when(config.dataDir()).thenReturn(dir);
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors, config, null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, component);
      var configField = KnowledgeServer.class.getDeclaredField("startupConfiguration");
      configField.setAccessible(true);
      configField.set(server, b);
      var indexSurface = new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(aProjection.indexDigest()),
              Set.of(EncoderRole.EMBEDDING), Set.of()));
      var fingerprint = IndexFingerprint.ModelFingerprint.present("test-model");
      var index = new EncoderSet(indexSurface,
          new EncoderSet.ModelIdentity(fingerprint, fingerprint, fingerprint, false, 768));
      var embedding = index.own(mock(EmbeddingService.class));
      index.bindEmbedding(embedding);
      when(embedding.isAvailable()).thenReturn(true);
      var querySurface = new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(bProjection.queryDigest()),
              Set.of(EncoderRole.RERANKER), Set.of()));
      var query = new QueryRoleSet(querySurface);
      query.bindReranker(query.own(mock(CrossEncoderReranker.class)));
      var indexField = KnowledgeServer.class.getDeclaredField("initialEncoderSet");
      indexField.setAccessible(true);
      indexField.set(server, index);
      var queryField = KnowledgeServer.class.getDeclaredField("initialQueryRoleSet");
      queryField.setAccessible(true);
      queryField.set(server, query);
      server.publishEncoderComposition();
      verify(component).setDesiredVersion(bProjection.digest());
      verify(component).setAppliedVersion(bProjection.digest());
      verify(component).transition(ComponentState.READY, null, null);
      server.close();
    }
  }

  @Test
  void restoredReadyOwnerDoesNotPublishHistoricalSelectionFailure() throws Exception {
    Path selectedModel = dir.resolve("active/model.onnx").toAbsolutePath().normalize();
    Files.createDirectories(selectedModel.getParent());
    Files.writeString(selectedModel, "restored generation model");
    var identity = GenerationModelSelection.captureIdentity(selectedModel);
    Files.delete(selectedModel);
    var selection = GenerationModelSelection.accepted(Map.of(
        "embedding", new ModelArtifact(selectedModel.toString(), identity.sha256())),
        "splade", 768);
    org.junit.jupiter.api.Assertions.assertTrue(selection.variant("embedding", false).isEmpty());
    Files.writeString(selectedModel, "restored generation model");
    org.junit.jupiter.api.Assertions.assertTrue(selection.variant("embedding", false).isPresent());

    var configuration = new ResolvedConfigBuilder().build();
    var desired = EncoderConfigurationProjection.from(configuration);
    var selected = EncoderConfigurationProjection.from(configuration, selection);
    var component = mock(ComponentHandle.class);
    var workerConfig = mock(io.justsearch.indexerworker.WorkerConfig.class);
    when(workerConfig.dataDir()).thenReturn(dir);
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors, workerConfig, null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, component);
      var configurationField = KnowledgeServer.class.getDeclaredField("startupConfiguration");
      configurationField.setAccessible(true);
      configurationField.set(server, configuration);
      var selectionField = KnowledgeServer.class.getDeclaredField("initialModelSelection");
      selectionField.setAccessible(true);
      selectionField.set(server, selection);

      var indexSurface = new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(selected.indexDigest()),
              Set.of(EncoderRole.EMBEDDING), Set.of()));
      var fingerprint = IndexFingerprint.ModelFingerprint.present(identity.sha256());
      var index = new EncoderSet(indexSurface,
          new EncoderSet.ModelIdentity(fingerprint, fingerprint, fingerprint, false, 768));
      var embedding = index.own(mock(EmbeddingService.class));
      index.bindEmbedding(embedding);
      when(embedding.isAvailable()).thenReturn(true);
      var querySurface = new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(Optional.of(desired.queryDigest()),
              Set.of(), Set.of()));
      var query = new QueryRoleSet(querySurface);
      var indexField = KnowledgeServer.class.getDeclaredField("initialEncoderSet");
      indexField.setAccessible(true);
      indexField.set(server, index);
      var queryField = KnowledgeServer.class.getDeclaredField("initialQueryRoleSet");
      queryField.setAccessible(true);
      queryField.set(server, query);

      server.publishEncoderComposition();

      verify(component).transition(ComponentState.READY, null, null);
      verify(component).setDesiredVersion(desired.digest());
      verify(component).setAppliedVersion(selected.withQueryFrom(desired).digest());
      server.close();
    }
  }

  private void observe(Set<EncoderRole> requested, Set<EncoderRole> missing,
      boolean serviceAvailable, boolean known, ComponentState expected, String evidence,
      boolean applied) throws Exception {
    observe(requested, missing, serviceAvailable, known,
        known ? Optional.of("digest") : Optional.empty(), expected, evidence, applied);
  }

  private void observe(Set<EncoderRole> requested, Set<EncoderRole> missing,
      boolean serviceAvailable, boolean known, Optional<String> queryDigest,
      ComponentState expected, String evidence, boolean applied) throws Exception {
    var component = mock(ComponentHandle.class);
    var config = mock(io.justsearch.indexerworker.WorkerConfig.class);
    when(config.dataDir()).thenReturn(dir);
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors, config, null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, component);
      var surface = new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(known ? Optional.of("digest") : Optional.empty(),
              requested, missing));
      var fingerprint = IndexFingerprint.ModelFingerprint.present("test-model");
      var owner = new EncoderSet(surface,
          new EncoderSet.ModelIdentity(fingerprint, fingerprint, fingerprint, false, 768));
      var embedding = owner.own(mock(EmbeddingService.class));
      owner.bindEmbedding(embedding);
      when(embedding.isAvailable()).thenReturn(serviceAvailable);
      var ownerField = KnowledgeServer.class.getDeclaredField("initialEncoderSet");
      ownerField.setAccessible(true);
      ownerField.set(server, owner);
      var querySurface = new InferenceSurface(Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
          new InferenceSurface.ComponentObservation(queryDigest, Set.of(), Set.of()));
      var queryField = KnowledgeServer.class.getDeclaredField("initialQueryRoleSet");
      queryField.setAccessible(true);
      queryField.set(server, new QueryRoleSet(querySurface));
      server.publishEncoderComposition();
      verify(component).transition(expected, null, evidence);
      verify(component, known ? times(1) : never()).setDesiredVersion("digest");
      verify(component, applied ? times(1) : never()).setAppliedVersion("digest");
    }
  }
}

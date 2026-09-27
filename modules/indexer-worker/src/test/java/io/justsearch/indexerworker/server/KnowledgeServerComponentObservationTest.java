/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.ort.EncoderRole;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeServerComponentObservationTest {
  @TempDir Path dir;

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

  private void observe(Set<EncoderRole> requested, Set<EncoderRole> missing,
      boolean serviceAvailable, boolean known, ComponentState expected, String evidence,
      boolean applied) throws Exception {
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
      server.publishEncoderComposition();
      verify(component).transition(expected, null, evidence);
      verify(component, known ? times(1) : never()).setDesiredVersion("digest");
      verify(component, applied ? times(1) : never()).setAppliedVersion("digest");
    }
  }
}

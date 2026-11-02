/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RerankerSwitchTest {
  @Test
  void offRefusesMemoryAdmissionWithoutReplacingTheServingView(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 0L);
        var composition = f.composition()) {
      // More than A can release: neither beside nor in-place composition fits.
      composition.when(() -> InferenceCompositionRoot.estimateQueryFootprintBytes(any(), any(), any()))
          .thenReturn(4097L);
      UiSettings candidate = new UiSettings();
      candidate.setRerankerEnabled(false);
      var refused = assertThrows(io.justsearch.app.api.settings.SettingsCommitOwner.Refused.class,
          () -> f.server.prepareQueryRoleSettings(candidate, f.configuration,
              Set.of("rerankerEnabled"), f.selection));
      assertEquals("COMPONENT_PREPARATION_REQUIRED", refused.response().errorCode().orElseThrow());
      f.assertA();
      assertFalse(f.queryA.isClosed());
    }
  }

  @Test
  void offRefusesFailedPairedCompositionAndKeepsTheServingView(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L);
        var composition = f.composition()) {
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
          any(), any())).thenThrow(new IllegalStateException("Paired citation files are unavailable"));
      UiSettings candidate = new UiSettings();
      candidate.setRerankerEnabled(false);
      var refused = assertThrows(io.justsearch.app.api.settings.SettingsCommitOwner.Refused.class,
          () -> f.server.prepareQueryRoleSettings(candidate, f.configuration,
              Set.of("rerankerEnabled"), f.selection));
      assertEquals("COMPONENT_PREPARATION_REQUIRED", refused.response().errorCode().orElseThrow());
      f.assertA();
      assertFalse(f.queryA.isClosed());
    }
  }

  @Test
  void offRefusesAnIssuedCallBeyondTheDrainBoundAndKeepsTheServingView(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
        var composition = f.composition(); var issued = f.server.captureServingView()) {
      UiSettings candidate = new UiSettings();
      candidate.setRerankerEnabled(false);
      var refused = assertThrows(io.justsearch.app.api.settings.SettingsCommitOwner.Refused.class,
          () -> f.server.prepareQueryRoleSettings(candidate, f.configuration,
              Set.of("rerankerEnabled"), f.selection));
      assertEquals("COMPONENT_PREPARATION_REQUIRED", refused.response().errorCode().orElseThrow());
      assertSame(f.producer, issued.services());
      f.assertA();
      assertFalse(f.queryA.isClosed());
    }
  }

  @Test
  void offPublishesWithoutRerankerAndKeepsIssuedViewAndExactFiles(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L);
        var composition = f.composition(); var issued = f.server.captureServingView()) {
      UiSettings settings = new UiSettings();
      settings.setRerankerEnabled(false);
      byte[] model = Files.readAllBytes(f.selection.reranker().model().path());
      byte[] tokenizer = Files.readAllBytes(f.selection.reranker().tokenizer().path());
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
          any(), any())).thenAnswer(call -> {
            QueryRoleSelection selected = call.getArgument(1);
            assertEquals(QueryRoleSelection.State.DISABLED, selected.reranker().state());
            return new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
                new InferenceSurface.ComponentObservation(Optional.of(
                    EncoderConfigurationProjection.from(f.configuration).queryDigest()), Set.of(),
                    Set.of(), Optional.of(selected)));
          });
      var prepared = assertDoesNotThrow(() -> f.server.prepareQueryRoleSettings(
          settings, f.configuration, Set.of("rerankerEnabled"), f.selection));
      assertEquals(QueryRoleSelection.State.DISABLED, prepared.selection().reranker().state());
      assertEquals(f.selection.reranker(), settings.getRerankerRestoreSelection());
      prepared.withOwnerLocks(() -> { prepared.validate(); prepared.install(); });
      prepared.notifyObservers();
      prepared.retire();
      assertSame(f.producer, issued.services());
      assertFalse(f.queryA.isClosed());
      try (var next = f.server.captureServingView()) {
        assertSame(f.index, next.encoderSet());
        assertNotSame(f.producer, next.services());
        verify((DefaultWorkerAppServices) next.services()).wireSearchReranker(null);
      }
      assertArrayEquals(model, Files.readAllBytes(f.selection.reranker().model().path()));
      assertArrayEquals(tokenizer, Files.readAllBytes(f.selection.reranker().tokenizer().path()));
    }
  }

  @Test
  void onRestoresExactCustomIdentityInsteadOfProbingAnotherVariant(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      UiSettings candidate = new UiSettings();
      candidate.setRerankerEnabled(false);
      var disabled = QueryRoleSelectionResolver.selectReranker(candidate, false, null,
          f.selection.reranker(), null, true, true);
      // Reopen the persisted candidate, as a new process would.
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      candidate = json.readValue(json.writeValueAsString(candidate), UiSettings.class);
      Files.writeString(f.selection.reranker().model().path().getParent().resolve("model.onnx"),
          "another available variant");
      candidate.setRerankerEnabled(true);
      var restored = QueryRoleSelectionResolver.selectReranker(candidate, false,
          f.selection.reranker().model().path().getParent(), disabled, null, false, false);
      assertEquals(f.selection.reranker(), restored);
    }
  }

  @Test
  void absentPreferencePreservesDisabledRoleAndOffGatesGenerationActivation(@TempDir Path dir)
      throws Exception {
    var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    var legacy = new ResolvedConfigBuilder().build();
    assertEquals(disabled, QueryRoleSelectionResolver.forBoot(disabled, legacy, null).selection());
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      var saved = new ResolvedConfigBuilder().putSettings("justsearch.rerank.enabled", "false")
          .putSettings("justsearch.rerank.model_path", dir.resolve("custom").toString()).build();
      var generation = GenerationModelSelection.accepted(java.util.Map.of("reranker",
          new io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact(
              f.selection.reranker().model().path().toString(),
              f.selection.reranker().model().sha256())), "splade", 768);
      var projected = EncoderConfigurationProjection.from(saved, generation);
      assertFalse(projected.reranker().enabled());
      assertEquals(dir.resolve("custom"), projected.reranker().modelPath());
      assertEquals(QueryRoleSelection.State.DISABLED,
          QueryRoleSelectionResolver.forBoot(f.selection, saved, generation)
              .selection().reranker().state());
    }
  }
}

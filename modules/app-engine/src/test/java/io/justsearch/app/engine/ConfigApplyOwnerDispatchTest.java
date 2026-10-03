/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentSnapshot;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.indexerworker.server.InferenceCompositionRoot;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Apply dispatch against the production dependencies and production owner-registration shape. */
final class ConfigApplyOwnerDispatchTest {
  @ParameterizedTest
  @ValueSource(strings = {
      "justsearch.rerank.model_path", "justsearch.citation.scorer.model_path"
  })
  void queryModelChangePreparesOnlyEncodersDespiteIndexIdentityOverlap(String key) {
    var before = ResolvedConfig.builder().putDefault(key, "models/query-a").build();
    var after = ResolvedConfig.builder().putDefault(key, "models/query-b").build();
    var affected = ConfigApplyScopes.classify(before, after).component();
    assertEquals(Map.of("encoders", Set.of(key)), affected);
    assertTrue(KnowledgeServer.componentDependencies().contains(key));
    assertTrue(InferenceCompositionRoot.componentDependencies().contains(key));
    var budget = new RetainedStateBudget();
    budget.declare(DefaultEngineComponentRegistry.ATTEMPTED_CONFIGURATIONS, 1, "test");
    var publication = new ReentrantReadWriteLock();
    try (var registry = new DefaultEngineComponentRegistry(budget, publication)) {
      var index = registry.register(spec("index", KnowledgeServer.componentDependencies()));
      var encoders = registry.register(spec("encoders", InferenceCompositionRoot.componentDependencies()));
      var generative = registry.register(HeadAssembly.generativeSpec());
      index.setAppliedVersion("serving-index-a");
      generative.setAppliedVersion("serving-generative-a");
      encoders.setAppliedVersion("serving-encoders-a");
      var indexA = index.snapshot();
      var generativeA = generative.snapshot();
      var prepared = mock(FixedSettingsComponentComposer.QueryRolePreparedOwner.class);
      var selection = new QueryRoleSelection(
          QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
      var observation = encoders.snapshot();
      when(prepared.selection()).thenReturn(selection);
      when(prepared.composition()).thenReturn(Optional.of(
          new ComposeEvidence(ComposeEvidence.Mode.BESIDE, "test", 100L, 1L)));
      when(prepared.observation()).thenReturn(new EngineComponentSnapshot.Component(
          observation.spec(), ComponentState.READY, null, observation.stateSince(),
          observation.stateSinceMonotonicNanos(), "verified-query-b", "verified-query-b",
          null, 0, null));
      var generativeOwner = mock(FixedSettingsComponentComposer.Owner.class);
      var composer = new FixedSettingsComponentComposer(registry);
      composer.register("encoders", (candidate, desired, keys) -> {
        assertEquals(Set.of(key), keys);
        return prepared;
      });
      composer.register("generative", generativeOwner);
      composer.seal();

      var transaction = composer.prepare(new UiSettings(), after, affected);
      assertEquals(selection, transaction.queryRoleSelection().orElseThrow());
      publication.writeLock().lock();
      try {
        transaction.validate();
        transaction.install();
      } finally {
        publication.writeLock().unlock();
      }
      transaction.notifyObservers();
      transaction.retire();

      verify(prepared).validate();
      verify(prepared).install();
      verify(prepared).retire();
      verify(generativeOwner, never()).prepare(org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      assertEquals("verified-query-b", encoders.snapshot().appliedVersion());
      assertEquals(indexA, index.snapshot());
      assertEquals(generativeA, generative.snapshot());
    }
  }

  private static ComponentSpec spec(String name, Set<String> keys) {
    return new ComponentSpec(name, name.equals("index"), keys,
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ofSeconds(60), 2);
  }
}

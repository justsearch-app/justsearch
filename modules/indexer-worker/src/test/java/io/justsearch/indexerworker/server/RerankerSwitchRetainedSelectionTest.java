/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Best match first switched on restores the exact retained reranker selection (R4), here a custom
 * model with a blank saved model path, while a different installed model sits where discovery
 * looks. The switch travels the real settings owner into the Worker's query-role preparation.
 * Only an actual model-path change selects from a directory again.
 */
final class RerankerSwitchRetainedSelectionTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void switchingOnSelectsTheRetainedCustomFilesNotTheDiscoveredModel(@TempDir Path dir)
      throws Exception {
    Path discovered = Files.createDirectories(dir.resolve("models/onnx/reranker"));
    Files.writeString(discovered.resolve("model.onnx"), "discovered-model");
    Files.writeString(discovered.resolve("model_fp16.onnx"), "discovered-model-fp16");
    Files.writeString(discovered.resolve("tokenizer.json"), "discovered-tokenizer");
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L);
        var composition = f.composition()) {
      UiSettings off = switchOff(f, composition);
      assertEquals("", off.getRerankerModelPath(), "a legacy install saves no model path");
      assertEquals(f.selection.reranker(), off.getRerankerRestoreSelection(),
          "Off retained the custom files it served");
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
          any(), any())).thenAnswer(ignored -> f.freshSurface(false));

      var chosen = switchOnThroughTheSettingsOwner(dir, f, off);

      assertEquals(f.selection.reranker(), chosen.reranker(),
          "On restores the retained custom model and tokenizer, not the discovered model");
    }
  }

  @Test
  void switchingOnRefusesWhenTheRetainedFilesChanged(@TempDir Path dir) throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      UiSettings candidate = new UiSettings();
      candidate.setRerankerEnabled(true);
      candidate.setRerankerRestoreSelection(f.selection.reranker());
      Files.writeString(f.selection.reranker().model().path(), "replaced-model");
      var failure = assertThrows(java.io.IOException.class,
          () -> QueryRoleSelectionResolver.selectReranker(candidate, false,
              f.selection.reranker().model().path().getParent(), QueryRoleSelection.Role.disabled(),
              null, false, false));
      assertTrue(failure.getMessage().contains("kept Best match first files changed"),
          failure.getMessage());
    }
  }

  /** Off through the Worker, as the settings owner prepares it; returns the saved candidate. */
  private static UiSettings switchOff(KnowledgeServerQuerySettingsOwnerTest.QueryFixture f,
      org.mockito.MockedStatic<InferenceCompositionRoot> composition) {
    UiSettings off = new UiSettings();
    off.setRerankerEnabled(false);
    composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
        any(), any())).thenAnswer(call -> {
          QueryRoleSelection selected = call.getArgument(1);
          return new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
              Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
              new InferenceSurface.ComponentObservation(Optional.of(
                  EncoderConfigurationProjection.from(f.configuration).queryDigest()), Set.of(),
                  Set.of(), Optional.of(selected)));
        });
    var prepared = f.server.prepareQueryRoleSettings(off, f.configuration,
        Set.of("rerankerEnabled"), f.selection);
    prepared.withOwnerLocks(() -> { prepared.validate(); prepared.install(); });
    prepared.notifyObservers();
    prepared.retire();
    // The Off view's services prepare the next successor, as the fixture's producer did.
    for (var services : List.of(f.degraded, f.candidate, f.restored)) {
      when(services.prepareQueryServingSuccessor(any(), any(), any()))
          .thenReturn(f.candidate, f.restored);
      when(services.prepareProducerTransferTo(any())).thenReturn(f.transfer);
    }
    return off;
  }

  private static QueryRoleSelection switchOnThroughTheSettingsOwner(Path dir,
      KnowledgeServerQuerySettingsOwnerTest.QueryFixture f, UiSettings off) throws Exception {
    var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        dir.resolve("settings.json"));
    var witness = new SettingsWitness(0, null);
    store.replacePrepared(store.prepare(off, witness, disabled));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(off));
    // What the Worker projects for the candidate: blank path, discovery under the data folder.
    ResolvedConfig desired = new ResolvedConfigBuilder()
        .putDefault("justsearch.data.dir", dir.toString())
        .putSettings("justsearch.rerank.enabled", "true").build();
    var chosen = new AtomicReference<QueryRoleSelection>();
    SettingsComponentComposer components = (candidate, ignored, affected) -> {
      var worker = f.server.prepareQueryRoleSettings(candidate, desired,
          affected.getOrDefault("encoders", Set.of()), disabled);
      chosen.set(worker.selection());
      worker.abort();
      var prepared = mock(SettingsComponentComposer.Prepared.class);
      when(prepared.queryRoleSelection()).thenReturn(Optional.of(worker.selection()));
      doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
          .when(prepared).withOwnerLocks(any());
      return prepared;
    };
    try (var operations = new SqliteOperationStore(dir.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(store, config,
          () -> { throw new AssertionError("Unexpected restart"); },
          candidate -> OperationResult.success("applied"), () -> false, components);
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      var service = new SettingsServiceImpl(store, runner);
      SettingsV2 switchOn = JSON.readValue("{\"rerankerEnabled\":true,"
          + "\"rerankerSwitchIntent\":true,\"witness\":" + JSON.writeValueAsString(witness)
          + ",\"operationKey\":\"" + OperationKeys.generate(Clock.systemUTC()) + "\"}",
          SettingsV2.class);
      service.applyPublic(switchOn, null,
          io.justsearch.app.services.intent.EngineProvenance.context(
              EngineContext.ClientKind.WEBVIEW, "reranker-switch-test", Optional.empty(),
              Optional.empty(), io.justsearch.agent.api.registry.TransportTag.BUTTON,
              EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND));
    }
    assertNotNull(chosen.get(), "the switch reached the Worker's preparation");
    assertEquals(Boolean.TRUE, store.load().getRerankerEnabled(), "the switch is saved on");
    assertEquals("", store.load().getRerankerModelPath(), "the saved path stays blank");
    return chosen.get();
  }
}

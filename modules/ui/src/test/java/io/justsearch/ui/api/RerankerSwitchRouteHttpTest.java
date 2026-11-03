/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Best match first switch through the settings HTTP route reaches the encoder owner as the
 * switch, never as a change of the reranker model path: On restores the retained selection, and
 * only an actual model-path change selects from a directory again (R4).
 */
final class RerankerSwitchRouteHttpTest {
  private static final String MODEL_PATH = "justsearch.rerank.model_path";

  @Test
  void onIsTheSwitchNotAModelPathChange(@TempDir Path dir) throws Exception {
    switchReachesTheEncoderOwner(true, dir);
  }

  @Test
  void offIsNotAModelPathChange(@TempDir Path dir) throws Exception {
    switchReachesTheEncoderOwner(false, dir);
  }

  private static void switchReachesTheEncoderOwner(boolean on, Path dir) throws Exception {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        dir.resolve("settings.json"));
    var initial = new UiSettings();
    initial.setRerankerEnabled(!on);
    initial.setRerankerModelPath(dir.resolve("reranker").toString());
    var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    var witness = new SettingsWitness(0, null);
    store.replacePrepared(store.prepare(initial, witness, disabled));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
    var affectedKeys = new AtomicReference<Map<String, Set<String>>>();
    SettingsComponentComposer components = (candidate, desired, affected) -> {
      affectedKeys.set(affected);
      var prepared = mock(SettingsComponentComposer.Prepared.class);
      when(prepared.queryRoleSelection()).thenReturn(Optional.of(disabled));
      doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
          .when(prepared).withOwnerLocks(any());
      return prepared;
    };
    try (var operations = new SqliteOperationStore(dir.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(store, config,
          () -> { throw new AssertionError("Unexpected restart"); },
          candidate -> io.justsearch.agent.api.registry.OperationResult.success("applied"),
          () -> false, components);
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      var controller = new SettingsController(store, dir, null, new SettingsServiceImpl(store, runner));
      var json = JsonMapper.builder().build();
      var ctx = mock(Context.class);
      when(ctx.body()).thenReturn("{\"rerankerEnabled\":" + on + ",\"rerankerSwitchIntent\":true,"
          + "\"witness\":" + json.writeValueAsString(witness) + ",\"operationKey\":\""
          + OperationKeys.generate(Clock.systemUTC()) + "\"}");
      when(ctx.path()).thenReturn("/api/settings/v2");
      when(ctx.method()).thenReturn(io.javalin.http.HandlerType.POST);
      when(ctx.json(any())).thenReturn(ctx);
      when(ctx.status(anyInt())).thenReturn(ctx);

      controller.handleUpdateSettingsV2(ctx);

      Set<String> encoders = affectedKeys.get().get("encoders");
      assertNotNull(encoders, "the switch is prepared by the encoder owner");
      assertTrue(encoders.contains("rerankerEnabled"));
      assertFalse(encoders.contains(MODEL_PATH),
          (on ? "On" : "Off") + " is the switch, not a model path change");
      assertEquals(on, store.load().getRerankerEnabled());
      assertEquals(dir.resolve("reranker").toString(), store.load().getRerankerModelPath(),
          "the switch keeps the saved model path");
    }
  }
}

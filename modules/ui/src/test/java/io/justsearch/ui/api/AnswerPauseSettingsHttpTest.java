/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.SettingsV2Projection;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

final class AnswerPauseSettingsHttpTest {
  @Test
  void witnessedPolicyPatchPersistsAndAStaleWindowCannotOverwriteIt(@TempDir Path dir)
      throws Exception {
    var json = JsonMapper.builder().build();
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        dir.resolve("settings.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    try (var operations = new SqliteOperationStore(dir.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(store, config,
          () -> { throw new AssertionError("pause policy must not restart the Engine"); },
          candidate -> io.justsearch.agent.api.registry.OperationResult.success("applied",
              java.util.Map.of("pauseAnswersWhileIndexing", json.valueToTree(
                  SettingsV2Projection.toSettingsV2(candidate, store.mode()))
                  .path("pauseAnswersWhileIndexing").asBoolean())));
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      var controller = new SettingsController(store, dir, null, new SettingsServiceImpl(store, runner));
      String witness = json.writeValueAsString(store.inspect().witness());
      Context first = context("{\"pauseAnswersWhileIndexing\":true,\"witness\":" + witness
          + ",\"operationKey\":\"" + OperationKeys.generate(Clock.systemUTC()) + "\"}");
      controller.handleUpdateSettingsV2(first);
      var response = ArgumentCaptor.forClass(Object.class);
      verify(first).json(response.capture());
      var body = json.valueToTree(response.getValue());
      assertEquals("COMPLETE", body.path("state").asString(), body.toString());
      assertTrue(body.path("pauseAnswersWhileIndexing").asBoolean());
      assertEquals(1L, body.path("witness").path("acceptedRevision").asLong());
      Context stale = context("{\"pauseAnswersWhileIndexing\":false,\"witness\":" + witness
          + ",\"operationKey\":\"" + OperationKeys.generate(Clock.systemUTC()) + "\"}");
      controller.handleUpdateSettingsV2(stale);
      verify(stale).status(409);
      var reopened = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, store.settingsPath());
      assertTrue(json.valueToTree(reopened.load()).path("pauseAnswersWhileIndexing").asBoolean());
      assertNull(reopened.load().getChatEnabled());
    }
  }

  private static Context context(String body) {
    var ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    when(ctx.path()).thenReturn("/api/settings/v2");
    when(ctx.method()).thenReturn(io.javalin.http.HandlerType.POST);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    return ctx;
  }
}

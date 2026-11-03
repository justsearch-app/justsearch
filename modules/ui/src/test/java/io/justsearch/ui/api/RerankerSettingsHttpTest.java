/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.javalin.http.Context;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.SettingsV2Projection;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

final class RerankerSettingsHttpTest {
  @Test
  void witnessedHttpSwitchCommitsLiveAndAStaleWindowCannotReplaceIt(@TempDir Path dir)
      throws Exception {
    var json = JsonMapper.builder().build();
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        dir.resolve("settings.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    SettingsComponentComposer components = (candidate, desired, affected) -> {
      assertEquals(Set.of("encoders"), affected.keySet());
      return new SettingsComponentComposer.Prepared() {
        @Override public Optional<QueryRoleSelection> queryRoleSelection() {
          return Optional.of(new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
              QueryRoleSelection.Role.disabled()));
        }
        @Override public void includeObservation(io.justsearch.core.component.EngineComponentSnapshot.Component row) {}
        @Override public void withOwnerLocks(Runnable publish) { publish.run(); }
        @Override public void validate() {}
        @Override public void install() {}
        @Override public void notifyObservers() {}
        @Override public void retire() {}
        @Override public void abort() {}
      };
    };
    try (var operations = new SqliteOperationStore(dir.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(store, config,
          () -> { throw new AssertionError("Switch requested an Engine restart"); }, candidate -> {
            var projection = SettingsV2Projection.toSettingsV2(candidate, store.mode());
            return io.justsearch.agent.api.registry.OperationResult.success("applied",
                java.util.Map.of("rerankerEnabled", projection.rerankerEnabled()));
          }, () -> false, components);
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      var controller = new SettingsController(store, dir, null, new SettingsServiceImpl(store, runner));
      String initial = json.writeValueAsString(store.inspect().witness());
      Context first = context("{\"rerankerEnabled\":false,\"witness\":" + initial
          + ",\"operationKey\":\"" + OperationKeys.generate(Clock.systemUTC()) + "\"}");
      controller.handleUpdateSettingsV2(first);
      var response = ArgumentCaptor.forClass(Object.class);
      verify(first).json(response.capture());
      var complete = assertInstanceOf(SettingsV2.class, response.getValue());
      assertEquals("COMPLETE", complete.state());
      assertEquals(Boolean.FALSE, complete.rerankerEnabled());
      assertEquals(1L, complete.witness().acceptedRevision());
      assertFalse(config.get().ai().reranker().enabled());
      Context stale = context("{\"rerankerEnabled\":true,\"witness\":" + initial
          + ",\"operationKey\":\"" + OperationKeys.generate(Clock.systemUTC()) + "\"}");
      controller.handleUpdateSettingsV2(stale);
      verify(stale).status(409);
      assertEquals(Boolean.FALSE, store.load().getRerankerEnabled());
      assertEquals(complete.witness(), store.inspect().witness());
      for (String property : java.util.List.of("justsearch.rerank.model_path",
          "justsearch.rerank.enabled")) {
        String previous = System.getProperty(property);
        String override = property.endsWith("model_path") ? dir.resolve("operator").toString() : "true";
        try {
          System.setProperty(property, override);
          Context controlled = context("{\"rerankerEnabled\":true,\"witness\":"
              + json.writeValueAsString(complete.witness()) + ",\"operationKey\":\""
              + OperationKeys.generate(Clock.systemUTC()) + "\"}");
          controller.handleUpdateSettingsV2(controlled);
          verify(controlled).status(409);
          var refusal = ArgumentCaptor.forClass(Object.class);
          verify(controlled).json(refusal.capture());
          assertEquals("QUERY_ROLE_PATH_OVERRIDDEN",
              ((java.util.Map<?, ?>) refusal.getValue()).get("errorCode"));
          assertEquals(override, System.getProperty(property));
          assertEquals(Boolean.FALSE, store.load().getRerankerEnabled());
          assertEquals(complete.witness(), store.inspect().witness());
        } finally {
          if (previous == null) System.clearProperty(property);
          else System.setProperty(property, previous);
        }
      }
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

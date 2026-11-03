/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsCommitOwner;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

final class RerankerRepeatedEnableHttpTest {
  @ParameterizedTest(name = "repeatedEnablePreparesDisabledRoleOrRefusesWithoutCommitting({0})")
  @ValueSource(booleans = {false, true})
  void repeatedEnablePreparesDisabledRoleOrRefusesWithoutCommitting(boolean filesReady,
      @TempDir Path dir) throws Exception {
    exerciseSave(filesReady, true, true, dir);
  }

  @org.junit.jupiter.api.Test
  void themeSaveEchoingTrueDoesNotPrepareUnavailableReranker(@TempDir Path dir) throws Exception {
    exerciseSave(false, false, true, dir);
  }

  @ParameterizedTest(name = "plainEnableChangeStillPreparesOrRefuses({0})")
  @ValueSource(booleans = {false, true})
  void plainEnableChangeStillPreparesOrRefuses(boolean filesReady, @TempDir Path dir)
      throws Exception {
    exerciseSave(filesReady, false, false, dir);
  }

  private static void exerciseSave(boolean filesReady, boolean switchIntent, boolean initialEnabled,
      Path dir) throws Exception {
    boolean prepare = switchIntent || !initialEnabled;
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE,
        dir.resolve("settings.json"));
    var initial = new UiSettings();
    initial.setRerankerEnabled(initialEnabled);
    var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(),
        QueryRoleSelection.Role.disabled());
    var witness = new SettingsWitness(0, null);
    store.replacePrepared(store.prepare(initial, witness, disabled));
    byte[] before = Files.readAllBytes(dir.resolve("settings.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(initial));
    var preparations = new AtomicInteger();
    var selected = new QueryRoleSelection(QueryRoleSelection.Role.selected("model.onnx",
        new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
            dir.resolve("model.onnx"), "a".repeat(64), 1),
        new io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile(
            dir.resolve("tokenizer.json"), "b".repeat(64), 1),
        io.justsearch.configuration.model.ModelPrecision.FP32,
        io.justsearch.configuration.model.ExecutionProvider.CPU), disabled.citation());
    SettingsComponentComposer components = (candidate, desired, affected) -> {
      preparations.incrementAndGet();
      assertEquals(Set.of("encoders"), affected.keySet());
      assertTrue(affected.get("encoders").contains("rerankerEnabled"));
      if (!filesReady) throw new SettingsCommitOwner.Refused(OperationResult.failure(
          "Best match first files are unavailable", "COMPONENT_PREPARATION_REQUIRED", Map.of(), false));
      var prepared = mock(SettingsComponentComposer.Prepared.class);
      when(prepared.queryRoleSelection()).thenReturn(Optional.of(selected));
      doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
          .when(prepared).withOwnerLocks(any());
      return prepared;
    };
    try (var operations = new SqliteOperationStore(dir.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(store, config,
          () -> { throw new AssertionError("Unexpected restart"); },
          candidate -> OperationResult.success("applied"), () -> false, components);
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
              io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
      var controller = new SettingsController(store, dir, null, new SettingsServiceImpl(store, runner));
      var json = JsonMapper.builder().build();
      var ctx = mock(Context.class);
      when(ctx.body()).thenReturn("{\"ui\":{\"theme\":\"dark\"},\"rerankerEnabled\":true,"
          + (switchIntent ? "\"rerankerSwitchIntent\":true," : "") + "\"witness\":"
          + json.writeValueAsString(witness) + ",\"operationKey\":\""
          + OperationKeys.generate(Clock.systemUTC()) + "\"}");
      when(ctx.path()).thenReturn("/api/settings/v2");
      when(ctx.method()).thenReturn(io.javalin.http.HandlerType.POST);
      when(ctx.json(any())).thenReturn(ctx);
      when(ctx.status(anyInt())).thenReturn(ctx);
      controller.handleUpdateSettingsV2(ctx);
      assertEquals(prepare ? 1 : 0, preparations.get(), prepare
          ? "An enable must go through reranker preparation"
          : "A theme save echoing the saved true must not prepare the reranker");
      if (filesReady || !prepare) {
        assertEquals(prepare ? selected : disabled, store.inspect().queryRoles(),
            "A theme echo leaves the role alone; an enable must prepare a working role");
        var response = ArgumentCaptor.forClass(Object.class);
        verify(ctx).json(response.capture());
        assertEquals("COMPLETE", assertInstanceOf(SettingsV2.class, response.getValue()).state());
        assertEquals(1, store.inspect().witness().acceptedRevision());
        assertEquals("dark", store.load().getTheme());
        assertEquals(prepare ? 1 : 0, store.load().getRerankerPreferenceRevision());
      } else {
        assertEquals(witness, store.inspect().witness(),
            "A repeated enable without a working reranker must not commit");
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("settings.json")));
        verify(ctx).status(503);
        var refusal = ArgumentCaptor.forClass(Object.class);
        verify(ctx).json(refusal.capture());
        assertEquals("COMPONENT_PREPARATION_REQUIRED",
            ((Map<?, ?>) refusal.getValue()).get("errorCode"));
        assertEquals(disabled, store.inspect().queryRoles());
      }
      assertEquals(filesReady || !prepare || initialEnabled, store.load().getRerankerEnabled());
    }
  }
}

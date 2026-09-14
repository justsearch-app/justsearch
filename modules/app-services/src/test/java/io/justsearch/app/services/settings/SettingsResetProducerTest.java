/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.registry.executor.SettingsResetTestSupport;
import io.justsearch.app.services.registry.operations.handlers.ResetSettingsHandler;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsResetProducerTest {
  @TempDir Path directory;

  @Test
  void handlerUsesAcceptedFrozenResetAndRetryCannotRepeatIt() throws Exception {
    var settings = settings();
    var initial = new UiSettings(); initial.setTheme("dark"); initial.setServerExecutablePath("admin.exe");
    settings.replacePrepared(settings.prepare(initial, new SettingsWitness(0, null)));
    try (var operations = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(initial)),
          () -> fail("Normal reset must not restart"), candidate -> OperationResult.success("Settings committed",
              Map.of("ui", SettingsV2Projection.toSettingsV2(candidate, settings.mode()).ui())));
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      var handler = new ResetSettingsHandler(() -> new SettingsServiceImpl(settings, runner));
      var prepared = handler.prepare("{}", null, null);
      handler.validatePreparation(prepared);
      assertTrue(handler.approvalPreview(prepared).summary().contains("preserving administrator"));
      var accepted = SettingsResetTestSupport.accept(runner, prepared);
      assertEquals("dark", settings.inspect().settings().getTheme(), "Acceptance alone cannot write");
      var result = runner.start(accepted, record -> handler.executePrepared(prepared, null, null, record));
      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(1L, result.response().structuredData().get("acceptedRevision"));
      assertEquals("system", settings.inspect().settings().getTheme());
      assertEquals("admin.exe", settings.inspect().settings().getServerExecutablePath());
      assertEquals(OperationState.COMPLETE, runner.start(accepted, record -> {
        fail("Recorded outcome must not execute again"); return null;
      }).record().state());
      assertEquals(1, settings.inspect().witness().acceptedRevision());
      assertThrows(IllegalStateException.class, () -> handler.execute("{}", null));
    }
  }

  @Test
  void dispatcherReusesOutcomeAndRefusesAStaleFrozenPreview() throws Exception {
    var settings = settings(); settings.replacePrepared(settings.prepare(new UiSettings(), new SettingsWitness(0, null)));
    try (var operations = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())),
          () -> fail("Normal reset must not restart"), candidate -> OperationResult.success("committed"));
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      var service = spy(new SettingsServiceImpl(settings, runner));
      var handlers = new io.justsearch.agent.api.registry.HandlerRegistry();
      var catalog = new io.justsearch.app.services.registry.operations.CoreOperationCatalog();
      var operation = catalog.definitions().stream()
          .filter(op -> op.id().value().equals(SettingsResetPreparation.OPERATION_ID)).findFirst().orElseThrow();
      assertEquals(OperationKind.SETTINGS_APPLY, operation.policy().recordKind());
      handlers.register(operation.id(), new ResetSettingsHandler(() -> service));
      var admission = mock(io.justsearch.app.api.EngineAdmissionService.class);
      when(admission.attach(any())).thenAnswer(call -> {
        var work = mock(io.justsearch.app.api.EngineWorkHandle.class);
        when(work.context()).thenReturn(call.getArgument(0)); when(work.retain()).thenReturn(work); return work;
      });
      var dispatcher = new io.justsearch.app.services.registry.executor.OperationExecutorImpl(runner, admission, handlers);
      var context = io.justsearch.app.services.TestEngineContexts.internal();
      var provenance = io.justsearch.app.services.intent.EngineProvenance.invocation(context,
          io.justsearch.agent.api.registry.ExecutorTag.UI, java.time.Instant.now(), java.util.Optional.empty());
      String firstKey = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
      assertTrue(dispatcher.dispatch(operation, "{}", provenance, java.util.Optional.empty(), context, firstKey).success());
      String staleKey = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
      var stale = (io.justsearch.agent.api.registry.OperationDispatchPlan.Ready)
          dispatcher.prepare(operation, "{}", provenance, context, staleKey, true);
      assertTrue(stale.approvalPreview().isPresent());
      String laterKey = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
      assertTrue(dispatcher.dispatch(operation, "{}", provenance, java.util.Optional.empty(), context, laterKey).success());
      var later = settings.inspect().witness(); assertEquals(2, later.acceptedRevision());
      clearInvocations(service);
      assertTrue(dispatcher.dispatch(operation, "{}", provenance, java.util.Optional.empty(), context, firstKey).success());
      verifyNoInteractions(service);
      var refused = dispatcher.dispatch(operation, "{}", provenance, java.util.Optional.empty(), context,
          staleKey, stale.preparationNonce());
      assertEquals("VERSION_CONFLICT", refused.errorCode().orElseThrow());
      assertEquals(later, settings.inspect().witness());
    }
  }

  @Test
  void recoveryPreparationDoesNotClearEvidenceAndCommittedResetRequestsRestart() throws Exception {
    var settings = settings(); Files.writeString(settings.settingsPath(), "{broken"); settings.load();
    var restarts = new AtomicInteger();
    try (var operations = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(settings, new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings())),
          restarts::incrementAndGet, candidate -> OperationResult.success("Settings committed"));
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      var handler = new ResetSettingsHandler(() -> new SettingsServiceImpl(settings, runner));
      var prepared = handler.prepare("{}", null, null);
      assertTrue(handler.approvalPreview(prepared).summary().contains("restart the Engine"));
      assertFalse(Files.exists(settings.settingsPath())); assertTrue(settings.lastRecovery().isPresent());
      assertEquals(0, restarts.get());
      var accepted = SettingsResetTestSupport.accept(runner, prepared);
      assertEquals(OperationState.COMPLETE,
          runner.start(accepted, record -> handler.executePrepared(prepared, null, null, record)).record().state());
      assertEquals(1, restarts.get()); assertTrue(settings.lastRecovery().isEmpty());
    }
  }

  @Test
  void readOnlyAndUnreadableLiveHistoryRefuseWithoutCallingRunnerOrQuarantining() throws Exception {
    var runner = mock(OperationAttemptRunner.class);
    var readOnly = new SettingsServiceImpl(new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY,
        directory.resolve("read-only.json")), runner);
    assertEquals("SETTINGS_READ_ONLY", assertThrows(OperationPreparationRefused.class,
        () -> readOnly.prepareReset("{}")).refusal().errorCode().orElseThrow());
    var settings = settings(); Files.writeString(settings.settingsPath(), "{private-broken");
    var service = new SettingsServiceImpl(settings, runner);
    var refused = assertThrows(OperationPreparationRefused.class, () -> service.prepareReset("{}"));
    assertEquals("SETTINGS_RECOVERY_REQUIRED", refused.refusal().errorCode().orElseThrow());
    assertFalse(refused.getMessage().contains("private-broken"));
    assertEquals("{private-broken", Files.readString(settings.settingsPath()));
    assertTrue(settings.lastRecovery().isEmpty()); verifyNoInteractions(runner);
  }

  private UiSettingsStore settings() {
    return new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
  }
}

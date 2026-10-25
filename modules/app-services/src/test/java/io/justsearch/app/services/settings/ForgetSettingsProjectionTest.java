/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

/** Real file/config/SQLite protocol. JSON/reflection keeps baseline failures behavioral. */
final class ForgetSettingsProjectionTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK = Clock.systemUTC();
  private static final String OWN = "2cbfdf15-0da3-41b0-8eed-448761c84240";
  private static final String FOREIGN = "fdad500d-3dd2-4b88-8a37-98ad2c272780";
  @TempDir Path directory;

  private OperationKind kind() {
    return assertDoesNotThrow(() -> OperationKind.fromWire("forget"),
        "the accepted FORGET kind must exist before any protocol probe");
  }

  private Map<String, Object> literal(String token, Path path) {
    return Map.of("literal", Map.of("version", 1, "id", token, "path", path.toString(), "scope", "FOLDER"));
  }

  private EngineContext context() {
    return EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "forget-settings-test",
        Optional.empty(), Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
  }

  private OperationAttemptRunner.PreparedAttempt accepted(OperationAttemptRunnerImpl runner, OperationKind kind) {
    String key = OperationKeys.generate(CLOCK);
    String arguments = JSON.writeValueAsString(Map.of("path", target().toString(), "scope", "FOLDER"));
    var descriptor = OperationDescriptor.invocation(kind, "core.forget-indexed-path", arguments, false);
    var context = context();
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, CLOCK.instant(), Optional.empty());
    var request = new OperationAttemptRunner.Request(key, descriptor, context, provenance);
    var nonce = UUID.randomUUID();
    String plan = JSON.writeValueAsString(Map.of("version", 1, "path", target().toString(), "scope", "FOLDER",
        "keepOut", literal(OWN, target()), "watchedRoots", List.of(), "source", "FILE_SYSTEM"));
    var prepared = new OperationPreparation(arguments, "forget-plan.v1", plan);
    String envelope = JSON.writeValueAsString(Map.of("version", 1, "key", key, "nonce", nonce,
        "descriptor", descriptor, "preparation", prepared, "context", context, "executor", ExecutorTag.UI,
        "occurredAt", provenance.occurredAt()));
    var saved = runner.withPreparation(request, scope -> runner.savePreparation(scope.request(),
        new OperationStore.Preparation(nonce, new OperationPreparedPayload(false, envelope))).orElseThrow());
    return runner.acceptPrepared(request, saved.nonce());
  }

  private Path target() { return directory.resolve("literal*[x]\\name "); }

  private Object call(Object owner, Class<?> port, String name, Class<?>[] signature, Object... arguments) {
    try { return port.getMethod(name, signature).invoke(owner, arguments); }
    catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof Error fatal) throw fatal;
      if (failure.getCause() instanceof RuntimeException failed) throw failed;
      throw new AssertionError(failure.getCause());
    } catch (ReflectiveOperationException invalid) { throw new AssertionError(name, invalid); }
  }

  private Object callRunner(OperationAttemptRunnerImpl runner, String name, Class<?>[] signature, Object... arguments) {
    return call(runner, OperationAttemptRunner.class, name, signature, arguments);
  }

  private Object progress(SqliteOperationStore store, String key, String next) {
    String cursor = store.find(key).orElseThrow().checkpointCursor();
    var node = cursor == null ? null : JSON.readTree(cursor);
    if (node == null || node.isNull()) node = JSON.readTree("""
        {"version":1,"phase":"ADMITTED","admitted":true,"installedToken":null,"settingsBase":null,
         "priorFileWitness":null,"installedFileWitness":null,"withdrawalRequested":false,"reason":"NONE"}
        """);
    if (next != null) ((tools.jackson.databind.node.ObjectNode) node).put("phase", next);
    final var value = node;
    return assertDoesNotThrow(() -> JSON.treeToValue(value, Class.forName("io.justsearch.app.api.operations.ForgetProgress")));
  }

  private void checkpoint(OperationRecordHandle handle, Object progress) {
    String cursor = (String) call(progress, progress.getClass(), "cursor", new Class<?>[0]);
    handle.checkpoint(cursor, 0, 0);
  }

  private void park(OperationAttemptRunnerImpl runner, SqliteOperationStore store, OperationRecordHandle handle) {
    var progress = progress(store, handle.key(), null);
    callRunner(runner, "parkForget", new Class<?>[] {OperationRecordHandle.class, progress.getClass()}, handle, progress);
  }

  private void project(OperationAttemptRunnerImpl runner, SqliteOperationStore store,
      ConfigStore config, OperationRecordHandle handle) {
    if (store.find(handle.key()).orElseThrow().checkpointCursor() == null) checkpoint(handle, progress(store, handle.key(), "ADMITTED"));
    var prepared = callRunner(runner, "prepareForgetProjection", new Class<?>[] {OperationRecordHandle.class}, handle);
    Class<?> port = assertDoesNotThrow(() -> Class.forName(SettingsCommitOwner.class.getName() + "$PreparedForgetProjection"));
    try {
      config.publicationLock().writeLock().lock();
      try {
        // Invoke explicitly here: injected process-death Error must escape to the real runner.
        try { port.getMethod("publish").invoke(prepared); }
        catch (java.lang.reflect.InvocationTargetException failure) {
          if (failure.getCause() instanceof Error fatal) throw fatal;
          if (failure.getCause() instanceof RuntimeException failed) throw failed;
          throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); }
      } finally { config.publicationLock().writeLock().unlock(); }
    } finally { call(prepared, port, "afterOwnersReleased", new Class<?>[0]); }
  }

  private UiSettings initial(UiSettingsStore settings) {
    var candidate = JSON.readValue(JSON.writeValueAsString(Map.of("theme", "light", "excludePatterns",
        List.of("*.cache", literal(FOREIGN, directory.resolve("foreign"))))), UiSettings.class);
    var roles = new QueryRoleSelection(QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
    assertDoesNotThrow(() -> settings.replacePrepared(settings.prepare(candidate, new SettingsWitness(0, null), roles)));
    return candidate;
  }

  @Test
  void RF55ProjectionIsNonterminalAndWD55WithdrawalRemovesOnlyOwnedEntryAfterFreshMerge() throws Exception {
    OperationKind kind = kind();
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(initial(settings)));
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var owner = new SettingsCommitCoordinator(settings, config, () -> {}, candidate -> OperationResult.success("prepared"));
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(kind, OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      var notifications = new AtomicInteger();
      config.addListener(event -> {
        assertFalse(config.publicationLock().isWriteLockedByCurrentThread());
        notifications.incrementAndGet();
      });
      var attempt = accepted(runner, kind);
      runner.start(attempt, handle -> {
        project(runner, store, config, handle);
        assertNull(store.find(handle.key()).orElseThrow().expectedSettingsRevision());
        assertEquals("POLICY_INSTALLED", JSON.readTree(store.find(handle.key()).orElseThrow().checkpointCursor()).path("phase").asString());
        park(runner, store, handle);
        return OperationExecution.finished(OperationResult.success("unfinished"));
      });
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertFalse(attempt.completion().toCompletableFuture().isDone());
      assertEquals(1, notifications.get());
      assertEquals(3, settings.load().getExcludePatterns().size());
      // An unrelated successful settings transaction happens before withdrawal. No stale whole
      // document from the first activation may replace its theme or any other policy entry.
      var ordinary = runner.accept(new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          new OperationDescriptor(OperationKind.SETTINGS_APPLY, "core.settings", "{}"), context(), null));
      runner.start(ordinary, handle -> {
        var changed = settings.load(); changed.setTheme("dark");
        return OperationExecution.finished(runner.applySettings(handle, settings.inspect().witness(), changed));
      });
      String key = attempt.accepted().key();
      callRunner(runner, "requestForgetWithdrawal", new Class<?>[] {String.class}, key);
      java.util.function.Function<OperationRecordHandle, OperationExecution> withdraw = handle -> {
        project(runner, store, config, handle);
        var completion = callRunner(runner, "finishForget", new Class<?>[] {OperationRecordHandle.class,
            Long.class, OperationState.class, OperationReceipt.class}, handle, null, OperationState.CANCELLED,
            new OperationReceipt("forget_withdrawn", null));
        call(completion, OperationStore.DeferredForgetCompletion.class, "publish", new Class<?>[0]);
        return OperationExecution.finished(OperationResult.success("withdrawn"));
      };
      callRunner(runner, "resumeForget", new Class<?>[] {String.class, java.util.function.Function.class}, key, withdraw);
      assertEquals(OperationState.CANCELLED, store.find(key).orElseThrow().state());
      assertEquals("dark", settings.load().getTheme());
      assertEquals(2, settings.load().getExcludePatterns().size());
      String saved = JSON.writeValueAsString(settings.load().getExcludePatterns());
      assertTrue(saved.contains(FOREIGN)); assertFalse(saved.contains(OWN)); assertTrue(saved.contains("*.cache"));
      assertEquals(new QueryRoleSelection(QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled()), settings.inspect().queryRoles());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void DF55DeathAfterExactFileReplaceRecoversOnlySettingsStepOrRetainsUnknown(
      boolean withdrawing, boolean changedBytes) throws Exception {
    OperationKind kind = kind();
    Path settingsPath = directory.resolve("settings.json");
    Path database = directory.resolve("operations.db");
    String key;
    var fault = new AtomicBoolean(!withdrawing);
    try (var store = new SqliteOperationStore(database)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(initial(settings)));
      var owner = new SettingsCommitCoordinator(settings, config, () -> {},
          ConfigStoreRebuilder::prepare, candidate -> OperationResult.success("prepared"), prepared -> {
            settings.replacePrepared(prepared);
            if (fault.get()) throw new SimulatedDeath();
          });
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(kind, OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      var attempt = accepted(runner, kind);
      key = attempt.accepted().key();
      java.util.function.Function<OperationRecordHandle, OperationExecution> segment = handle -> {
        project(runner, store, config, handle); park(runner, store, handle);
        return OperationExecution.finished(OperationResult.success("unfinished"));
      };
      if (withdrawing) {
        runner.start(attempt, segment);
        callRunner(runner, "requestForgetWithdrawal", new Class<?>[] {String.class}, key);
        fault.set(true);
        assertThrows(SimulatedDeath.class, () -> callRunner(runner, "resumeForget",
            new Class<?>[] {String.class, java.util.function.Function.class}, key, segment));
      } else assertThrows(SimulatedDeath.class, () -> runner.start(attempt, segment));
      assertEquals(OperationState.RUNNING, store.find(key).orElseThrow().state());
      assertNotNull(store.find(key).orElseThrow().expectedSettingsRevision());
    }
    if (changedBytes) Files.writeString(settingsPath, Files.readString(settingsPath) + "\n");
    try (var store = new SqliteOperationStore(database)) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()));
      var owner = new SettingsCommitCoordinator(settings, config, () -> {}, candidate -> OperationResult.success("prepared"));
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(kind, OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      assertEquals(!changedBytes, runner.reconcileSettingsAfterComposition());
      var recovered = store.find(key).orElseThrow();
      assertEquals(OperationState.RUNNING, recovered.state(), "settings recovery cannot claim whole forget completion");
      if (changedBytes) assertNotNull(recovered.expectedSettingsRevision());
      else {
        assertNull(recovered.expectedSettingsRevision());
        assertEquals(withdrawing ? "WITHDRAWN" : "POLICY_INSTALLED",
            JSON.readTree(recovered.checkpointCursor()).path("phase").asString());
      }
      assertTrue(JSON.writeValueAsString(settings.load().getExcludePatterns()).contains(FOREIGN));
    }
  }

  private static final class SimulatedDeath extends Error {
    private static final long serialVersionUID = 1L;
    SimulatedDeath() { super("private fixture: process death after durable replace"); }
  }
}

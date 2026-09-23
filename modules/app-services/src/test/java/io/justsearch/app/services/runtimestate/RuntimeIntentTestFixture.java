/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatchPlan;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Real settings/operations composition for runtime-intent tests. */
public final class RuntimeIntentTestFixture implements AutoCloseable {
  private final UiSettingsStore settings;
  private final SqliteOperationStore operations;
  private final OperationAttemptRunner runner;
  private final RuntimeSpecStore spec;

  public RuntimeIntentTestFixture(Path directory) throws Exception {
    this(directory, null);
  }

  /** Seeds the file before composing its sole runtime writer. */
  public RuntimeIntentTestFixture(Path directory, Boolean initialChatEnabled) throws Exception {
    Files.createDirectories(directory);
    UiSettingsStore created = new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    UiSettings initial = new UiSettings();
    if (initialChatEnabled != null) {
      initial.setChatEnabled(initialChatEnabled);
      created.replacePrepared(created.prepare(initial, new SettingsWitness(0, null)));
    }
    this.settings = created;
    operations = new SqliteOperationStore(directory.resolve("operations.db"));
    var owner = new SettingsCommitCoordinator(settings,
        new ConfigStore(ConfigStoreRebuilder.prepare(initial)),
        () -> { throw new AssertionError("Unexpected settings restart"); },
        candidate -> OperationResult.success("Settings committed"),
        () -> false, inMemoryComponents());
    runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
        Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
    spec = new RuntimeSpecStore(settings, runner);
  }

  /** Composes the accepted writer around an already-seeded store and its live config. */
  public RuntimeIntentTestFixture(Path directory, UiSettingsStore settings, ConfigStore config)
      throws Exception {
    Files.createDirectories(directory);
    this.settings = java.util.Objects.requireNonNull(settings, "settings");
    operations = new SqliteOperationStore(directory.resolve("operations.db"));
    var owner = new SettingsCommitCoordinator(settings,
        java.util.Objects.requireNonNull(config, "config"),
        () -> { throw new AssertionError("Unexpected settings restart"); },
        candidate -> OperationResult.success("Settings committed"),
        () -> false, inMemoryComponents());
    runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
        Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
    spec = new RuntimeSpecStore(settings, runner);
  }

  public UiSettingsStore settings() {
    return settings;
  }

  public OperationAttemptRunner runner() {
    return runner;
  }

  public RuntimeSpecStore spec() {
    return spec;
  }

  /**
   * Runtime-intent tests do not compose real inference or registry owners.  They still opt into
   * the coordinator's prepared-component protocol explicitly so a test fixture cannot become an
   * accidental production fallback.
   */
  private static SettingsComponentComposer inMemoryComponents() {
    return (candidate, desired, affected) -> new SettingsComponentComposer.Prepared() {
      @Override public void validate() { }

      @Override public void install() { }

      @Override public void notifyObservers() { }

      @Override public void retire() { }

      @Override public void abort() { }
    };
  }

  /** Real prepared dispatcher around one handler under test. */
  public DispatchHarness dispatcher(String operationId, OperationHandler handler) {
    Operation operation = new CoreOperationCatalog().definitions().stream()
        .filter(candidate -> candidate.id().value().equals(operationId)).findFirst().orElseThrow();
    var handlers = new HandlerRegistry();
    handlers.register(operation.id(), handler);
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    when(admission.attach(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      EngineWorkHandle work = mock(EngineWorkHandle.class);
      when(work.context()).thenReturn(call.getArgument(0));
      when(work.retain()).thenReturn(work);
      return work;
    });
    var context = TestEngineContexts.internal();
    var provenance = EngineProvenance.invocation(context,
        io.justsearch.agent.api.registry.ExecutorTag.UI, Instant.now(), Optional.empty());
    return new DispatchHarness(new OperationExecutorImpl(runner, admission, handlers),
        operation, context, provenance);
  }

  public record DispatchHarness(OperationExecutorImpl dispatcher, Operation operation,
      io.justsearch.core.context.EngineContext context, InvocationProvenance provenance) {
    public OperationResult dispatch(String argumentsJson, String key) {
      return dispatcher.dispatch(operation, argumentsJson, provenance, Optional.empty(), context, key);
    }

    public OperationResult dispatch(String argumentsJson, String key, UUID nonce) {
      return dispatcher.dispatch(operation, argumentsJson, provenance, Optional.empty(), context, key, nonce);
    }

    public OperationDispatchPlan prepare(String argumentsJson, String key) {
      return dispatcher.prepare(operation, argumentsJson, provenance, context, key, true);
    }
  }

  @Override
  public void close() {
    try { operations.close(); }
    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }
}

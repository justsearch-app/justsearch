/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationHistoryMode;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import io.justsearch.core.context.EngineContext;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Runtime intent is a projection of settings; the accepted settings owner alone commits it. */
public final class RuntimeSpecStore {
  private final UiSettingsStore settingsStore;
  private final OperationAttemptRunner attempts;

  /** Read-only composition; a missing runner never creates an unrecorded writer. */
  public RuntimeSpecStore(UiSettingsStore settingsStore) {
    this(settingsStore, null);
  }

  public RuntimeSpecStore(UiSettingsStore settingsStore, OperationAttemptRunner attempts) {
    this.settingsStore = settingsStore;
    this.attempts = attempts;
  }

  public RuntimeSpec load() {
    return RuntimeSpec.fromSettings(settingsStore == null ? null : settingsStore.load());
  }

  /** Freeze the witness before dispatch acceptance without mutating settings. */
  public OperationPreparation prepareIntent(String argumentsJson) {
    return RuntimeIntentPreparation.prepare(argumentsJson, capture().witness());
  }

  /** The existing dispatcher-issued attempt owns this synchronous settings effect. */
  public OperationResult applyIntent(OperationPreparation prepared, boolean enabled, OperationRecordHandle record) {
    var expected = RuntimeIntentPreparation.validate(prepared);
    final UiSettingsStore.Snapshot snapshot;
    try { snapshot = capture(); }
    catch (OperationPreparationRefused refusal) { return refusal.refusal(); }
    if (!expected.equals(snapshot.witness())) {
      return OperationResult.failure("Settings changed since runtime intent was prepared", "VERSION_CONFLICT", Map.of(), false);
    }
    return apply(snapshot, enabled, record);
  }

  /** Direct ingress looks up a supplied key before reading or preparing a candidate. */
  public OperationAttemptRunner.Result writeIntent(boolean enabled, EngineContext context, String key) {
    return writeIntent(enabled, context, key, () -> {});
  }

  /** The observation runs only after this invocation commits, never on a keyed replay. */
  public OperationAttemptRunner.Result writeIntent(boolean enabled, EngineContext context, String key,
      Runnable committedObservation) {
    requireAttempts();
    java.util.Objects.requireNonNull(committedObservation, "committedObservation");
    var request = request(enabled, context, key);
    Supplier<OperationAttemptRunner.Result> execute = attempts.withPreparation(request, scope -> {
      if (scope.existing().isPresent()) {
        return () -> attempts.start(attempts.lookup(scope.request()).orElseThrow(),
            ignored -> { throw new IllegalStateException("Existing runtime intent must not execute"); });
      }
      var snapshot = capture();
      return () -> commit(scope.request(), snapshot, enabled, committedObservation);
    });
    return execute.get();
  }

  /** Fresh internal mutation; callers with a request context use writeIntent instead. */
  public void setChatEnabled(boolean enabled) {
    requireSuccess(writeIntent(enabled, internal("runtime-intent"), null));
  }

  /** Keep the no-op predicate and candidate bound to one full settings witness. */
  public void recordUserEnabled() {
    var snapshot = capture();
    var spec = RuntimeSpec.fromSettings(snapshot.settings());
    if (spec.chatEnabled() && spec.chatEnabledExplicit()) return;
    requireSuccess(commit(request(true, internal("runtime-activation"), null), snapshot, true));
  }

  /** A boot seed cannot override a choice made after its captured snapshot. */
  public boolean seedAutostartIfUnset() {
    var snapshot = capture();
    if (RuntimeSpec.fromSettings(snapshot.settings()).chatEnabledExplicit()) return false;
    requireSuccess(commit(request(true, internal("runtime-autostart"), null), snapshot, true));
    return true;
  }

  private OperationAttemptRunner.Result commit(OperationAttemptRunner.Request request,
      UiSettingsStore.Snapshot snapshot, boolean enabled) {
    return commit(request, snapshot, enabled, () -> {});
  }

  private OperationAttemptRunner.Result commit(OperationAttemptRunner.Request request,
      UiSettingsStore.Snapshot snapshot, boolean enabled, Runnable committedObservation) {
    var accepted = attempts.accept(request);
    return attempts.start(accepted, record -> {
      var response = apply(snapshot, enabled, record);
      if (response.success()) committedObservation.run();
      return OperationExecution.finished(response);
    });
  }

  private OperationResult apply(UiSettingsStore.Snapshot snapshot, boolean enabled, OperationRecordHandle record) {
    snapshot.settings().setChatEnabled(enabled);
    return attempts.applySettings(record, snapshot.witness(), snapshot.settings());
  }

  private OperationAttemptRunner.Request request(boolean enabled, EngineContext context, String key) {
    var identity = OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY, "core.set-chat-enabled",
        "{\"enabled\":" + enabled + "}", false);
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Instant.now(), Optional.empty());
    return new OperationAttemptRunner.Request(key, identity, context, provenance,
        context.clientKind() == EngineContext.ClientKind.INTERNAL ? OperationHistoryMode.NONE : OperationHistoryMode.STANDARD);
  }

  private UiSettingsStore.Snapshot capture() {
    requireWriter();
    try { return settingsStore.inspect(); }
    catch (CorruptDurableStoreException | UnsupportedStoreVersionException | UncheckedIOException failure) {
      throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings history cannot be verified");
    }
  }

  private void requireWriter() {
    if (settingsStore == null || !settingsStore.mode().isWritable()) {
      throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled");
    }
    requireAttempts();
  }

  private void requireAttempts() {
    if (attempts == null) throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings commit owner is unavailable");
  }

  private static EngineContext internal(String owner) {
    return EngineProvenance.internal(owner, EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  }

  private static OperationPreparationRefused refused(String code, String message) {
    return new OperationPreparationRefused(OperationResult.failure(message, code, Map.of(), false));
  }

  public static void requireSuccess(OperationAttemptRunner.Result result) {
    if (!result.response().success()) throw new SettingsCommitOwner.Refused(result.response());
    result.completion().toCompletableFuture().join();
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;

/** Coordinates producer acceptance; the fixed settings owner alone prepares and commits settings. */
public final class SettingsServiceImpl implements SettingsService {
  private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
  private final UiSettingsStore store;
  private final OperationAttemptRunner attempts;
  private final io.justsearch.app.api.EngineAdmissionService engineAdmission;
  private final Runnable chatEnabledChanged;
  private final java.util.function.Supplier<String> servingRefreshProfileId;
  // Replaces the controller's blocking whole-write monitor. Callbacks cannot wait on themselves.
  private final java.util.concurrent.atomic.AtomicBoolean publicWriteActive = new java.util.concurrent.atomic.AtomicBoolean();
  private final Map<String, Long> latestUiModeIntentByClient =
      new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
          return size() > 64;
        }
      };

  public SettingsServiceImpl(UiSettingsStore store, OperationAttemptRunner attempts) {
    this(store, attempts, () -> {}, null);
  }

  public SettingsServiceImpl(UiSettingsStore store, OperationAttemptRunner attempts, Runnable chatEnabledChanged) {
    this(store, attempts, chatEnabledChanged, null);
  }

  public SettingsServiceImpl(UiSettingsStore store, OperationAttemptRunner attempts,
      Runnable chatEnabledChanged, io.justsearch.app.api.EngineAdmissionService engineAdmission) {
    this(store, attempts, chatEnabledChanged, engineAdmission, () -> null);
  }

  public SettingsServiceImpl(UiSettingsStore store, OperationAttemptRunner attempts,
      Runnable chatEnabledChanged, io.justsearch.app.api.EngineAdmissionService engineAdmission,
      java.util.function.Supplier<String> servingRefreshProfileId) {
    this.store = Objects.requireNonNull(store, "store");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.chatEnabledChanged = Objects.requireNonNull(chatEnabledChanged, "chatEnabledChanged");
    this.engineAdmission = engineAdmission;
    this.servingRefreshProfileId = Objects.requireNonNull(servingRefreshProfileId,
        "servingRefreshProfileId");
  }

  @Override public String servingRefreshProfileId() {
    return servingRefreshProfileId.get();
  }

  @Override
  public OperationResult applyAccepted(io.justsearch.app.api.settings.SettingsV2 input,
      String modeIntentHeader, io.justsearch.core.context.EngineContext context,
      OperationRecordHandle record) {
    return applyAccepted(input, modeIntentHeader, context, record, false);
  }

  @Override
  public OperationResult applyAccepted(io.justsearch.app.api.settings.SettingsV2 input,
      String modeIntentHeader, io.justsearch.core.context.EngineContext context,
      OperationRecordHandle record, boolean refreshInference) {
    return applyAccepted(input, modeIntentHeader, context, record,
        refreshInference ? new SettingsCandidateContext(null, true) : SettingsCandidateContext.NONE);
  }

  @Override
  public OperationResult applyAccepted(io.justsearch.app.api.settings.SettingsV2 input,
      String modeIntentHeader, io.justsearch.core.context.EngineContext context,
      OperationRecordHandle record, SettingsCandidateContext candidateContext) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(record, "accepted record");
    Objects.requireNonNull(candidateContext, "candidateContext");
    if (input.witness() == null || engineAdmission == null || context.workId().isEmpty()) {
      throw new IllegalArgumentException("Accepted reconfigure requires a witness and exact admitted work");
    }
    var patch = SettingsPatch.normalize(input);
    UiModeIntent modeIntent = modeIntentOf(modeIntentHeader, patch);
    if (!publicWriteActive.compareAndSet(false, true)) {
      throw refused("RECONFIGURE_IN_PROGRESS", "Another public settings mutation is active");
    }
    boolean changedChat = false;
    OperationResult result;
    try {
      if (!store.mode().isWritable()) throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled");
      UiSettingsStore.Snapshot snapshot;
      try { snapshot = store.inspect(); }
      catch (CorruptDurableStoreException | UnsupportedStoreVersionException | UncheckedIOException failure) {
        throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings history cannot be verified");
      }
      Long latest = modeIntent == null ? null : latestUiModeIntentByClient.get(modeIntent.clientId());
      boolean applyMode = modeIntent == null || latest == null || modeIntent.sequence() > latest;
      var candidate = SettingsPatch.merge(snapshot.settings(), patch, applyMode);
      String invalidPath = SettingsPatch.validateIndexPath(candidate.getIndexBasePath());
      if (invalidPath != null) throw refused("INVALID_PATH", invalidPath);
      try (var work = engineAdmission.attach(context)) {
        result = attempts.applySettings(record, patch.witness(), candidate,
            candidateContext,
            work);
      } catch (io.justsearch.app.api.EngineAdmissionException refusal) {
        if (!engineAdmission.isClosing()) throw refusal;
        throw refused("ENGINE_CLOSING", "Engine shutdown has closed settings admission");
      }
      if (result.success()) {
        if (modeIntent != null && applyMode) latestUiModeIntentByClient.put(modeIntent.clientId(), modeIntent.sequence());
        changedChat = !Objects.equals(snapshot.settings().getChatEnabled(), candidate.getChatEnabled());
      }
    } finally {
      publicWriteActive.set(false);
    }
    if (changedChat) {
      try { chatEnabledChanged.run(); }
      catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(SettingsServiceImpl.class)
            .warn("Settings committed but chat reconciliation notification failed", failure);
      }
    }
    return result;
  }

  @Override
  public OperationAttemptRunner.Result applyInternal(io.justsearch.app.api.UiSettings candidate,
      io.justsearch.app.api.settings.SettingsWitness expected,
      io.justsearch.core.context.EngineContext context) {
    return applyInternal(candidate, expected, context,
        SettingsCandidateContext.NONE);
  }

  @Override
  public OperationAttemptRunner.Result applyInternal(io.justsearch.app.api.UiSettings candidate,
      io.justsearch.app.api.settings.SettingsWitness expected,
      io.justsearch.core.context.EngineContext context,
      SettingsCandidateContext candidateContext) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(candidateContext, "candidateContext");
    if (!io.justsearch.agent.api.registry.TransportTag.SYSTEM_INTERNAL.name().equals(context.transport())) {
      throw new IllegalArgumentException("Internal settings producer requires system transport");
    }
    io.justsearch.app.services.intent.EngineProvenance.sourceTier(context);
    var frozen = JSON.readValue(JSON.writeValueAsString(candidate), io.justsearch.app.api.UiSettings.class);
    var identity = new java.util.LinkedHashMap<String, Object>();
    identity.put("settings", frozen);
    identity.put("expected", expected);
    if (candidateContext.hasChatProfile()) {
      identity.put("chatProfile", candidateContext.chatProfile().id());
    }
    if (candidateContext.forceGenerativeRefresh()) identity.put("forceGenerativeRefresh", true);
    var descriptor = io.justsearch.app.api.operations.OperationDescriptor.invocation(
        io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
        SettingsCandidateContext.NONE.equals(candidateContext)
            ? "settings.apply-internal" : SettingsCandidatePreparation.OPERATION_REF,
        JSON.writeValueAsString(identity), false);
    var request = new OperationAttemptRunner.Request(null, descriptor, context,
        io.justsearch.app.services.intent.EngineProvenance.invocation(context,
            io.justsearch.agent.api.registry.ExecutorTag.UI, java.time.Instant.now(), java.util.Optional.empty()));
    var accepted = SettingsCandidateContext.NONE.equals(candidateContext) ? attempts.accept(request)
        : attempts.withPreparation(request, scope -> {
          if (scope.existing().isPresent()) {
            return (java.util.function.Supplier<OperationAttemptRunner.PreparedAttempt>)
                () -> attempts.lookup(scope.request()).orElseThrow();
          }
          var pending = attempts.pendingPreparation(scope.request());
          var saved = pending.isPresent() ? pending : attempts.savePreparation(scope.request(),
              new io.justsearch.app.api.operations.OperationStore.Preparation(
                  java.util.UUID.randomUUID(), SettingsCandidatePreparation.encode(candidateContext)));
          if (saved.isEmpty()) {
            return (java.util.function.Supplier<OperationAttemptRunner.PreparedAttempt>)
                () -> attempts.lookup(scope.request()).orElseThrow();
          }
          return (java.util.function.Supplier<OperationAttemptRunner.PreparedAttempt>)
              () -> attempts.acceptPrepared(scope.request(), saved.orElseThrow().nonce());
        }).get();
    return attempts.start(accepted, record -> io.justsearch.agent.api.registry.OperationExecution.finished(
        !SettingsCandidateContext.NONE.equals(candidateContext)
            ? attempts.applySettings(record, expected, frozen, candidateContext)
            : attempts.applySettings(record, expected, frozen)));
  }

  @Override
  public OperationAttemptRunner.Result applyPublic(io.justsearch.app.api.settings.SettingsV2 input,
      String modeIntentHeader, io.justsearch.core.context.EngineContext context) {
    if (input == null || input.witness() == null) {
      throw new IllegalArgumentException("A complete settings witness is required");
    }
    try { io.justsearch.app.api.operations.OperationKeys.timestampMillis(input.operationKey()); }
    catch (IllegalArgumentException invalid) {
      throw new io.justsearch.app.api.operations.OperationStoreException(
          io.justsearch.app.api.operations.OperationStoreException.Code.INVALID_OPERATION_KEY, invalid);
    }
    Objects.requireNonNull(context, "context");
    var patch = SettingsPatch.normalize(input);
    UiModeIntent modeIntent = modeIntentOf(modeIntentHeader, patch);
    var identity = new java.util.LinkedHashMap<String, Object>();
    identity.put("patch", patch);
    identity.put("modeIntent", modeIntent);
    var descriptor = io.justsearch.app.api.operations.OperationDescriptor.invocation(
        io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY, "settings.apply-public",
        JSON.writeValueAsString(identity), false);
    var request = new OperationAttemptRunner.Request(input.operationKey(), descriptor, context,
        io.justsearch.app.services.intent.EngineProvenance.invocation(context,
            io.justsearch.agent.api.registry.ExecutorTag.UI, java.time.Instant.now(), java.util.Optional.empty()),
        io.justsearch.app.api.operations.OperationHistoryMode.STANDARD);
    java.util.function.Supplier<OperationAttemptRunner.Result> execute = attempts.withPreparation(request, scope -> {
      if (scope.existing().isPresent()) {
        return () -> startPublic(attempts.lookup(scope.request()).orElseThrow(),
            ignored -> { throw new IllegalStateException("A recorded settings attempt cannot execute again"); });
      }
      return () -> applyUnknown(scope.request(), patch, modeIntent);
    });
    return execute.get();
  }

  private OperationAttemptRunner.Result applyUnknown(OperationAttemptRunner.Request request,
      io.justsearch.app.api.settings.SettingsV2 patch, UiModeIntent modeIntent) {
    if (!publicWriteActive.compareAndSet(false, true)) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Another public settings mutation is active", "RECONFIGURE_IN_PROGRESS", Map.of(), true));
    }
    OperationAttemptRunner.Result result;
    boolean changedChat = false;
    try {
      // A same-key contender could have accepted after preparation returned but before this gate.
      var existing = attempts.lookup(request);
      if (existing.isPresent()) {
        return startPublic(existing.orElseThrow(),
            ignored -> { throw new IllegalStateException("A recorded settings attempt cannot execute again"); });
      }
      if (!store.mode().isWritable()) throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled");
      UiSettingsStore.Snapshot snapshot;
      try { snapshot = store.inspect(); }
      catch (CorruptDurableStoreException | UnsupportedStoreVersionException | UncheckedIOException failure) {
        throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings history cannot be verified");
      }
      Boolean before = snapshot.settings().getChatEnabled();
      Long latest = modeIntent == null ? null : latestUiModeIntentByClient.entrySet().stream()
          .filter(entry -> entry.getKey().equals(modeIntent.clientId())).map(Map.Entry::getValue).findFirst().orElse(null);
      boolean applyMode = modeIntent == null || latest == null || modeIntent.sequence() > latest;
      var candidate = SettingsPatch.merge(snapshot.settings(), patch, applyMode);
      String invalidPath = SettingsPatch.validateIndexPath(candidate.getIndexBasePath());
      if (invalidPath != null) throw refused("INVALID_PATH", invalidPath);
      var accepted = attempts.accept(request);
      if (accepted.existing()) {
        return startPublic(accepted,
            ignored -> { throw new IllegalStateException("A recorded settings attempt cannot execute again"); });
      }
      result = startPublic(accepted, record ->
          io.justsearch.agent.api.registry.OperationExecution.finished(
              attempts.applySettings(record, patch.witness(), candidate)));
      if (result.response().success()
          && result.record().state() == io.justsearch.app.api.operations.OperationState.COMPLETE) {
        if (modeIntent != null && applyMode) {
          latestUiModeIntentByClient.put(modeIntent.clientId(), modeIntent.sequence());
        } else if (modeIntent != null) {
          latestUiModeIntentByClient.get(modeIntent.clientId()); // Touch only after commitment.
        }
        changedChat = !Objects.equals(before, candidate.getChatEnabled());
      }
    } finally {
      publicWriteActive.set(false);
    }
    if (changedChat) {
      try { chatEnabledChanged.run(); }
      catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(SettingsServiceImpl.class)
            .warn("Settings committed but chat reconciliation notification failed", failure);
      }
    }
    return result;
  }

  private static UiModeIntent modeIntentOf(String raw, io.justsearch.app.api.settings.SettingsV2 incoming) {
    if (incoming.ui() == null || incoming.ui().mode() == null || raw == null || raw.isBlank()) return null;
    int separator = raw.lastIndexOf(':');
    if (separator < 1 || separator == raw.length() - 1) throw new IllegalArgumentException("Invalid UI mode intent header");
    String clientId = raw.substring(0, separator);
    if (clientId.length() > 96 || !clientId.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Invalid UI mode intent client id");
    }
    long sequence = Long.parseLong(raw.substring(separator + 1));
    if (sequence <= 0) throw new IllegalArgumentException("UI mode intent sequence must be positive");
    return new UiModeIntent(clientId, sequence);
  }

  private record UiModeIntent(String clientId, long sequence) {}

  private OperationAttemptRunner.Result startPublic(OperationAttemptRunner.PreparedAttempt accepted,
      java.util.function.Function<OperationRecordHandle, io.justsearch.agent.api.registry.OperationExecution> body) {
    try { return attempts.start(accepted, body); }
    catch (io.justsearch.app.api.operations.OperationStoreException failure) {
      var projection = io.justsearch.app.api.registry.OperationInvocationResponse.fromStoreFailure(failure);
      // Preserve the last known acceptance and its exceptional completion. This is not a
      // pre-effect refusal or a terminal FAILED verdict; the physical owner decides recovery.
      var response = new OperationResult(false, projection.message(), java.util.Optional.empty(),
          Map.of("operationKey", accepted.accepted().key(), "operationRecordId", accepted.accepted().id()),
          java.util.Optional.of(projection.errorCode()), Map.of(), java.util.Optional.of(false));
      return new OperationAttemptRunner.Result(accepted.accepted(), response, accepted.completion());
    }
  }

  @Override
  public OperationPreparation prepareReset(String argumentsJson) {
    if (!store.mode().isWritable()) {
      throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled");
    }
    try {
      return SettingsResetPreparation.normal(argumentsJson, store.inspect().witness());
    } catch (CorruptDurableStoreException | UnsupportedStoreVersionException | UncheckedIOException unreadable) {
      try {
        return SettingsResetPreparation.recovery(argumentsJson, store.recoveryFingerprint());
      } catch (IOException unavailable) {
        throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings recovery evidence cannot be verified");
      }
    }
  }

  @Override
  public OperationResult resetToDefaults(OperationRecordHandle record) {
    return attempts.applySettingsReset(record);
  }

  private static OperationPreparationRefused refused(String code, String message) {
    return new OperationPreparationRefused(OperationResult.failure(message, code, Map.of(), false));
  }
}

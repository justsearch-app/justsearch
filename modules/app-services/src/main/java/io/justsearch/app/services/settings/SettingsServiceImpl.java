/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
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

  public SettingsServiceImpl(UiSettingsStore store, OperationAttemptRunner attempts) {
    this.store = Objects.requireNonNull(store, "store");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
  }

  @Override
  public OperationAttemptRunner.Result applyInternal(io.justsearch.app.api.UiSettings candidate,
      io.justsearch.app.api.settings.SettingsWitness expected,
      io.justsearch.core.context.EngineContext context) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(context, "context");
    if (!io.justsearch.agent.api.registry.TransportTag.SYSTEM_INTERNAL.name().equals(context.transport())) {
      throw new IllegalArgumentException("Internal settings producer requires system transport");
    }
    io.justsearch.app.services.intent.EngineProvenance.sourceTier(context);
    var frozen = JSON.readValue(JSON.writeValueAsString(candidate), io.justsearch.app.api.UiSettings.class);
    var descriptor = io.justsearch.app.api.operations.OperationDescriptor.invocation(
        io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY, "settings.apply-internal",
        JSON.writeValueAsString(Map.of("settings", frozen, "expected", expected)), false);
    var request = new OperationAttemptRunner.Request(null, descriptor, context,
        io.justsearch.app.services.intent.EngineProvenance.invocation(context,
            io.justsearch.agent.api.registry.ExecutorTag.UI, java.time.Instant.now(), java.util.Optional.empty()));
    var accepted = attempts.accept(request);
    return attempts.start(accepted, record -> io.justsearch.agent.api.registry.OperationExecution.finished(
        attempts.applySettings(record, expected, frozen)));
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

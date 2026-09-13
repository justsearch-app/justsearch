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

/** Reads reset intent once; the fixed settings owner alone prepares and commits the candidate. */
public final class SettingsServiceImpl implements SettingsService {
  private final UiSettingsStore store;
  private final OperationAttemptRunner attempts;

  public SettingsServiceImpl(UiSettingsStore store, OperationAttemptRunner attempts) {
    this.store = Objects.requireNonNull(store, "store");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
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

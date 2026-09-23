/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;

/** Settings producers route through the existing accepted operation owner. */
public interface SettingsService {
  /**
   * Accept one fresh internal attempt for a caller-owned candidate and its captured full witness.
   * Internal producers do not reuse a public ingress key; the parent operation owns their retry.
   * The result retains the row identity on typed refusal. Persistence uncertainty propagates
   * from the runner and leaves its row unresolved. Only COMPLETE proves commitment.
   */
  io.justsearch.app.api.operations.OperationAttemptRunner.Result applyInternal(
      UiSettings candidate, io.justsearch.app.api.settings.SettingsWitness expected,
      io.justsearch.core.context.EngineContext context);

  /** Public partial mutation; the caller retains one witness and key for every retry. */
  io.justsearch.app.api.operations.OperationAttemptRunner.Result applyPublic(
      io.justsearch.app.api.settings.SettingsV2 input, String modeIntentHeader,
      io.justsearch.core.context.EngineContext context);

  /** Execute a public patch inside the already accepted reconfigure row. */
  OperationResult applyAccepted(io.justsearch.app.api.settings.SettingsV2 input,
      String modeIntentHeader, io.justsearch.core.context.EngineContext context,
      OperationRecordHandle record);

  /** Freeze the readable witness or absent-history quarantine identity without an effect. */
  OperationPreparation prepareReset(String argumentsJson);

  /** Reset fixed user-facing defaults using this runner's currently executing accepted row. */
  OperationResult resetToDefaults(OperationRecordHandle record);
}

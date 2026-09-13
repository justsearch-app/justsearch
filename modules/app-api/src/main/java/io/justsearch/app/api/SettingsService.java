/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;

/** Settings reset preparation and execution through the accepted operation owner. */
public interface SettingsService {
  /** Freeze the readable witness or absent-history quarantine identity without an effect. */
  OperationPreparation prepareReset(String argumentsJson);

  /** Reset fixed user-facing defaults using this runner's currently executing accepted row. */
  OperationResult resetToDefaults(OperationRecordHandle record);
}

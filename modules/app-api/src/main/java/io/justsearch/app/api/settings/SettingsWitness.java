/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import io.justsearch.app.api.operations.OperationKeys;

/** The settings envelope's one revision/commit identity, shared by storage and compare-and-apply. */
public record SettingsWitness(long acceptedRevision, String lastCommittedOperationKey) {
  public SettingsWitness {
    if (acceptedRevision < 0 || (acceptedRevision == 0 && lastCommittedOperationKey != null)) {
      throw new IllegalArgumentException("Invalid settings revision witness");
    }
    if (acceptedRevision > 0) OperationKeys.timestampMillis(lastCommittedOperationKey);
  }
}

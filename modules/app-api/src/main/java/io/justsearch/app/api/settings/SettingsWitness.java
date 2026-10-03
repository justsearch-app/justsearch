/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import io.justsearch.app.api.operations.OperationKeys;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The settings envelope's one revision/commit identity, shared by storage and compare-and-apply. */
public record SettingsWitness(@JsonProperty(required = true) long acceptedRevision,
    @JsonProperty(required = true) String lastCommittedOperationKey) {
  public SettingsWitness {
    if (acceptedRevision < 0 || (acceptedRevision == 0 && lastCommittedOperationKey != null)) {
      throw new IllegalArgumentException("Invalid settings revision witness");
    }
    if (acceptedRevision > 0) OperationKeys.timestampMillis(lastCommittedOperationKey);
  }
}

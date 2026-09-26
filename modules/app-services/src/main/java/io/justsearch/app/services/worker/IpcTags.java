/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Objects;
import java.util.Set;

/** Tag schemas for {@code ipc.*} metrics. */
public final class IpcTags {

  private IpcTags() {}

  static final String KEY_OUTCOME = "outcome";

  static final Set<String> OUTCOME_KEYS = Set.of(KEY_OUTCOME);

  /** Tag schema for {@code ipc.worker.restart}. */
  public record WorkerRestartTags(WorkerRestartOutcome outcome) implements TagSchema {

    public WorkerRestartTags {
      Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public Set<String> allowedKeys() {
      return OUTCOME_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_OUTCOME), outcome.wireValue());
    }
  }
}

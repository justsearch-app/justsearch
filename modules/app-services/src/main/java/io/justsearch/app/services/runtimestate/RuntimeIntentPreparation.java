/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.settings.SettingsWitness;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Frozen runtime-intent metadata; decoding never authorizes a settings mutation. */
public final class RuntimeIntentPreparation {
  public static final String SCHEMA = "settings-runtime-intent-v1";
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private RuntimeIntentPreparation() {}

  /** Preserves raw caller arguments while freezing the complete witness used for the decision. */
  public static OperationPreparation prepare(String publicArguments, SettingsWitness expected) {
    Objects.requireNonNull(publicArguments, "publicArguments");
    requireWitness(expected);
    return new OperationPreparation(publicArguments, SCHEMA, JSON.writeValueAsString(expected));
  }

  /** Validates frozen witness metadata without reading settings or granting row authority. */
  public static SettingsWitness validate(OperationPreparation prepared) {
    try {
      Objects.requireNonNull(prepared, "prepared");
      Objects.requireNonNull(prepared.argumentsJson(), "argumentsJson");
      if (!SCHEMA.equals(prepared.replaySchema())
          || prepared.content() != OperationPreparation.Content.METADATA) {
        throw new IllegalArgumentException("Runtime intent preparation schema mismatch");
      }
      JsonNode payload = JSON.readTree(prepared.replayPayloadJson());
      if (payload == null || !payload.isObject() || payload.size() != 2
          || !payload.has("acceptedRevision") || !payload.has("lastCommittedOperationKey")) {
        throw new IllegalArgumentException("Invalid runtime intent payload fields");
      }
      JsonNode revision = payload.get("acceptedRevision");
      JsonNode key = payload.get("lastCommittedOperationKey");
      if (!revision.isIntegralNumber() || !revision.canConvertToLong()
          || (!key.isNull() && !key.isTextual())) {
        throw new IllegalArgumentException("Invalid settings witness fields");
      }
      long acceptedRevision = revision.longValue();
      if (acceptedRevision == Long.MAX_VALUE) {
        throw new IllegalArgumentException("Invalid settings witness revision");
      }
      return new SettingsWitness(acceptedRevision, key.isNull() ? null : key.textValue());
    } catch (RuntimeException malformed) {
      // Persisted input can contain private values; do not expose parser details or nested causes.
      throw new IllegalArgumentException("Invalid runtime intent preparation");
    }
  }

  private static void requireWitness(SettingsWitness expected) {
    Objects.requireNonNull(expected, "expected");
    if (expected.acceptedRevision() == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid settings witness revision");
    }
  }
}

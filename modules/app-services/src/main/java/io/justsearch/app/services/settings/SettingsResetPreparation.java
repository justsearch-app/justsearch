/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.registry.executor.PreparedInvocationCodec;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed server-prepared reset metadata; decoding alone never authorizes settings mutation. */
public final class SettingsResetPreparation {
  public static final String SCHEMA = "settings-reset-v1";
  public static final String OPERATION_ID = "core.reset-settings";
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private SettingsResetPreparation() {}

  /** Exactly one readable-history witness or absent-history quarantine identity. */
  public record Intent(SettingsWitness expected, String quarantineFingerprint) {
    public Intent {
      if ((expected == null) == (quarantineFingerprint == null)
          || (expected != null && expected.acceptedRevision() == Long.MAX_VALUE)
          || (quarantineFingerprint != null && !quarantineFingerprint.matches("[0-9a-f]{64}"))) {
        throw new IllegalArgumentException("Invalid settings reset intent");
      }
    }
    public boolean recovery() { return quarantineFingerprint != null; }
    public long expectedRevision() { return recovery() ? 0 : expected.acceptedRevision(); }
  }

  public static OperationPreparation normal(String publicArguments, SettingsWitness expected) {
    return prepare(publicArguments, new Intent(Objects.requireNonNull(expected, "expected"), null));
  }

  public static OperationPreparation recovery(String publicArguments, String fingerprint) {
    return prepare(publicArguments, new Intent(null, Objects.requireNonNull(fingerprint, "fingerprint")));
  }

  private static OperationPreparation prepare(String publicArguments, Intent intent) {
    requireEmptyArguments(publicArguments);
    return new OperationPreparation(publicArguments, SCHEMA, JSON.writeValueAsString(intent));
  }

  /** Bound to the runner's actual accepted row and preparation, including invoke rather than undo. */
  public static Intent decode(OperationRecord row, OperationStore.Preparation accepted) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(accepted, "accepted");
    try {
      var preparation = PreparedInvocationCodec.decodeMetadata(accepted.payload(), row.key(),
          accepted.nonce(), row.descriptor());
      if (!SCHEMA.equals(preparation.replaySchema())
          || preparation.content() != OperationPreparation.Content.METADATA
          || !row.descriptor().hasSameIdentity(OperationDescriptor.invocation(OperationKind.SETTINGS_APPLY,
              OPERATION_ID, preparation.argumentsJson(), false))) {
        throw new IllegalArgumentException("Settings reset preparation binding mismatch");
      }
      requireEmptyArguments(preparation.argumentsJson());
      JsonNode payload = JSON.readTree(preparation.replayPayloadJson());
      if (payload == null || !payload.isObject() || payload.size() != 2
          || !payload.has("expected") || !payload.has("quarantineFingerprint")) {
        throw new IllegalArgumentException("Invalid settings reset payload fields");
      }
      JsonNode expected = payload.get("expected");
      if (!expected.isNull()) requireWitnessFields(expected);
      JsonNode fingerprint = payload.get("quarantineFingerprint");
      if (!fingerprint.isNull() && !fingerprint.isTextual()) {
        throw new IllegalArgumentException("Invalid quarantine fingerprint type");
      }
      Intent intent = JSON.treeToValue(payload, Intent.class);
      if (row.expectedSettingsRevision() != null && row.expectedSettingsRevision() != intent.expectedRevision()) {
        throw new IllegalArgumentException("Settings reset SQL marker mismatch");
      }
      return intent;
    } catch (RuntimeException malformed) {
      // Jackson exceptions may contain private persisted input fragments; return only a bounded cause-free error.
      throw new IllegalArgumentException("Invalid accepted settings reset preparation");
    }
  }

  private static void requireWitnessFields(JsonNode expected) {
    if (!expected.isObject() || expected.size() != 2
        || !expected.has("acceptedRevision") || !expected.has("lastCommittedOperationKey")
        || !expected.get("acceptedRevision").isIntegralNumber() || !expected.get("acceptedRevision").canConvertToLong()
        || !(expected.get("lastCommittedOperationKey").isNull() || expected.get("lastCommittedOperationKey").isTextual())) {
      throw new IllegalArgumentException("Invalid settings witness fields");
    }
  }

  private static void requireEmptyArguments(String publicArguments) {
    Objects.requireNonNull(publicArguments, "publicArguments");
    final JsonNode arguments;
    try { arguments = JSON.readTree(publicArguments); }
    catch (JacksonException malformed) {
      throw new IllegalArgumentException("Settings reset takes an empty argument object");
    }
    if (arguments == null || !arguments.isObject() || arguments.size() != 0) {
      throw new IllegalArgumentException("Settings reset takes an empty argument object");
    }
  }
}

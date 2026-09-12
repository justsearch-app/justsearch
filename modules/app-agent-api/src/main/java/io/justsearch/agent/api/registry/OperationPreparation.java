/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Immutable value prepared before an operation is accepted.
 *
 * <p>{@code argumentsJson} is the raw invocation input and is transient: callers must not persist
 * it in an operation row. A replay payload is optional, but when present its schema and payload are
 * both required and bounded. The payload is reserved for safe replay data such as paths, roots,
 * generation, collection, and policy; it must not contain prompts or document content.
 */
public record OperationPreparation(
    String argumentsJson, String replaySchema, String replayPayloadJson) {

  private static final int MAX_REPLAY_SCHEMA_CHARS = 128;
  private static final int MAX_REPLAY_PAYLOAD_CHARS = 200_000;

  public OperationPreparation {
    Objects.requireNonNull(argumentsJson, "argumentsJson");

    boolean schemaAbsent = replaySchema == null;
    boolean payloadAbsent = replayPayloadJson == null;
    if (schemaAbsent != payloadAbsent) {
      throw new IllegalArgumentException(
          "replaySchema and replayPayloadJson must be both absent or both present");
    }
    if (!schemaAbsent) {
      if (replaySchema.isBlank()) {
        throw new IllegalArgumentException("replaySchema, when present, must be non-blank");
      }
      if (replayPayloadJson.isBlank()) {
        throw new IllegalArgumentException(
            "replayPayloadJson, when present, must be non-blank");
      }
      if (replaySchema.length() > MAX_REPLAY_SCHEMA_CHARS) {
        throw new IllegalArgumentException("replaySchema exceeds 128 characters");
      }
      if (replayPayloadJson.length() > MAX_REPLAY_PAYLOAD_CHARS) {
        throw new IllegalArgumentException("replayPayloadJson exceeds 200000 characters");
      }
    }
  }

  /** Creates a preparation that carries raw arguments without a replay payload. */
  public static OperationPreparation passthrough(String argumentsJson) {
    return new OperationPreparation(argumentsJson, null, null);
  }
}

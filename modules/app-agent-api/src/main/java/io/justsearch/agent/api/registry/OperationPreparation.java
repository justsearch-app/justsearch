/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Immutable value prepared before an operation is accepted.
 *
 * <p>{@code argumentsJson} is the invocation input. Passthrough values remain transient; a
 * replay value persists only in the classified prepared envelope, separate from the public-input
 * digest used for key comparison. A replay payload is optional, but its schema and payload are
 * both required and bounded in UTF-8 bytes. METADATA is for paths, roots, generation, collection
 * and policy. A server handler with prompts or document content must declare CONTENT, which
 * requires the whole persisted preparation to be sealed using the existing data-key authority.
 * A replay handler must explicitly validate its schema and implement prepared execution. An
 * accepted incomplete row never authorizes caller-driven re-execution; reconciliation owns replay.
 */
public record OperationPreparation(
    String argumentsJson, String replaySchema, String replayPayloadJson, Content content) {

  public enum Content { METADATA, CONTENT }

  public OperationPreparation(String argumentsJson, String replaySchema, String replayPayloadJson) {
    this(argumentsJson, replaySchema, replayPayloadJson, Content.METADATA);
  }

  private static final int MAX_REPLAY_SCHEMA_BYTES = 128;
  private static final int MAX_REPLAY_PAYLOAD_BYTES = 200_000;

  public OperationPreparation {
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    Objects.requireNonNull(content, "content");

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
      if (replaySchema.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_REPLAY_SCHEMA_BYTES) {
        throw new IllegalArgumentException("replaySchema exceeds 128 UTF-8 bytes");
      }
      if (replayPayloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_REPLAY_PAYLOAD_BYTES) {
        throw new IllegalArgumentException("replayPayloadJson exceeds 200000 UTF-8 bytes");
      }
    }
  }

  @Override
  public String toString() {
    return "OperationPreparation[content=" + content + ", replay=" + (replaySchema != null) + "]";
  }

  /** Creates a preparation that carries raw arguments without a replay payload. */
  public static OperationPreparation passthrough(String argumentsJson) {
    return new OperationPreparation(argumentsJson, null, null);
  }
}

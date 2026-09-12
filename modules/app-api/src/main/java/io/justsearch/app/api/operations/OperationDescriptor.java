/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.util.Objects;

/**
 * The replay dependency identity. Generic arguments are represented by a canonical digest;
 * replayable owners may retain roots, generation and policy, never document or prompt content.
 * The store canonicalizes the JSON before comparison. Invoke/undo and the undo target belong
 * inside this identity, so sharing a key cannot return an unrelated operation's receipt.
 */
public record OperationDescriptor(OperationKind kind, String operationRef, String identityJson) {
  /** Generic invocation identity persists a digest rather than the content-bearing arguments. */
  public static OperationDescriptor invocation(OperationKind kind, String operationRef,
      String argumentsJson, boolean undo) {
    String digest = CanonicalOperationArguments.digest(argumentsJson);
    return new OperationDescriptor(kind, operationRef,
        "{\"mode\":\"" + (undo ? "undo" : "invoke") + "\",\"argumentsSha256\":\"" + digest + "\"}");
  }

  public OperationDescriptor {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(identityJson, "identityJson");
    if (operationRef != null && (operationRef.isBlank() || operationRef.length() > 256)) {
      throw new IllegalArgumentException("Invalid operation reference");
    }
    if (identityJson.length() > 262144) throw new IllegalArgumentException("Operation identity is too large");
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Opaque preparation storage value, excluded from operation history and receipt projections. */
public record OperationPreparedPayload(boolean sealed, String value) {
  public static final int MAX_ENVELOPE_BYTES = 524_288;
  public static final int MAX_STORED_BYTES = 750_000;

  public OperationPreparedPayload {
    Objects.requireNonNull(value, "value");
    if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_STORED_BYTES) {
      throw new IllegalArgumentException("Invalid prepared payload size");
    }
  }

  @Override
  public String toString() { return "OperationPreparedPayload[sealed=" + sealed + "]"; }
}

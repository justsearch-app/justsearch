/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Wire-format request for {@code POST /api/operations/{id}/invoke} (slice 3a-1-2).
 *
 * <p>The operation id is in the path; the body carries the runtime arguments plus
 * optional client-supplied keys for idempotency and HIGH-risk confirmation. {@code args}
 * is a free-shape JSON object whose schema is operation-specific and declared on the
 * Operation registry entry.
 *
 * <p>{@code idempotencyKey}: optional canonical UUIDv7 for durable operation lookup. Matching
 * public input returns the recorded outcome without preparing or executing again; changed input
 * conflicts. Unkeyed accepted calls receive an Engine-minted key in the response metadata.
 *
 * <p>{@code confirmationToken}: opt-in for HIGH-risk operations. The
 * {@code OperationPolicy.confirm} axis and backend trust lattice determine the gate. The token is
 * a single-use consent capsule bound to the operation and its arguments, never a typed UI string.
 *
 * <p>Stability: stable (API contract).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationInvocationRequest(
    Map<String, Object> args, String idempotencyKey, String confirmationToken) {

  public OperationInvocationRequest {
    args = args == null ? Map.of() : Map.copyOf(args);
  }
}

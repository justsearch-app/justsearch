/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.Map;

/**
 * Wire-format response for {@code POST /api/operations/{id}/invoke} (slice 3a-1-2).
 *
 * <p>Mirrors {@link OperationResult}'s shape on the wire with an additional
 * {@code errorClass} for controller-level rejections (operation-not-found, bad-request,
 * handler-threw). On success, {@code errorClass} is absent. Handler-returned failures
 * (where {@code OperationResult.success() == false}) carry the handler's {@code message}
 * with {@code errorClass} = {@code "HANDLER_FAILURE"}.
 *
 * <p>Stability: stable (API contract).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationInvocationResponse(
    boolean success,
    String message,
    String executionId,
    Map<String, Object> structuredData,
    String errorClass,
    String errorCode,
    Map<String, Object> errorDetails,
    Boolean retryable) {

  public OperationInvocationResponse {
    structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    // errorDetails left as-is (nullable) so @JsonInclude(NON_NULL) elides it on success.
    // We only normalize when the field is meaningful (failure paths).
  }

  /**
   * Maps a handler-produced {@link OperationResult} to its wire response shape.
   *
   * <p>Slice 3a-2-c Phase B: threads typed error metadata
   * ({@code errorCode}, {@code errorDetails}, {@code retryable}) from the
   * {@link OperationResult} through to the wire shape so FE consumers can
   * branch on the typed code (e.g., POLICY_ONLINE_AI_DISABLED) instead of
   * parsing the message string.
   */
  public static OperationInvocationResponse fromResult(OperationResult result) {
    Map<String, Object> details = result.errorDetails();
    return new OperationInvocationResponse(
        result.success(),
        result.message(),
        result.executionId().orElse(null),
        result.structuredData(),
        result.success() ? null : "HANDLER_FAILURE",
        result.errorCode().orElse(null),
        details == null || details.isEmpty() ? null : details,
        result.retryable().orElse(null));
  }

  /**
   * Controller-level failure (operation-not-found / bad-request / handler threw before
   * returning a result). {@code errorClass} is the canonical wire token consumers can
   * branch on; {@code message} is human-readable.
   */
  public static OperationInvocationResponse error(String message, String errorClass) {
    return new OperationInvocationResponse(
        false, message, null, Map.of(), errorClass, null, null, null);
  }

  /** Existing STORE_LOCKED contract: unlock is required before retry, with no private content. */
  public static OperationInvocationResponse fromLockedStore() {
    String code = io.justsearch.app.api.ApiErrorCode.STORE_LOCKED.name();
    return new OperationInvocationResponse(false, "Unlock encrypted storage to continue", null,
        Map.of(), code, code, Map.of("locked", true), false);
  }

  /** Public key/storage failure projection; native causes and stored invocation content stay private. */
  public static OperationInvocationResponse fromStoreFailure(
      io.justsearch.app.api.operations.OperationStoreException failure) {
    String code = switch (failure.code()) {
      case INVALID_OPERATION_KEY -> "OPERATION_KEY_INVALID";
      case OPERATION_EXPIRED -> "OPERATION_KEY_EXPIRED";
      case STORAGE_FAILED -> "OPERATION_STORAGE_FAILED";
      default -> failure.code().name();
    };
    String errorClass = switch (failure.code()) {
      case INVALID_OPERATION_KEY -> "BAD_REQUEST";
      case OPERATION_EXPIRED, OPERATION_KEY_REUSED, OPERATION_PREPARATION_UNAVAILABLE -> "CONFLICT";
      case OPERATIONS_CAPACITY -> "UNAVAILABLE";
      case STORAGE_FAILED -> "HANDLER_ERROR";
    };
    return new OperationInvocationResponse(false, code, null, Map.of(), errorClass, code, null,
        failure.code() == io.justsearch.app.api.operations.OperationStoreException.Code.OPERATIONS_CAPACITY);
  }
}

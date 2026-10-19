/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.configuration.persistence.StoreIds;
import java.util.Map;

/** Validates decoded request ids before a store call or streaming response can start. */
final class StoreIdRequest {
  private StoreIdRequest() {}

  static boolean rejectUnsafe(Context ctx, String id) {
    try {
      StoreIds.requireValid(id);
      return false;
    } catch (IllegalArgumentException invalid) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, invalid.getMessage()));
      return true;
    }
  }

  static boolean rejectBody(Context ctx, Map<String, Object> body) {
    for (String key : new String[] {"sessionId", "conversationId"}) {
      Object value = body.get(key);
      if (value == null) continue;
      if (!(value instanceof String id)) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, key + " must be a string"));
        return true;
      }
      if (rejectUnsafe(ctx, id)) return true;
    }
    return false;
  }
}

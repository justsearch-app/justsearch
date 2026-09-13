/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkHandle;
import java.util.Map;

/** Request-side ownership only. The Engine owns admission state and the foreground gauge. */
public final class RequestEngineWork {
  public static final String ATTRIBUTE = "__engine_work__";
  public static final String REFUSAL_ATTRIBUTE = "__engine_work_refusal__";

  private RequestEngineWork() {}

  public static EngineWorkHandle get(Context context) { return context.attribute(ATTRIBUTE); }

  public static String errorCode(EngineAdmissionException failure) {
    return switch (failure.reason()) {
      case CONTEXT_LIMIT -> ApiErrorCode.ADMISSION_CONTEXT_LIMIT.name();
      case ENGINE_LIMIT -> ApiErrorCode.ADMISSION_ENGINE_LIMIT.name();
      case FROZEN -> "UPGRADE_PREPARING";
      case WORK_FINISHED -> ApiErrorCode.SERVICE_UNAVAILABLE.name();
    };
  }

  public static void status(Context context, EngineAdmissionException failure) {
    boolean capacity = failure.reason() == EngineAdmissionException.Reason.CONTEXT_LIMIT
        || failure.reason() == EngineAdmissionException.Reason.ENGINE_LIMIT;
    context.status(capacity ? 429 : 503);
    if (capacity) context.header("Retry-After", Integer.toString(failure.retryAfterSeconds()));
  }

  static void writeRefusal(Context context, EngineAdmissionException failure) {
    status(context, failure);
    context.json(Map.of("error", failure.getMessage(), "errorCode", errorCode(failure),
        "errorClass", "TRANSIENT", "retryable", true,
        "retrySafe", context.attribute(REFUSAL_ATTRIBUTE) == failure));
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.core.status.StatusChangeCounter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Additive transport metadata, captured when a response/event is sent, including replay. */
final class StatusChangeHeaders {
  static final String CHANGE = "X-JustSearch-Status-Change";
  static final String EPOCH = "X-JustSearch-Status-Epoch";
  static final String REFLECTED = "X-JustSearch-Status-Reflected";

  private StatusChangeHeaders() {}

  static void install(Javalin app) {
    app.after(ctx -> { if (ctx.path().startsWith("/api/")) current(ctx); });
  }

  static void current(Context ctx) {
    var counter = StatusChangeCounter.process();
    ctx.header(EPOCH, counter.epoch());
    ctx.header(CHANGE, Long.toString(counter.current()));
  }

  static Map<String, Object> event(Map<String, ?> payload) {
    var result = new LinkedHashMap<String, Object>(payload);
    var counter = StatusChangeCounter.process();
    result.put("statusChangeEpoch", counter.epoch());
    result.put("statusChangeCounter", counter.current());
    return result;
  }
}

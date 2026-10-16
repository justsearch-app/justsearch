/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.inference.chat.ChatResidency;
import io.justsearch.telemetry.Telemetry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * The chat residency routes (Spec A step 9).
 *
 * <ul>
 *   <li>{@code POST /api/chat/load-anyway} — the app's "load anyway" for a person (T26). It needs
 *       the same evidence as a person's chat (reconciliation F8/CH1): a body with {@code allowLoad:
 *       true} from the app's own client. It answers at once; the status surface shows the load.
 *   <li>{@code POST /api/debug/chat-pressure} — the dev-only pressure trigger, {@code {"signal":
 *       "EXTERNAL_MEMORY" | "GAME" | "GAME_AND_EXTERNAL_MEMORY" | "NONE", "profile"?: "P16_PLUS" | "P12" | ...}} (the profile is
 *       optional; the game rule depends on it). Available only with {@code -Djustsearch.eval.mode=true}
 *       (the dev run sets it); otherwise 404, as the other synthetic debug routes.
 * </ul>
 */
public final class ChatResidencyController {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int FORBIDDEN = 403;

  private final Supplier<ChatResidency> residency;
  private final Telemetry telemetry;

  public ChatResidencyController(Supplier<ChatResidency> residency, Telemetry telemetry) {
    this.residency = Objects.requireNonNull(residency, "residency");
    this.telemetry = telemetry;
  }

  /** The gate every chat entry shares with these routes. */
  public ChatResidencyGate gate() {
    return new ChatResidencyGate(residency, telemetry);
  }

  /** {@code POST /api/chat/load-anyway}. */
  public void handleLoadAnyway(Context ctx) {
    ChatResidency controller = residency.get();
    if (controller == null) {
      ctx.status(ApiErrorHandler.httpStatusFor(ApiErrorCode.SERVICE_UNAVAILABLE))
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE,
              "Chat is not managed here", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    Map<String, Object> body = readBody(ctx);
    try {
      if (!Boolean.TRUE.equals(body.get("allowLoad"))) {
        // Only the app's chat sends allowLoad; nothing else may override pressure (F8/CH1).
        throw new ChatRefusal(RefusalReason.REFUSED, java.util.Optional.empty(), false);
      }
      CompletableFuture<Void> load =
          controller.loadAnyway(RequestEngineContext.get(ctx)).toCompletableFuture();
      boolean loading = !load.isDone();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("loading", loading);
      ctx.status(loading ? 202 : 200).json(response);
    } catch (ChatRefusal refusal) {
      ApiErrorCode code = ChatResidencyGate.codeFor(refusal.reason());
      int status = refusal.reason() == RefusalReason.REFUSED
          ? FORBIDDEN : ApiErrorHandler.httpStatusFor(code);
      ctx.status(status).json(
          ChatResidencyGate.refusalBody(refusal, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /** {@code POST /api/debug/chat-pressure}: dev-only. */
  public void handleDevPressure(Context ctx) {
    if (!io.justsearch.configuration.EnvRegistry.EVAL_MODE.getBoolean(false)) {
      ctx.status(404).json(Map.of("error", "Not available outside eval mode"));
      return;
    }
    ChatResidency controller = residency.get();
    if (controller == null) {
      ctx.status(503).json(Map.of("error", "Chat is not managed here"));
      return;
    }
    Map<String, Object> body = readBody(ctx);
    Object raw = body.get("signal");
    PressureSignal signal;
    try {
      signal = PressureSignal.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      ctx.status(400).json(Map.of("error", "signal must be NONE | EXTERNAL_MEMORY | GAME | GAME_AND_EXTERNAL_MEMORY"));
      return;
    }
    Profile profile = null;
    if (body.get("profile") != null) {
      try {
        profile = Profile.valueOf(String.valueOf(body.get("profile")).trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException invalid) {
        ctx.status(400).json(Map.of("error", "profile must be P16_PLUS | P12 | P8 | NO_GPU | UNRESOLVED"));
        return;
      }
    }
    // Until Spec E supplies the machine profile, a trial of the game rule names one (O4).
    if (profile != null) controller.setProfile(profile);
    controller.reportPressure(signal);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("signal", controller.reportedPressure().name());
    if (profile != null) response.put("profile", profile.name());
    response.put("unloaded", controller.unloaded().map(detail -> detail.token()).orElse(null));
    ctx.status(200).json(response);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readBody(Context ctx) {
    try {
      String raw = ctx.body();
      if (raw == null || raw.isBlank()) return Map.of();
      Map<String, Object> parsed = MAPPER.readValue(raw, Map.class);
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException malformed) {
      return Map.of();
    }
  }
}

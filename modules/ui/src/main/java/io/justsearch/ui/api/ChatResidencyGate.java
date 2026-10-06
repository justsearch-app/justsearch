/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatResidency;
import io.justsearch.core.context.EngineContext;
import io.justsearch.telemetry.Telemetry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The residency gate of every HTTP chat entry (Spec A step 9, §4.3): before a chat request commits
 * a stream, it asks the chat controller whether chat may load, waits a bounded time for a reload it
 * started, or answers the typed refusal ({@code CHAT_UNAVAILABLE} with {@code retryAfterMs}, the
 * refusal {@code reason} and {@code forceAllowed}).
 *
 * <p>A request is a person's only when its body says {@code allowLoad: true} and it comes from the
 * app's own client (reconciliation F8/CH1); a person waits up to {@link #HUMAN_MAX_WAIT}. Every
 * other chat request is an agent's and waits at most {@link #AGENT_MAX_WAIT}, lowered by a body
 * {@code waitForReadyMs}.
 */
public final class ChatResidencyGate {

  /** How long a person's chat waits for the reload it started before it reads "loading". */
  public static final Duration HUMAN_MAX_WAIT = Duration.ofMinutes(2);

  /** The agent wait cap (reconciliation F8); {@code waitForReadyMs} lowers it. */
  public static final Duration AGENT_MAX_WAIT = Duration.ofSeconds(30);

  /** When a request that found chat still loading may usefully ask again. */
  static final Duration LOADING_RETRY_AFTER = Duration.ofSeconds(5);

  private static final ChatResidencyGate NONE = new ChatResidencyGate(() -> null, null);

  private final Supplier<ChatResidency> residency;
  private final Telemetry telemetry;

  public ChatResidencyGate(Supplier<ChatResidency> residency, Telemetry telemetry) {
    this.residency = Objects.requireNonNull(residency, "residency");
    this.telemetry = telemetry;
  }

  /** A gate that admits everything: no chat controller is installed. */
  public static ChatResidencyGate none() {
    return NONE;
  }

  /**
   * Admits a chat request whose body is {@code body} (may be empty).
   *
   * @return true to go on; false when the response was written
   */
  public boolean admitChat(Context ctx, Map<String, Object> body) {
    EngineContext context = RequestEngineContext.get(ctx);
    boolean allowLoad = body != null && Boolean.TRUE.equals(body.get("allowLoad"));
    DemandKind kind = ChatResidency.chatDemandKind(context.clientKind(), allowLoad);
    Duration wait = kind == DemandKind.HUMAN_CHAT ? HUMAN_MAX_WAIT
        : agentWait(body == null ? null : body.get("waitForReadyMs"));
    return admit(ctx, context, kind, wait);
  }

  /** Admits an agent's request (the OpenAI-compatible proxy), waiting at most {@code maxWait}. */
  public boolean admitAgent(Context ctx, Duration maxWait) {
    return admit(ctx, RequestEngineContext.get(ctx), DemandKind.AGENT_CHAT, maxWait);
  }

  /** The agent wait: {@link #AGENT_MAX_WAIT}, lowered by a non-negative {@code waitForReadyMs}. */
  static Duration agentWait(Object waitForReadyMs) {
    if (waitForReadyMs instanceof Number number && number.longValue() >= 0) {
      Duration asked = Duration.ofMillis(number.longValue());
      return asked.compareTo(AGENT_MAX_WAIT) < 0 ? asked : AGENT_MAX_WAIT;
    }
    return AGENT_MAX_WAIT;
  }

  private boolean admit(Context ctx, EngineContext context, DemandKind kind, Duration maxWait) {
    ChatResidency controller = residency.get();
    if (controller == null) return true;
    CompletableFuture<Void> loaded;
    try {
      loaded = controller.demand(new Demand(kind, context, maxWait)).toCompletableFuture();
    } catch (ChatRefusal refusal) {
      respond(ctx, refusal);
      return false;
    }
    if (loaded.isDone() && !loaded.isCompletedExceptionally()) return true;
    try {
      loaded.get(maxWait.toNanos(), TimeUnit.NANOSECONDS);
      return true;
    } catch (TimeoutException stillLoading) {
      respond(ctx, loading());
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      respond(ctx, loading());
      return false;
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof ChatRefusal refusal) {
        respond(ctx, refusal);
      } else if (cause instanceof ChatActuationRouter.SupersededException) {
        // A later start took the reload over; chat is still loading.
        respond(ctx, loading());
      } else {
        respond(ctx, new ChatRefusal(RefusalReason.FAILED, Optional.empty(), false));
      }
      return false;
    }
  }

  private static ChatRefusal loading() {
    return new ChatRefusal(RefusalReason.LOADING, Optional.of(LOADING_RETRY_AFTER), false);
  }

  private void respond(Context ctx, ChatRefusal refusal) {
    ApiErrorCode code = codeFor(refusal.reason());
    Map<String, Object> body = refusalBody(refusal, telemetry, ApiErrorHandler.routeOf(ctx));
    refusal.retryAfter().filter(ignored -> code.isRetryable()).ifPresent(after ->
        ctx.header("Retry-After", Long.toString(Math.max(1, (after.toMillis() + 999) / 1000))));
    ctx.status(ApiErrorHandler.httpStatusFor(code)).json(body);
  }

  /**
   * The wire code of a refusal (ADR-0050): pressure, the dwell, a self-test and a busy controller
   * are {@code CHAT_UNAVAILABLE}; loading stays {@code AI_STARTING}; off, failed and refused stay
   * {@code AI_OFFLINE}.
   */
  public static ApiErrorCode codeFor(RefusalReason reason) {
    return switch (reason) {
      case GPU_BUSY, GAME_MODE, RELOAD_BACKOFF, SELF_TEST, BUSY, DEVICE_RECOVERING ->
          ApiErrorCode.CHAT_UNAVAILABLE;
      case LOADING -> ApiErrorCode.AI_STARTING;
      case OFF, FAILED, REFUSED -> ApiErrorCode.AI_OFFLINE;
      case ENGINE_STOPPING -> ApiErrorCode.SERVICE_UNAVAILABLE;
    };
  }

  /** The words a person reads for a refusal. */
  static String messageFor(RefusalReason reason) {
    return switch (reason) {
      case GPU_BUSY -> "Chat is paused: another application needs the graphics memory.";
      case GAME_MODE -> "Chat is paused while a game is running.";
      case RELOAD_BACKOFF -> "Chat was paused a moment ago to free graphics memory; it can load"
          + " again shortly.";
      case LOADING -> "Chat is loading.";
      case BUSY -> "Chat is busy; try again shortly.";
      case SELF_TEST -> "Chat is paused while a model test runs.";
      case DEVICE_RECOVERING -> "Chat is paused while the graphics card recovers.";
      case OFF -> "Chat is off.";
      case FAILED -> "Chat could not start.";
      case REFUSED -> "Chat may not load for this request.";
      case ENGINE_STOPPING -> "JustSearch is shutting down.";
    };
  }

  /**
   * The HTTP body of a refusal: the typed error, {@code retryAfterMs} when the code is retryable
   * and the refusal knows when, the refusal {@code reason} and {@code forceAllowed}.
   *
   * @param telemetry records the error code when present
   * @param route the route the error counter is recorded under, or null with no telemetry
   */
  public static Map<String, Object> refusalBody(
      ChatRefusal refusal, Telemetry telemetry, String route) {
    ApiErrorCode code = codeFor(refusal.reason());
    String message = messageFor(refusal.reason());
    Map<String, Object> body = telemetry == null
        ? ApiErrorHandler.toResponse(code, message)
        : ApiErrorHandler.toResponse(code, message, telemetry, route);
    if (code.isRetryable()) {
      refusal.retryAfter().ifPresent(after -> body.put("retryAfterMs", after.toMillis()));
    }
    body.put("reason", refusal.reason().name());
    body.put("forceAllowed", refusal.forceAllowed());
    return body;
  }
}

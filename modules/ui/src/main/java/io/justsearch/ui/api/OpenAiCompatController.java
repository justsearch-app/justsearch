/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI-compatible API surface on JustSearch's loopback API server.
 *
 * <p>Tempdoc 374 alpha.17 R5. Round-7 sandbox showed third-party agents and
 * CLI tools targeting the standard OpenAI shape on JustSearch's documented
 * loopback port (published in the runtime manifest at
 * {@code <dataDir>/runtime/manifest.json#head.apiPort} per tempdoc 501) get
 * an empty body.
 * llama-server itself is on a separate ephemeral port that integrators have
 * to discover from {@code /api/inference/status} — leaking an internal
 * implementation detail.
 *
 * <p>This controller proxies a minimal subset of OpenAI's HTTP API to the
 * running llama-server's port:
 *
 * <ul>
 *   <li>{@code POST /v1/chat/completions} — including SSE streaming
 *   <li>{@code GET /v1/models}
 * </ul>
 *
 * <p>Out of scope: {@code /v1/embeddings} (the embed encoder is in-process in
 * the Worker; no HTTP server hosts it).
 *
 * <p>When llama-server is offline (port unset, connect refused, or hosed) the
 * proxy responds {@code 503 AI_OFFLINE} via the project's
 * {@link ApiErrorHandler} so error shape matches the rest of the API surface.
 */
public final class OpenAiCompatController implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatController.class);

  /**
   * Headers we explicitly do not forward upstream. {@code Host} and
   * {@code Connection} are managed by the JDK HttpClient; the rest are hop-by-hop
   * per RFC 7230 §6.1.
   */
  private static final Set<String> SKIP_REQUEST_HEADERS =
      Set.of(
          "host",
          "connection",
          "keep-alive",
          "transfer-encoding",
          "te",
          "trailer",
          "upgrade",
          "proxy-authenticate",
          "proxy-authorization",
          "content-length");

  /**
   * Headers we do not forward back to the client (Javalin manages
   * {@code Content-Length} / {@code Transfer-Encoding} from the body sink).
   */
  private static final Set<String> SKIP_RESPONSE_HEADERS =
      Set.of(
          "transfer-encoding",
          "connection",
          "keep-alive",
          "content-length");

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  /**
   * Streaming completions can run a long time on slow hardware. JDK HttpClient
   * imposes no read timeout when reading via {@link HttpResponse.BodyHandlers#ofInputStream};
   * the request-level timeout below applies to first-byte. The 30-minute wall
   * is a guard against runaway sessions.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

  private final HttpClient foregroundHttp;
  private final HttpClient backgroundHttp;
  private final io.justsearch.core.execution.EngineExecutorRegistry.Registration foregroundOwner;
  private final io.justsearch.core.execution.EngineExecutorRegistry.Registration backgroundOwner;
  private final IntSupplier llamaServerPortSupplier;
  private final Telemetry telemetry;

  public OpenAiCompatController(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      IntSupplier llamaServerPortSupplier, Telemetry telemetry) {
    this.llamaServerPortSupplier = java.util.Objects.requireNonNull(llamaServerPortSupplier);
    this.telemetry = telemetry;
    var fg = httpOwner(executors, io.justsearch.core.execution.EngineExecutorSpec.Kind.FOREGROUND);
    io.justsearch.core.execution.EngineExecutorRegistry.Registration bg = null;
    HttpClient foreground = null;
    try {
      bg = httpOwner(executors, io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND);
      foreground = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
          .executor(fg.open(Thread.ofPlatform().daemon().name("openai-http-foreground-", 0).factory()))
          .build();
      backgroundHttp = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
          .executor(bg.open(Thread.ofPlatform().daemon().name("openai-http-background-", 0).factory()))
          .build();
    } catch (RuntimeException | Error failure) {
      if (foreground != null) foreground.shutdownNow();
      try { fg.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
      if (bg != null) {
        try { bg.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
      }
      throw failure;
    }
    foregroundHttp = foreground;
    foregroundOwner = fg;
    backgroundOwner = bg;
  }

  private static io.justsearch.core.execution.EngineExecutorRegistry.Registration httpOwner(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      io.justsearch.core.execution.EngineExecutorSpec.Kind kind) {
    var limits = executors.limits(kind);
    return executors.register(new io.justsearch.core.execution.EngineExecutorSpec(
        "head.openai.http." + kind.name().toLowerCase(java.util.Locale.ROOT), kind,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.PLATFORM,
        limits.maxThreads(), limits.maxQueue(), 1));
  }

  /** Test seam: the supplied HTTP client is borrowed, with no hidden executor allocation. */
  OpenAiCompatController(
      HttpClient httpClient, IntSupplier llamaServerPortSupplier, Telemetry telemetry) {
    foregroundHttp = httpClient;
    backgroundHttp = httpClient;
    foregroundOwner = null;
    backgroundOwner = null;
    this.llamaServerPortSupplier = llamaServerPortSupplier;
    this.telemetry = telemetry;
  }

  @Override
  public void close() {
    if (foregroundOwner == null) return;
    foregroundHttp.shutdownNow();
    backgroundHttp.shutdownNow();
    try { foregroundOwner.close(); } finally { backgroundOwner.close(); }
  }

  public void handleChatCompletions(Context ctx) {
    proxy(ctx, "/v1/chat/completions");
  }

  public void handleModels(Context ctx) {
    proxy(ctx, "/v1/models");
  }

  private void proxy(Context ctx, String path) {
    int port = llamaServerPortSupplier.getAsInt();
    if (port <= 0) {
      respondOffline(ctx, "llama-server not running (port unset)");
      return;
    }

    URI upstream = URI.create("http://127.0.0.1:" + port + path);
    HttpRequest.Builder rb =
        HttpRequest.newBuilder(upstream).timeout(REQUEST_TIMEOUT);

    // Copy method + body (always — `bodyAsBytes()` returns empty for GET).
    byte[] body = ctx.bodyAsBytes();
    String method = ctx.method().name();
    if (body == null || body.length == 0) {
      rb.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      rb.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    }

    // Forward request headers, dropping hop-by-hop and a couple Javalin
    // already manages.
    for (String name : ctx.headerMap().keySet()) {
      if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
        continue;
      }
      String value = ctx.header(name);
      if (value == null) continue;
      try {
        rb.header(name, value);
      } catch (IllegalArgumentException ex) {
        // JDK HttpClient rejects some restricted headers (e.g. Host). Skip.
        log.debug("Skipped restricted upstream header {}", name);
      }
    }

    HttpResponse<InputStream> response;
    try {
      HttpClient client = RequestEngineContext.get(ctx).urgency()
          == io.justsearch.core.context.EngineContext.Urgency.BACKGROUND
          ? backgroundHttp : foregroundHttp;
      response = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (ConnectException ce) {
      respondOffline(ctx, "llama-server connect refused on port " + port);
      return;
    } catch (java.io.IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("OpenAI-compat proxy failed: {} {} → {}: {}", method, path, upstream, ex.toString());
      respondOffline(ctx, "llama-server proxy failed: " + ex.getMessage());
      return;
    }

    // Status + response headers (skip hop-by-hop).
    ctx.status(response.statusCode());
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return;
              }
              for (String v : values) {
                ctx.header(name, v);
              }
            });

    // Body: stream the upstream InputStream straight to the client. This
    // works for both buffered JSON and SSE/event-stream — the JDK
    // HttpClient's `ofInputStream` body handler returns a stream that
    // produces bytes as they arrive on the wire.
    ctx.result(response.body());
  }

  private void respondOffline(Context ctx, String detail) {
    // Use SERVICE_UNAVAILABLE (TRANSIENT → 503) rather than AI_OFFLINE
    // (PERMANENT → 500). The OpenAI ecosystem expects 503 for "upstream
    // not running, retry later"; AI_OFFLINE is reserved for the head's
    // own /api/inference status reporting.
    ApiErrorCode code = ApiErrorCode.SERVICE_UNAVAILABLE;
    int httpStatus = ApiErrorHandler.httpStatusFor(code);
    String route = ApiErrorHandler.routeOf(ctx);
    var payload =
        ApiErrorHandler.toResponse(
            code, detail, telemetry, route);
    ctx.status(httpStatus).json(payload);
  }

}

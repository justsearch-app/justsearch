package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.justsearch.telemetry.Telemetry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 374 alpha.17 R5 regression coverage for {@link OpenAiCompatController}.
 *
 * <p>Round-7 sandbox: {@code POST :8080/v1/chat/completions} returned an
 * empty body on JustSearch's documented loopback API server because no
 * handler was registered. Third-party agents using the standard OpenAI
 * shape against the published port had to discover the internal
 * llama-server port from {@code /api/inference/status} to get a working
 * response. This controller closes that gap by proxying.
 */
@DisplayName("OpenAiCompatController (tempdoc 374 alpha.17 R5)")
class OpenAiCompatControllerTest {

  @Test
  @DisplayName("port unset (== 0) → 503 SERVICE_UNAVAILABLE without contacting upstream")
  void portUnset_returnsAiOffline() {
    HttpClient httpClient = mock(HttpClient.class);
    Telemetry telemetry = null; // ApiErrorHandler.toResponse handles null telemetry.
    OpenAiCompatController ctrl =
        new OpenAiCompatController(httpClient, () -> 0, telemetry);

    Context ctx = mockContext("POST", "/v1/chat/completions");

    ctrl.handleChatCompletions(ctx);

    verify(ctx).status(503);
    var capturedBody = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(capturedBody.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) capturedBody.getValue();
    assertNotNull(body);
    assertEquals("SERVICE_UNAVAILABLE", body.get("errorCode"));
  }

  @Test
  @DisplayName("upstream connect refused → 503 SERVICE_UNAVAILABLE")
  void upstreamConnectRefused_returnsAiOffline() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new ConnectException("connect refused"));

    OpenAiCompatController ctrl =
        new OpenAiCompatController(httpClient, () -> 8081, null);

    Context ctx = mockContext("POST", "/v1/chat/completions");

    ctrl.handleChatCompletions(ctx);

    verify(ctx).status(503);
    var capturedBody = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(capturedBody.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) capturedBody.getValue();
    assertEquals("SERVICE_UNAVAILABLE", body.get("errorCode"));
  }

  @Test
  @DisplayName("upstream success → forwards status, headers (filtered), and body stream")
  void upstreamOk_streamsBodyToClient() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> upstreamResp = mock(HttpResponse.class);
    when(upstreamResp.statusCode()).thenReturn(200);
    when(upstreamResp.body())
        .thenReturn(
            new ByteArrayInputStream(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes()));
    when(upstreamResp.headers())
        .thenReturn(
            java.net.http.HttpHeaders.of(
                Map.of(
                    "content-type", java.util.List.of("application/json"),
                    "transfer-encoding", java.util.List.of("chunked")),
                ALLOW_ALL_HEADERS));
    // Mockito's generic inference can't bind HttpResponse<InputStream> through
    // the wildcard; doReturn bypasses the strict type check.
    org.mockito.Mockito.doReturn(upstreamResp).when(httpClient).send(any(), any());

    OpenAiCompatController ctrl =
        new OpenAiCompatController(httpClient, () -> 8081, null);

    Context ctx = mockContext("POST", "/v1/chat/completions");

    ctrl.handleChatCompletions(ctx);

    verify(ctx).status(200);
    // Hop-by-hop transfer-encoding must NOT be forwarded; content-type must.
    verify(ctx).header("content-type", "application/json");
    verify(ctx, org.mockito.Mockito.never()).header("transfer-encoding", "chunked");
    verify(ctx).result(any(InputStream.class));
  }

  /** {@code java.net.http.HttpHeaders.of} requires a header-name filter. */
  private static final java.util.function.BiPredicate<String, String> ALLOW_ALL_HEADERS =
      (n, v) -> true;

  private static Context mockContext(String method, String path) {
    Context ctx = mock(Context.class);
    when(ctx.method()).thenReturn(HandlerType.valueOf(method));
    org.mockito.Mockito.lenient().when(ctx.path()).thenReturn(path);
    org.mockito.Mockito.lenient().when(ctx.attribute(RequestEngineContext.ATTRIBUTE))
        .thenReturn(TestRequestContexts.browser());
    when(ctx.bodyAsBytes()).thenReturn(new byte[0]);
    when(ctx.headerMap()).thenReturn(Map.of("content-type", "application/json"));
    when(ctx.header("content-type")).thenReturn("application/json");
    // ApiErrorHandler.routeOf may call ctx.endpointHandlerPath().
    when(ctx.endpointHandlerPath()).thenReturn(path);
    when(ctx.status(any(int.class))).thenReturn(ctx);
    return ctx;
  }

  /** Suppress the unused-import warning for {@link Predicate} / {@link Optional}. */
  @SuppressWarnings("unused")
  private void unused(Predicate<String> p, Optional<String> o, Map<?, ?> m) {
    assertTrue(true);
  }
}

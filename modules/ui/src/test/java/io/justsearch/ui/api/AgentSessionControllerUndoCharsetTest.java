/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentRunQueries;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 875 open item — {@code POST /api/chat/agent/undo} mojibaked non-ASCII in its JSON
 * response (the undo summary's em-dash arrived as {@code â€"}) while the SSE plane stayed clean.
 *
 * <p>The bytes were never wrong: {@code SseWriter} declares {@code text/event-stream; charset=utf-8}
 * and every {@code ctx.json(...)} route declared bare {@code application/json}, so a client that
 * honours the response charset (defaulting to ISO-8859-1 when the parameter is absent) decoded UTF-8
 * bytes as Latin-1 — the exact shape of {@code â€"}. The fault is one missing media-type parameter on
 * the JSON plane, which is why this test asserts BOTH halves: the declared charset AND the bytes.
 *
 * <p>The fix is the one chokepoint every JSON route already passes through
 * ({@link JsonResponseCharset}), so the assertions below are written against a route registered the
 * same way {@code LocalApiServer} registers it.
 */
class AgentSessionControllerUndoCharsetTest {

  /** An em-dash and a u-umlaut — the two characters the live 868 §I campaign saw mangled. */
  private static final String NON_ASCII_SUMMARY =
      "Undo completed: 2 operations reversed — 1 changed since the agent acted (müller.txt)";

  private Javalin app;
  private int port;
  private HttpClient client;

  @BeforeEach
  void setUp() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  /** The read surface the controller talks to; only {@code undoOperation} is exercised. */
  private static AgentRunQueries queriesReturning(String message) {
    return new AgentRunQueries() {
      @Override
      public List<Operation> availableOperations() {
        return List.of();
      }

      @Override
      public List<Operation> offeredOperations() {
        return List.of();
      }

      @Override
      public OperationResult undoOperation(String toolName, String executionId, EngineContext engineContext) {
        return OperationResult.success(message, executionId);
      }

      @Override
      public List<java.util.Map<String, Object>> operationHistory(int limit) {
        return List.of(java.util.Map.of("batchId", "b-1", "explanation", message));
      }
    };
  }

  private void startServer(String message) {
    startServer(message, true);
  }

  private void startServer(String message, boolean installCharsetFix) {
    AgentSessionController controller =
        new AgentSessionController(() -> queriesReturning(message), null);
    app =
        Javalin.create(
            cfg -> {
              cfg.showJavalinBanner = false;
              cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });
    if (installCharsetFix) {
      JsonResponseCharset.install(app);
    }
    app.post("/api/chat/agent/undo", controller::handleUndo);
    app.get("/api/chat/agent/history", controller::handleHistory);
    app.start("127.0.0.1", 0);
    port = app.port();
  }

  private HttpResponse<byte[]> post(String path, String body) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  @DisplayName("the undo response declares UTF-8 and carries the em-dash as UTF-8 bytes")
  void undoResponseIsUtf8() throws Exception {
    startServer(NON_ASCII_SUMMARY);

    HttpResponse<byte[]> resp =
        post("/api/chat/agent/undo", "{\"toolName\":\"core_file_operations\",\"executionId\":\"b-1\"}");

    assertEquals(200, resp.statusCode());
    String contentType = resp.headers().firstValue("Content-Type").orElse("");
    assertTrue(
        contentType.toLowerCase(Locale.ROOT).contains("charset=utf-8"),
        "the JSON response must DECLARE its charset, or a header-honouring client decodes UTF-8 "
            + "bytes as Latin-1 (the 'a-circumflex euro' mojibake): " + contentType);

    // The bytes themselves: the em-dash must be its 3-byte UTF-8 sequence, and decoding the body
    // as UTF-8 must round-trip the exact summary the handler returned.
    byte[] body = resp.body();
    assertTrue(
        indexOf(body, "—".getBytes(StandardCharsets.UTF_8)) >= 0,
        "the em-dash must be on the wire as UTF-8 bytes, not '?' (a lossy Latin-1 encode)");
    String decoded = new String(body, StandardCharsets.UTF_8);
    assertTrue(decoded.contains(NON_ASCII_SUMMARY), "round-trips through UTF-8: " + decoded);
  }

  /**
   * The same fault, on a sibling route in the same controller — {@code ctx.json} is the shared path,
   * so a per-route patch would have left every other JSON route mojibaking. This pins that the fix
   * covers the plane, not one handler.
   */
  @Test
  @DisplayName("a sibling JSON route in the same controller declares UTF-8 too")
  void siblingJsonRouteIsUtf8() throws Exception {
    startServer(NON_ASCII_SUMMARY);

    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/chat/agent/history"))
            .GET()
            .build();
    HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());

    assertEquals(200, resp.statusCode());
    assertTrue(
        resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT)
            .contains("charset=utf-8"),
        "every ctx.json route shares the fault and therefore the fix");
    assertTrue(
        new String(resp.body(), StandardCharsets.UTF_8).contains(NON_ASCII_SUMMARY),
        "the history projection round-trips through UTF-8 as well");
  }

  /**
   * The falsifier for the two tests above. Neither proves anything unless the SAME route, wired the
   * SAME way minus {@link JsonResponseCharset}, actually omits the charset — otherwise Jetty (which
   * does append {@code charset=utf-8} to some media types on its own) could be supplying it and the
   * greens would be measuring the container, not the fix.
   *
   * <p>It also pins the shape of the original bug: the bytes were already correct UTF-8 even
   * unfixed. That is what makes the mojibake a LABELLING defect — and what makes relabelling, not
   * re-encoding, the correct fix.
   */
  @Test
  @DisplayName("without the fix the same route omits the charset (so the greens above mean something)")
  void withoutTheFixTheCharsetIsAbsent() throws Exception {
    startServer(NON_ASCII_SUMMARY, false);

    HttpResponse<byte[]> resp =
        post("/api/chat/agent/undo", "{\"toolName\":\"core_file_operations\",\"executionId\":\"b-1\"}");

    assertEquals(200, resp.statusCode());
    String contentType = resp.headers().firstValue("Content-Type").orElse("");
    assertTrue(contentType.toLowerCase(Locale.ROOT).contains("application/json"), contentType);
    assertFalse(
        contentType.toLowerCase(Locale.ROOT).contains("charset="),
        "unfixed, Javalin's ctx.json declares a bare media type — this is the defect: " + contentType);
    assertTrue(
        indexOf(resp.body(), "—".getBytes(StandardCharsets.UTF_8)) >= 0,
        "…while the bytes were always correct UTF-8, so the fault is the label, not the encoding");
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}

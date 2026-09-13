package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService.AiUsage;
import io.justsearch.app.api.OnlineAiService.VisionCompletionResult;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.Mode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OnlineModeOpsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Tempdoc 881 (review §G finding 12) — an explicit JSON {@code null} finish_reason must read back
   * as absent, not as the four-character string "null".
   *
   * <p>{@code NullNode.asText()} returns "null", and llama-server puts {@code "finish_reason": null}
   * on every non-terminal streaming chunk. That was inert while nothing consumed the value; 881 puts
   * it into a sentence shown to the user, where a stream ending without a terminal reason would
   * claim "finish_reason=null" instead of admitting the runtime reported nothing.
   */
  @Test
  @DisplayName("finishReasonOf: explicit JSON null reads as absent, not as the string \"null\"")
  void finishReasonOfDistinguishesJsonNullFromAValue() {
    JsonNode nullChoice = MAPPER.readTree("{\"finish_reason\":null}");
    JsonNode missing = MAPPER.readTree("{}");
    JsonNode stop = MAPPER.readTree("{\"finish_reason\":\"stop\"}");

    assertNull(OnlineModeOps.finishReasonOf(nullChoice), "explicit JSON null is NOT a finish reason");
    assertNull(OnlineModeOps.finishReasonOf(missing));
    assertNull(OnlineModeOps.finishReasonOf(null));
    assertEquals("stop", OnlineModeOps.finishReasonOf(stop), "a real reason still comes through");
  }

  private HttpServer server;
  private int port;
  private AtomicReference<Mode> mode;
  private AtomicReference<String> lastKnownModelId;
  private OnlineModeOps ops;

  @BeforeEach
  void setUp() throws Exception {
    mode = new AtomicReference<>(Mode.ONLINE);
    lastKnownModelId = new AtomicReference<>("test-model");

    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] requestBody = exchange.getRequestBody().readAllBytes();
          String requestStr = new String(requestBody, StandardCharsets.UTF_8);

          if (requestStr.contains("\"stream\":true") || requestStr.contains("\"stream\": true")) {
            // Streaming response
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var os = exchange.getResponseBody();
            os.write(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(
                ("data: {\"choices\":[],\"usage\":"
                        + "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
          } else {
            // Non-streaming response
            byte[] body =
                "{\"choices\":[{\"message\":{\"content\":\"Hello from LLM\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          }
        });

    server.start();
    port = server.getAddress().getPort();

    ops =
        new OnlineModeOps(new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
            HttpClient.newHttpClient(),
            MAPPER,
            mode::get,
            () -> port,
            lastKnownModelId::get,
            () -> "default-model.gguf");
  }

  @AfterEach
  void tearDown() {
    ops.shutdown();
    server.stop(0);
  }

  // ==================== formatContextAsNumberedPassages ====================

  @Test
  void formatContextAsNumberedPassages_nullReturnsEmpty() {
    assertEquals("", OnlineModeOps.formatContextAsNumberedPassages(null));
  }

  @Test
  void formatContextAsNumberedPassages_singlePassageWithSource() {
    String input = "[1] doc.pdf\nSome content";
    String result = OnlineModeOps.formatContextAsNumberedPassages(input);
    assertEquals("<passage id=\"1\" source=\"doc.pdf\">\nSome content\n</passage>", result);
  }

  @Test
  void formatContextAsNumberedPassages_multiplePassages() {
    String input =
        "[1] first.pdf\nFirst content"
            + DocumentService.SECTION_SEPARATOR
            + "[2] second.txt\nSecond content";
    String result = OnlineModeOps.formatContextAsNumberedPassages(input);
    String expected =
        "<passage id=\"1\" source=\"first.pdf\">\nFirst content\n</passage>"
            + "\n\n"
            + "<passage id=\"2\" source=\"second.txt\">\nSecond content\n</passage>";
    assertEquals(expected, result);
  }

  @Test
  @DisplayName("passage id is the header's number, not this loop's running counter")
  void formatContextAsNumberedPassages_keysPassageIdToHeaderNumber() {
    // Non-sequential ordinals are the only way to tell the two apart: a running counter would
    // renumber these 1 and 2 and silently break the model's [n] -> sources[n-1] mapping
    // (tempdoc 822 §3a). ContextBudgeterTest pins the emitter side of the same "[n] label\n"
    // shape — app-api/app-inference cannot depend on :modules:indexing, so it is mirrored, as
    // SECTION_SEPARATOR already is.
    String input =
        "[3] third.pdf\nThird content"
            + DocumentService.SECTION_SEPARATOR
            + "[7] seventh.txt\nSeventh content";

    String result = OnlineModeOps.formatContextAsNumberedPassages(input);

    assertEquals(
        "<passage id=\"3\" source=\"third.pdf\">\nThird content\n</passage>"
            + "\n\n"
            + "<passage id=\"7\" source=\"seventh.txt\">\nSeventh content\n</passage>",
        result);
  }

  @Test
  @DisplayName("a label containing brackets or a dash survives the header parse")
  void formatContextAsNumberedPassages_labelWithPunctuation() {
    String label = "TechCrunch — \"Twitch [ends] 70/30 split\"";
    String result =
        OnlineModeOps.formatContextAsNumberedPassages("[2] " + label + "\nBody text");
    assertEquals("<passage id=\"2\" source=\"" + label + "\">\nBody text\n</passage>", result);
  }

  @Test
  @DisplayName("a section whose header does not parse falls back to the running ordinal")
  void formatContextAsNumberedPassages_unparseableHeaderFallsBack() {
    String input =
        "[1] doc.pdf\nFirst content"
            + DocumentService.SECTION_SEPARATOR
            + "no header at all";
    String result = OnlineModeOps.formatContextAsNumberedPassages(input);
    assertEquals(
        "<passage id=\"1\" source=\"doc.pdf\">\nFirst content\n</passage>"
            + "\n\n"
            + "<passage id=\"2\" source=\"unknown\">\nno header at all\n</passage>",
        result);
  }

  // ==================== extractUsageFromChatChunk ====================

  @Test
  void extractUsageFromChatChunk_parsesTokenCounts() throws Exception {
    JsonNode root =
        MAPPER.readTree(
            "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");
    AiUsage usage = OnlineModeOps.extractUsageFromChatChunk(root);
    assertNotNull(usage);
    assertEquals(10, usage.promptTokens());
    assertEquals(5, usage.completionTokens());
    assertEquals(15, usage.totalTokens());
  }

  @Test
  void extractUsageFromChatChunk_returnsNullWhenNoUsage() throws Exception {
    JsonNode root = MAPPER.readTree("{\"choices\":[]}");
    assertNull(OnlineModeOps.extractUsageFromChatChunk(root));
  }

  @Test
  void extractUsageFromChatChunk_returnsNullForNullInput() {
    assertNull(OnlineModeOps.extractUsageFromChatChunk(null));
  }

  // ==================== chatCompletion ====================

  @Test
  void chatCompletion_happyPath() throws Exception {
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "Say hello"));

    String result = ops.chatCompletion(messages, 100).get(5, TimeUnit.SECONDS);
    assertEquals("Hello from LLM", result);
  }

  @Test
  void chatCompletion_serverErrorThrows() throws Exception {
    // Replace server context with one that returns 500
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "Say hello"));

    CompletableFuture<String> future = ops.chatCompletion(messages, 100);
    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    assertNotNull(ex.getCause());
    // sendChatRequest wraps the 500 error as: RuntimeException("Chat request failed",
    //   RuntimeException("Server returned status 500: ..."))
    Throwable root = ex.getCause();
    boolean found500 = false;
    while (root != null) {
      if (root.getMessage() != null && root.getMessage().contains("500")) {
        found500 = true;
        break;
      }
      root = root.getCause();
    }
    assertTrue(found500, "Expected cause chain to contain status code 500, got: " + ex.getCause());
  }

  @Test
  void chatCompletion_notOnlineThrows() {
    mode.set(Mode.OFFLINE);

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "Say hello"));

    assertThrows(IllegalStateException.class, () -> ops.chatCompletion(messages, 100));
  }

  // ==================== think-tag stripping (non-streaming) ====================

  @Test
  void chatCompletion_stripsThinkTags() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"<think>internal reasoning</think>Clean answer\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    String result = ops.chatCompletion(messages, 100).get(5, TimeUnit.SECONDS);
    assertEquals("Clean answer", result, "Should strip <think> tags from non-streaming response");
  }

  @Test
  void chatCompletion_stripsMultipleThinkTags() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body =
              ("{\"choices\":[{\"message\":{\"content\":"
                  + "\"<think>first thought</think>Part 1 <think>second thought</think>Part 2\"}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    String result = ops.chatCompletion(messages, 100).get(5, TimeUnit.SECONDS);
    assertEquals("Part 1 Part 2", result,
        "Should strip all <think> tags from non-streaming response");
  }

  // ==================== SamplingParams injection ====================

  @Test
  void chatCompletion_injectsSamplingParams() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    ops.chatCompletion(messages, 100, SamplingParams.THINKING).get(5, TimeUnit.SECONDS);

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertEquals(0.7, requestJson.path("temperature").asDouble(), 0.001,
        "THINKING preset should inject temperature=0.7 (Qwen3.5)");
    assertEquals(0.8, requestJson.path("top_p").asDouble(), 0.001,
        "THINKING preset should inject top_p=0.8 (Qwen3.5)");
  }

  @Test
  void chatCompletion_deterministicPresetSuppressesThinkingOnTheWire() throws Exception {
    // Tempdoc 835 §10f — the auxiliary-call guarantee, asserted where it is actually delivered:
    // in the request body llama-server sees. DETERMINISTIC is what query rewrite, query
    // understanding, filter normalization, context sufficiency and query expansion all pass; with
    // the server-wide budget on, an absent kwarg means every one of them thinks.
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    ops.chatCompletion(
            List.of(Map.of("role", "user", "content", "test")), 100, SamplingParams.DETERMINISTIC)
        .get(5, TimeUnit.SECONDS);

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertFalse(
        requestJson.path("chat_template_kwargs").path("enable_thinking").asBoolean(true),
        "DETERMINISTIC must send chat_template_kwargs.enable_thinking=false: " + capturedBody.get());
  }

  @Test
  void streamChat_deterministicPresetSuppressesThinkingOnTheWire() throws Exception {
    // The same guarantee on the streaming transport — query expansion (KnowledgeSearchEngine) and
    // section summarize (HierarchicalShapeRunner) reach llama-server this way, not via chatCompletion.
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch done = new CountDownLatch(1);
    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")), 100,
        chunk -> {}, null, fr -> done.countDown(), t -> done.countDown(),
        SamplingParams.DETERMINISTIC);

    assertTrue(done.await(5, TimeUnit.SECONDS));
    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertFalse(
        requestJson.path("chat_template_kwargs").path("enable_thinking").asBoolean(true),
        "DETERMINISTIC must suppress thinking on the streaming path too: " + capturedBody.get());
  }

  @Test
  void streamChat_serverErrorBodyReachesTheCaller() throws Exception {
    // Tempdoc 835 §10f: the streaming paths used to pass null as the error body, so a context
    // overflow — llama-server's own "exceeds the available context size" explanation — surfaced as
    // a bare "Server returned status 400" with "check llama-server logs" as the only lead.
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body =
              ("{\"error\":{\"code\":400,\"message\":\"request (5878 tokens) exceeds the available"
                      + " context size (4096 tokens), try increasing it\"}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(400, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")), 100,
        chunk -> {},
        null,
        fr -> done.countDown(),
        t -> {
          error.set(t);
          done.countDown();
        },
        SamplingParams.DETERMINISTIC);

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertNotNull(error.get());
    assertTrue(
        error.get() instanceof LlmServerException lse && lse.httpStatus() == 400,
        "expected a typed 400: " + error.get());
    assertTrue(
        error.get().getMessage().contains("exceeds the available context size"),
        "the server's explanation must survive to the caller: " + error.get().getMessage());
  }

  @Test
  void chatCompletion_omitsSamplingWhenNull() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    ops.chatCompletion(messages, 100).get(5, TimeUnit.SECONDS);

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertTrue(requestJson.path("temperature").isMissingNode(),
        "Null sampling should not include temperature");
    assertTrue(requestJson.path("top_p").isMissingNode(),
        "Null sampling should not include top_p");
  }

  @Test
  void streamChatWithTools_injectsSamplingParams() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.DETERMINISTIC);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertEquals(0.1, requestJson.path("temperature").asDouble(), 0.001,
        "DETERMINISTIC preset should inject temperature=0.1");
    assertEquals(0.9, requestJson.path("top_p").asDouble(), 0.001,
        "DETERMINISTIC preset should inject top_p=0.9");
  }

  /**
   * Lane F PR 0b — the seed half of the agent chat request's optional {@code sampling} override.
   * Asserted here, in the body llama-server actually receives, because that is the only place the
   * pin can be checked; a {@code SamplingParams} carrying a seed that this transport dropped would
   * pass every test upstream of it and still produce an unpinned completion.
   */
  @Test
  void streamChatWithTools_injectsSeedWhenSetAndOmitsItWhenNull() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respondWithOneSseChunk(exchange);
        });

    List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "test"));

    CountDownLatch seeded = new CountDownLatch(1);
    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> seeded.countDown(), t -> {},
        SamplingParams.AGENT.withSeed(20260907L));
    assertTrue(seeded.await(5, TimeUnit.SECONDS));

    JsonNode withSeed = MAPPER.readTree(capturedBody.get());
    assertEquals(20260907L, withSeed.path("seed").asLong(),
        "a pinned seed must reach llama-server");

    CountDownLatch unseeded = new CountDownLatch(1);
    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> unseeded.countDown(), t -> {},
        SamplingParams.AGENT);
    assertTrue(unseeded.await(5, TimeUnit.SECONDS));

    JsonNode withoutSeed = MAPPER.readTree(capturedBody.get());
    assertTrue(withoutSeed.path("seed").isMissingNode(),
        "a null seed must OMIT the field, so the body is byte-identical to before the component"
            + " existed — not send an explicit null or a 0");
  }

  /** Writes one SSE content chunk and terminates the stream. */
  private static void respondWithOneSseChunk(com.sun.net.httpserver.HttpExchange exchange)
      throws java.io.IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    var os = exchange.getResponseBody();
    os.write(
        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
    os.flush();
    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    os.flush();
    os.close();
  }

  @Test
  void streamChatWithTools_reroutesInlineThinkTagsSplitAcrossFrames() throws Exception {
    // Tempdoc 835 §5.3 — the wiring, not just the filter: a build that leaks inline reasoning emits
    // the tag across SSE frame boundaries ("<thi" + "nk>"), which a per-chunk replaceAll passes
    // through verbatim. Answer text must stay clean and the thinking must reach the reasoning
    // channel, so a leaking build behaves like a reasoning_content one.
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          for (String piece : List.of("Answer: <thi", "nk>hidden", " thought</thi", "nk>42")) {
            os.write(
                ("data: {\"choices\":[{\"delta\":{\"content\":\"" + piece + "\"}}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    StringBuilder content = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    CountDownLatch completeLatch = new CountDownLatch(1);

    ops.streamChatWithTools(
        List.of(Map.of("role", "user", "content", "test")), List.of(), 100,
        content::append, toolDelta -> {}, reasoning::append, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.DETERMINISTIC);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    assertEquals("Answer: 42", content.toString(), "think markup must not reach answer text");
    assertEquals("hidden thought", reasoning.toString(), "thinking must be rerouted, not deleted");
  }

  @Test
  void streamChatWithTools_injectsToolChoice() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.AGENT.withToolChoice("required"));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertEquals("required", requestJson.path("tool_choice").asText(),
        "tool_choice=required should appear in request body");
  }

  @Test
  void streamChatWithTools_omitsToolChoiceWhenNull() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.AGENT);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertTrue(requestJson.path("tool_choice").isMissingNode(),
        "tool_choice should be absent when SamplingParams.toolChoice is null");
  }

  @Test
  void streamChatWithTools_injectsGrammar() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.AGENT.withGrammar("root ::= \"ok\""));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertEquals("root ::= \"ok\"", requestJson.path("grammar").asText(),
        "grammar should appear in request body when set");
  }

  @Test
  void streamChatWithTools_injectsResponseFormat() throws Exception {
    // Tempdoc 569 Phase 5 — the STREAMING path (the one the conversation engine uses) must forward
    // response_format so a schema-constrained shape (core.extract) is GBNF-enforced server-side. This
    // path previously dropped it (only the non-streaming sendChatRequest applied it) — found by live
    // verification; iteration-1 was not actually schema-constrained.
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    Map<String, Object> rf =
        Map.of("type", "json_object", "schema", Map.of("type", "object"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.DETERMINISTIC.withResponseFormat(rf));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertEquals("json_object", requestJson.path("response_format").path("type").asText(),
        "response_format should appear in the streaming request body when set");
    assertEquals("object",
        requestJson.path("response_format").path("schema").path("type").asText(),
        "the carried schema rides on response_format.schema");
  }

  @Test
  void streamChatWithTools_responseFormatTakesPrecedenceOverGrammar() throws Exception {
    // response_format wins over a raw grammar (mirrors the non-streaming precedence) — only one of the
    // two output-constraint keys is emitted so llama-server is never asked to honor both.
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    Map<String, Object> rf =
        Map.of("type", "json_object", "schema", Map.of("type", "object"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.DETERMINISTIC.withGrammar("root ::= \"ok\"").withResponseFormat(rf));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertEquals("json_object", requestJson.path("response_format").path("type").asText(),
        "response_format must be emitted");
    assertTrue(requestJson.path("grammar").isMissingNode(),
        "grammar must be suppressed when response_format is present (precedence)");
  }

  @Test
  void streamChatWithTools_omitsGrammarWhenNull() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.AGENT);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertTrue(requestJson.path("grammar").isMissingNode(),
        "grammar should be absent when SamplingParams.grammar is null");
  }

  @Test
  void streamChatWithTools_injectsChatTemplateKwargsWhenEnableThinkingFalse() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.AGENT.withEnableThinking(false));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertFalse(
        requestJson.path("chat_template_kwargs").path("enable_thinking").asBoolean(true),
        "chat_template_kwargs.enable_thinking should be false when set to false");
    assertTrue(
        requestJson.path("chat_template_kwargs").path("enable_thinking").isBoolean(),
        "enable_thinking must be a JSON boolean (not a string) for llama-server .dump() parsing");
  }

  @Test
  void streamChatWithTools_omitsChatTemplateKwargsWhenEnableThinkingNull() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));

    ops.streamChatWithTools(
        messages, List.of(), 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.AGENT);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertTrue(requestJson.path("chat_template_kwargs").isMissingNode(),
        "chat_template_kwargs should be absent when enableThinking is null");
  }

  @Test
  void streamChatWithTools_suppressesGrammarWhenToolsPresentButEmitsChatTemplateKwargs()
      throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
              StandardCharsets.UTF_8));
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    // Simulate the E0a production path: grammar + toolChoice + enableThinking=false,
    // with a non-empty tools list (Organizer always has ingest_files in tools).
    List<Map<String, Object>> tools = List.of(Map.of("name", "ingest_files"));

    ops.streamChatWithTools(
        messages, tools, 100,
        chunk -> {}, toolDelta -> {}, reasoning -> {}, null,
        fr -> completeLatch.countDown(), t -> {},
        SamplingParams.AGENT
            .withGrammar("root ::= \"ok\"")
            .withToolChoice("required")
            .withEnableThinking(false));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    JsonNode requestJson = MAPPER.readTree(capturedBody.get());
    assertTrue(requestJson.path("grammar").isMissingNode(),
        "grammar must be suppressed when tools are non-empty");
    assertFalse(
        requestJson.path("chat_template_kwargs").path("enable_thinking").asBoolean(true),
        "chat_template_kwargs.enable_thinking must still be emitted even when grammar is suppressed");
    assertTrue(
        requestJson.path("chat_template_kwargs").path("enable_thinking").isBoolean(),
        "enable_thinking must be a JSON boolean");
  }

  // ==================== streamChat ====================

  @Test
  void streamChat_deliversChunksAndCallsOnComplete() throws Exception {
    List<String> chunks = new CopyOnWriteArrayList<>();
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "Stream test"));

    ops.streamChat(
        messages,
        100,
        chunks::add,
        fr -> completeLatch.countDown(),
        error::set);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "onComplete should be called");
    assertNull(error.get(), "No error should have occurred");
    assertEquals(List.of("Hello", " world"), chunks);
  }

  @Test
  void streamChat_extractsUsage() throws Exception {
    List<String> chunks = new CopyOnWriteArrayList<>();
    AtomicReference<AiUsage> capturedUsage = new AtomicReference<>();
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "Stream with usage"));

    ops.streamChat(
        messages,
        100,
        chunks::add,
        capturedUsage::set,
        fr -> completeLatch.countDown(),
        error::set);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "onComplete should be called");
    assertNull(error.get(), "No error should have occurred");
    assertEquals(List.of("Hello", " world"), chunks);

    AiUsage usage = capturedUsage.get();
    assertNotNull(usage, "Usage should have been extracted from the final chunk");
    assertEquals(10, usage.promptTokens());
    assertEquals(5, usage.completionTokens());
    assertEquals(15, usage.totalTokens());
  }

  // ==================== streamChatWithTools ====================

  @Test
  void streamChatWithTools_extractsReasoningContent() throws Exception {
    // Replace handler with one that sends interleaved reasoning + content chunks
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"The answer\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\" about this\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\" is 42\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    List<String> contentChunks = new CopyOnWriteArrayList<>();
    List<String> reasoningChunks = new CopyOnWriteArrayList<>();
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "Test reasoning"));

    ops.streamChatWithTools(
        messages,
        List.of(),
        100,
        contentChunks::add,
        toolDelta -> {},
        reasoningChunks::add,
        null,
        fr -> completeLatch.countDown(),
        error::set);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "onComplete should be called");
    assertNull(error.get(), "No error should have occurred");
    assertEquals(List.of("Let me think", " about this"), reasoningChunks);
    assertEquals(List.of("The answer", " is 42"), contentChunks);
  }

  // ==================== visionCompletion ====================

  @Test
  void visionCompletion_sendsBase64EncodedImage() throws Exception {
    // Replace the handler to capture the request body for verification
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] requestBody = exchange.getRequestBody().readAllBytes();
          capturedBody.set(new String(requestBody, StandardCharsets.UTF_8));

          byte[] responseBody =
              "{\"choices\":[{\"message\":{\"content\":\"I see an image\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    byte[] imageBytes = {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic bytes
    String result = ops.visionCompletion("Describe this image", imageBytes, 200)
        .get(5, TimeUnit.SECONDS);

    assertEquals("I see an image", result);

    String body = capturedBody.get();
    assertNotNull(body, "Server should have received a request body");

    String expectedBase64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
    assertTrue(
        body.contains(expectedBase64),
        "Request body should contain base64-encoded image data");
    assertTrue(
        body.contains("data:image/jpeg;base64,"),
        "Request body should contain JPEG data URI prefix");
  }

  // ==================== visionCompletionDetailed logprobs plumbing (tempdoc 677 S1) ====================

  @Test
  void visionCompletionDetailed_requestsLogprobsAndReducesToScalars() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] requestBody = exchange.getRequestBody().readAllBytes();
          capturedBody.set(new String(requestBody, StandardCharsets.UTF_8));

          byte[] responseBody =
              ("{\"choices\":[{\"message\":{\"content\":\"extracted text\"},"
                      + "\"finish_reason\":\"stop\",\"logprobs\":{\"content\":["
                      + "{\"token\":\"a\",\"logprob\":-0.05},"
                      + "{\"token\":\"b\",\"logprob\":-0.10},"
                      + "{\"token\":\"c\",\"logprob\":-1.50},"
                      + "{\"token\":\"d\",\"logprob\":-0.20}]}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    VisionCompletionResult result =
        ops.visionCompletionDetailed("Extract text", new byte[] {1, 2, 3}, 200)
            .get(5, TimeUnit.SECONDS);

    assertEquals("extracted text", result.content());
    assertEquals("stop", result.finishReason());
    assertEquals(4, result.tokenCount());
    assertEquals(-0.4625, result.meanLogprob(), 1e-9);
    assertEquals(0.25, result.lowConfidenceFraction(), 1e-9);

    String body = capturedBody.get();
    assertNotNull(body, "Server should have received a request body");
    assertTrue(
        body.contains("\"logprobs\":true"),
        "Vision request should opt into per-token logprobs: " + body);
  }

  @Test
  void visionCompletionDetailed_nullSignalsWhenServerOmitsLogprobs() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] responseBody =
              "{\"choices\":[{\"message\":{\"content\":\"no logprobs here\"},"
                      .concat("\"finish_reason\":\"stop\"}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    VisionCompletionResult result =
        ops.visionCompletionDetailed("Extract text", new byte[] {1, 2, 3}, 200)
            .get(5, TimeUnit.SECONDS);

    assertEquals("no logprobs here", result.content());
    assertEquals("stop", result.finishReason());
    assertEquals(0, result.tokenCount());
    assertNull(result.meanLogprob());
    assertNull(result.lowConfidenceFraction());
  }

  @Test
  void visionCompletionDetailed_extractsLengthFinishReason() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] responseBody =
              "{\"choices\":[{\"message\":{\"content\":\"truncated ou\"},"
                      .concat("\"finish_reason\":\"length\"}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    VisionCompletionResult result =
        ops.visionCompletionDetailed("Extract text", new byte[] {1, 2, 3}, 5)
            .get(5, TimeUnit.SECONDS);

    assertEquals("length", result.finishReason());
    assertEquals(0, result.tokenCount());
  }

  // ==================== visionCompletionDetailed sampling/seed override (tempdoc 677 S2) ====================

  @Test
  void visionCompletionDetailed_5arg_sendsSeedAndOverrideTemperature() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] requestBody = exchange.getRequestBody().readAllBytes();
          capturedBody.set(new String(requestBody, StandardCharsets.UTF_8));

          byte[] responseBody =
              "{\"choices\":[{\"message\":{\"content\":\"probe text\"},\"finish_reason\":\"stop\"}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    VisionCompletionResult result =
        ops.visionCompletionDetailed(
                "Extract text", new byte[] {1, 2, 3}, 200, SamplingParams.VDU_PROBE, 677L)
            .get(5, TimeUnit.SECONDS);

    assertEquals("probe text", result.content());

    String body = capturedBody.get();
    assertNotNull(body, "Server should have received a request body");
    assertTrue(body.contains("\"seed\":677"), "seed must be sent on the request: " + body);
    assertTrue(
        body.contains("\"temperature\":0.8"),
        "sampling override (VDU_PROBE, temp 0.8) must be sent: " + body);
  }

  @Test
  void visionCompletionDetailed_3arg_omitsSeed() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] requestBody = exchange.getRequestBody().readAllBytes();
          capturedBody.set(new String(requestBody, StandardCharsets.UTF_8));

          byte[] responseBody =
              "{\"choices\":[{\"message\":{\"content\":\"text\"},\"finish_reason\":\"stop\"}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    ops.visionCompletionDetailed("Extract text", new byte[] {1, 2, 3}, 200)
        .get(5, TimeUnit.SECONDS);

    String body = capturedBody.get();
    assertNotNull(body, "Server should have received a request body");
    assertFalse(
        body.contains("\"seed\""),
        "the plain 3-arg overload (used by Pass 1) must never send a seed: " + body);
  }

  @Test
  void visionCompletion_stringVariantStillReturnsJustContent() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] responseBody =
              ("{\"choices\":[{\"message\":{\"content\":\"extracted text\"},"
                      + "\"finish_reason\":\"stop\",\"logprobs\":{\"content\":["
                      + "{\"token\":\"a\",\"logprob\":-0.05}]}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    String result =
        ops.visionCompletion("Extract text", new byte[] {1, 2, 3}, 200).get(5, TimeUnit.SECONDS);

    assertEquals("extracted text", result);
  }

  @Test
  void chatCompletion_doesNotRequestLogprobs() throws Exception {
    AtomicReference<String> capturedBody = new AtomicReference<>();
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] requestBody = exchange.getRequestBody().readAllBytes();
          capturedBody.set(new String(requestBody, StandardCharsets.UTF_8));

          byte[] responseBody =
              "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });

    ops.chatCompletion(List.of(Map.of("role", "user", "content", "hello")), 50)
        .get(5, TimeUnit.SECONDS);

    String body = capturedBody.get();
    assertNotNull(body);
    assertFalse(
        body.contains("logprobs"),
        "Interactive chat path must not request logprobs (tempdoc 677 S1 scope): " + body);
  }

  // ==================== streamChat error ====================

  @Test
  void streamChat_callsOnErrorOnServerFailure() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });

    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunk -> {},
        fr -> {},
        t -> {
          error.set(t);
          errorLatch.countDown();
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "onError should be called");
    assertNotNull(error.get());
    assertTrue(error.get().getMessage().contains("500"));
  }

  // ==================== vision lock deadline ====================

  @Test
  void visionCompletion_timesOutWhenLockHeld() throws Exception {
    CountDownLatch chatArrived = new CountDownLatch(1);
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          chatArrived.countDown();
          try {
            Thread.sleep(5000);
          } catch (InterruptedException ignored) {
            // test cleanup
          }
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"slow\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    ops.visionLockDeadline = Duration.ofMillis(200);

    // Start a chat that will hold the lock while the server sleeps
    CompletableFuture<String> chatFuture =
        ops.chatCompletion(List.of(Map.of("role", "user", "content", "block")), 100);

    // Wait until the server receives the request (lock is now held)
    assertTrue(chatArrived.await(5, TimeUnit.SECONDS));

    // Vision should timeout after 200ms trying to acquire the lock
    CompletableFuture<String> visionFuture =
        ops.visionCompletion("describe", new byte[] {1, 2, 3}, 100);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class, () -> visionFuture.get(3, TimeUnit.SECONDS));
    assertTrue(ex.getCause().getMessage().contains("timed out"));

    // Let the chat complete to avoid resource leaks
    chatFuture.get(10, TimeUnit.SECONDS);
  }

  // ==================== shutdown ====================

  @Test
  void shutdown_terminatesExecutor() throws Exception {
    // Perform one operation to ensure executor is initialized and working
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "warmup"));
    ops.chatCompletion(messages, 10).get(5, TimeUnit.SECONDS);

    ops.shutdown();

    // After shutdown, vision requests (which use vduExecutor) should fail.
    // supplyAsync on a shut-down executor throws RejectedExecutionException synchronously,
    // or the future completes exceptionally -- either way, the operation must fail.
    mode.set(Mode.ONLINE);
    byte[] imageBytes = {0x01, 0x02};
    try {
      CompletableFuture<String> future = ops.visionCompletion("test", imageBytes, 10);
      ExecutionException ex =
          assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
      assertNotNull(ex.getCause(), "Should fail because executor is shut down");
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // Also acceptable: supplyAsync rejects immediately on a shut-down executor
    }
  }

  // ==================== Streaming completeness contract ====================

  @Test
  void streamChat_completesNormallyWhenDoneSentinelReceived() throws Exception {
    // Default server handler sends [DONE] — verify onComplete fires
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    CopyOnWriteArrayList<String> chunks = new CopyOnWriteArrayList<>();

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunks::add,
        fr -> completeLatch.countDown(),
        errorRef::set);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "onComplete should fire");
    assertNull(errorRef.get(), "onError should not fire on clean completion");
    assertEquals(List.of("Hello", " world"), chunks);
  }

  @Test
  void streamChat_passesStopFinishReasonToOnComplete() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<String> finishReasonRef = new AtomicReference<>();

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunk -> {},
        fr -> {
          finishReasonRef.set(fr);
          completeLatch.countDown();
        },
        err -> fail("onError should not fire: " + err));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "onComplete should fire");
    assertEquals("stop", finishReasonRef.get());
  }

  @Test
  void streamChat_passesLengthFinishReasonToOnComplete() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"length\"}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<String> finishReasonRef = new AtomicReference<>();

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunk -> {},
        fr -> {
          finishReasonRef.set(fr);
          completeLatch.countDown();
        },
        err -> fail("onError should not fire: " + err));

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "onComplete should fire");
    assertEquals("length", finishReasonRef.get());
  }

  @Test
  void streamChat_firesOnErrorWhenDoneSentinelMissing() throws Exception {
    // Replace server with one that sends chunks but NO [DONE]
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          // Close without sending data: [DONE]
          os.close();
        });

    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    CountDownLatch completeLatch = new CountDownLatch(1);

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunk -> {},
        fr -> completeLatch.countDown(),
        err -> {
          errorRef.set(err);
          errorLatch.countDown();
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "onError should fire on missing sentinel");
    assertEquals(1, completeLatch.getCount(), "onComplete should NOT fire on truncation");
    assertInstanceOf(StreamTruncatedException.class, errorRef.get());
  }

  @Test
  void streamChat_capturesFinishReasonInTruncationException() throws Exception {
    // Send a chunk with finish_reason but no [DONE]
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"length\"}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunk -> {},
        fr -> {},
        err -> {
          errorRef.set(err);
          errorLatch.countDown();
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
    assertInstanceOf(StreamTruncatedException.class, errorRef.get());
    assertEquals("length", ((StreamTruncatedException) errorRef.get()).finishReason());
  }

  @Test
  void streamChat_cancellationRoutesToOnError() throws Exception {
    // Default server handler — but onChunk throws CancellationException
    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunk -> {
          throw new java.util.concurrent.CancellationException("client_disconnected");
        },
        fr -> fail("onComplete should not fire on cancellation"),
        err -> {
          errorRef.set(err);
          errorLatch.countDown();
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "onError should fire on cancellation");
    assertInstanceOf(
        java.util.concurrent.CancellationException.class,
        errorRef.get(),
        "Error should be CancellationException");
  }

  @Test
  void streamChatWithTools_firesOnErrorWhenDoneSentinelMissing() throws Exception {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    ops.streamChatWithTools(
        List.of(Map.of("role", "user", "content", "test")),
        List.of(),
        100,
        chunk -> {},
        toolDelta -> {},
        reasoning -> {},
        null,
        fr -> fail("onComplete should not fire on truncation"),
        err -> {
          errorRef.set(err);
          errorLatch.countDown();
        });

    assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
    assertInstanceOf(StreamTruncatedException.class, errorRef.get());
  }

  @Test
  void streamChat_lenientModeCompletesOnMissingSentinel() throws Exception {
    // Replace server with one that sends chunks but NO [DONE]
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });

    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    CopyOnWriteArrayList<String> chunks = new CopyOnWriteArrayList<>();

    // requireSentinel=false — lenient mode for map-reduce style accumulation
    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunks::add,
        null,
        fr -> completeLatch.countDown(),
        errorRef::set,
        null,
        false);

    assertTrue(completeLatch.await(5, TimeUnit.SECONDS),
        "onComplete should fire in lenient mode even without [DONE]");
    assertNull(errorRef.get(), "onError should not fire in lenient mode");
    assertEquals(List.of("partial"), chunks);
  }

  // ==================== Streaming producer wedge (836 §9.9) ====================

  /**
   * Installs a handler that answers 200, writes one chunk, and then never writes again and never
   * closes — the "producer that never returns" shape. Returns a latch the test releases so the
   * server thread can exit.
   */
  private CountDownLatch installNeverEndingStream() {
    CountDownLatch release = new CountDownLatch(1);
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.flush();
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    return release;
  }

  @Test
  @DisplayName("A body that goes silent hits the idle read deadline and errors instead of parking")
  void streamChat_stalledBodyFiresStreamStalledException() throws Exception {
    CountDownLatch release = installNeverEndingStream();
    ops.streamIdleDeadline = Duration.ofMillis(300);

    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    try {
      ops.streamChat(
          List.of(Map.of("role", "user", "content", "test")),
          100,
          chunk -> {},
          fr -> fail("onComplete must not fire for an abandoned stream"),
          err -> {
            errorRef.set(err);
            errorLatch.countDown();
          });

      // The request timeout (2 minutes) bounds only header arrival, so without a read deadline
      // nothing here ever fires.
      assertTrue(errorLatch.await(10, TimeUnit.SECONDS), "the idle read deadline must fire");
      assertInstanceOf(
          StreamStalledException.class,
          errorRef.get(),
          "an idle body must terminate as a stall, not park the streaming thread");
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName(
      "Wedge regression (836 §9.9): a stream that never returns must not block the next dispatch"
          + " past the read deadline")
  void streamChat_stalledStreamDoesNotWedgeTheNextDispatch() throws Exception {
    CountDownLatch release = installNeverEndingStream();
    ops.streamIdleDeadline = Duration.ofMillis(300);

    CountDownLatch firstDone = new CountDownLatch(1);
    try {
      ops.streamChat(
          List.of(Map.of("role", "user", "content", "first")),
          100,
          chunk -> {},
          fr -> firstDone.countDown(),
          err -> firstDone.countDown());

      assertTrue(
          firstDone.await(10, TimeUnit.SECONDS),
          "the stalled stream must terminate on its own read deadline");

      // Second dispatch: a healthy stream, on the same single-threaded executor and the same
      // process-wide lock the first one held. Pre-fix it queued behind the first forever.
      release.countDown();
      restoreDefaultStreamHandler();
      CountDownLatch secondDone = new CountDownLatch(1);
      CopyOnWriteArrayList<String> chunks = new CopyOnWriteArrayList<>();
      ops.streamChat(
          List.of(Map.of("role", "user", "content", "second")),
          100,
          chunks::add,
          fr -> secondDone.countDown(),
          err -> secondDone.countDown());

      assertTrue(secondDone.await(10, TimeUnit.SECONDS), "the next dispatch must still run");
      assertEquals(List.of("Hello", " world"), chunks);
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("A blocked consumer callback no longer holds the process-wide inference lock")
  void streamChat_slowCallbackDoesNotHoldTheOnlineRequestLock() throws Exception {
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch streamDone = new CountDownLatch(1);

    ops.streamChat(
        List.of(Map.of("role", "user", "content", "test")),
        100,
        chunk -> {
          callbackEntered.countDown();
          try {
            releaseCallback.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        },
        fr -> streamDone.countDown(),
        err -> streamDone.countDown());

    assertTrue(callbackEntered.await(10, TimeUnit.SECONDS), "the chunk callback should run");
    try {
      // A chat completion needs the same lock the streaming exchange takes. While a consumer
      // callback is blocked, that lock must already be free — pre-fix the callback ran inside it,
      // so this call could not complete until the consumer returned.
      String answer =
          ops.chatCompletion(List.of(Map.of("role", "user", "content", "q")), 32)
              .get(10, TimeUnit.SECONDS);
      assertEquals("Hello from LLM", answer);
    } finally {
      releaseCallback.countDown();
    }
    assertTrue(streamDone.await(10, TimeUnit.SECONDS), "the stream still terminates");
  }

  private void restoreDefaultStreamHandler() {
    server.removeContext("/v1/chat/completions");
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          var os = exchange.getResponseBody();
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          os.flush();
          os.close();
        });
  }
}

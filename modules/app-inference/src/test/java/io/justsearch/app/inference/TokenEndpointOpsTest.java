package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenEndpointOpsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<Map<String, Object>> MESSAGES =
      List.of(Map.of("role", "user", "content", "test"));

  private static final List<Map<String, Object>> TOOLS =
      List.of(
          Map.of(
              "type",
              "function",
              "function",
              Map.of("name", "read_document", "description", "Reads a document")));

  private HttpServer server;
  private int port;
  private AtomicReference<Mode> mode;
  private AtomicInteger tokenizeHitCount;
  private AtomicInteger applyTemplateHitCount;
  private AtomicReference<String> lastApplyTemplateBody;
  private TokenEndpointOps ops;

  @BeforeEach
  void setUp() throws Exception {
    mode = new AtomicReference<>(Mode.ONLINE);
    tokenizeHitCount = new AtomicInteger(0);
    applyTemplateHitCount = new AtomicInteger(0);
    lastApplyTemplateBody = new AtomicReference<>(null);

    server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

    server.createContext(
        "/tokenize",
        exchange -> {
          tokenizeHitCount.incrementAndGet();
          byte[] body = "{\"tokens\":[1,2,3,4,5]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    server.createContext(
        "/apply-template",
        exchange -> {
          applyTemplateHitCount.incrementAndGet();
          lastApplyTemplateBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              "{\"prompt\":\"<|user|>\\ntest<|end|>\\n<|assistant|>\\n\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    server.start();
    port = server.getAddress().getPort();

    ops =
        new TokenEndpointOps(
            HttpClient.newHttpClient(), MAPPER, mode::get, () -> port, () -> () -> {});
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  // ==================== countTokens ====================

  @Test
  void countTokens_happyPath() {
    Optional<Integer> result = ops.countTokens("hello world");
    assertEquals(Optional.of(5), result);
  }

  @Test
  void countTokens_emptyTextReturnsZero() {
    Optional<Integer> result = ops.countTokens("");
    assertEquals(Optional.of(0), result);
    assertEquals(0, tokenizeHitCount.get(), "No HTTP call should be made for empty text");
  }

  @Test
  void countTokens_nullTextReturnsZero() {
    Optional<Integer> result = ops.countTokens(null);
    assertEquals(Optional.of(0), result);
    assertEquals(0, tokenizeHitCount.get(), "No HTTP call should be made for null text");
  }

  @Test
  void countTokens_notOnlineReturnsEmpty() {
    mode.set(Mode.OFFLINE);
    Optional<Integer> result = ops.countTokens("hello world");
    assertEquals(Optional.empty(), result);
    assertEquals(0, tokenizeHitCount.get(), "No HTTP call should be made when OFFLINE");
  }

  // ==================== applyTemplate ====================

  @Test
  void applyTemplate_happyPath() {
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    Optional<String> result = ops.applyTemplate(messages);
    assertTrue(result.isPresent());
    assertEquals("<|user|>\ntest<|end|>\n<|assistant|>\n", result.get());
  }

  @Test
  void applyTemplate_emptyMessagesReturnsEmptyString() {
    Optional<String> result = ops.applyTemplate(List.of());
    assertEquals(Optional.of(""), result);
  }

  // ==================== applyTemplate: tool threading (tempdoc 878 finding 6) ====================

  @Test
  void applyTemplate_sendsToolsWhenListNonEmpty() throws Exception {
    assertTrue(ops.applyTemplate(MESSAGES, TOOLS).isPresent());

    JsonNode body = MAPPER.readTree(lastApplyTemplateBody.get());
    JsonNode tools = body.get("tools");
    assertNotNull(tools, "request body must carry a tools key when tools were supplied");
    assertTrue(tools.isArray());
    assertEquals(1, tools.size());
    assertEquals(
        "read_document",
        tools.get(0).get("function").get("name").asText(),
        "the tool schema itself must reach the template, not just the key");
  }

  @Test
  void applyTemplate_emptyToolsProducesBodyIdenticalToNoToolsForm() throws Exception {
    ops.applyTemplate(MESSAGES);
    String withoutToolsArg = lastApplyTemplateBody.get();
    assertNotNull(withoutToolsArg);
    assertFalse(
        MAPPER.readTree(withoutToolsArg).has("tools"),
        "single-argument form must not introduce a tools key");

    ops.applyTemplate(MESSAGES, List.of());
    assertEquals(
        withoutToolsArg,
        lastApplyTemplateBody.get(),
        "an empty tool list must send byte-identical JSON to the pre-threading body");
  }

  @Test
  void applyTemplate_nullToolsProducesBodyIdenticalToNoToolsForm() throws Exception {
    ops.applyTemplate(MESSAGES);
    String withoutToolsArg = lastApplyTemplateBody.get();

    ops.applyTemplate(MESSAGES, null);
    assertEquals(
        withoutToolsArg,
        lastApplyTemplateBody.get(),
        "a null tool list must send byte-identical JSON to the pre-threading body");
    assertFalse(MAPPER.readTree(lastApplyTemplateBody.get()).has("tools"));
  }

  @Test
  void countPromptTokens_threadsToolsIntoTemplate() throws Exception {
    assertEquals(Optional.of(5), ops.countPromptTokens(MESSAGES, TOOLS));
    assertTrue(
        MAPPER.readTree(lastApplyTemplateBody.get()).has("tools"),
        "countPromptTokens must project the same prompt the generation call will send");
  }

  // ==================== countPromptTokens ====================

  @Test
  void countPromptTokens_combinedOperation() {
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", "test"));
    Optional<Integer> result = ops.countPromptTokens(messages);
    // applyTemplate returns a non-empty prompt, then countTokens returns 5
    assertEquals(Optional.of(5), result);
  }

  // ==================== supportsTokenize ====================

  @Test
  void supportsTokenize_probesAndCaches() {
    // First call triggers a probe request to the server
    assertTrue(ops.supportsTokenize());
    int hitsAfterFirst = tokenizeHitCount.get();
    assertEquals(1, hitsAfterFirst, "First call should probe the server");

    // Second call should use the cached result (no additional HTTP request)
    assertTrue(ops.supportsTokenize());
    assertEquals(hitsAfterFirst, tokenizeHitCount.get(), "Second call should use cache");
  }

  @Test
  void supportsTokenize_returnsFalseWhenNotOnline() {
    mode.set(Mode.OFFLINE);
    assertFalse(ops.supportsTokenize());
    assertEquals(0, tokenizeHitCount.get(), "No HTTP call should be made when OFFLINE");
  }

  // ==================== supportsApplyTemplate ====================

  @Test
  void supportsApplyTemplate_probesAndCaches() {
    assertTrue(ops.supportsApplyTemplate());
    assertEquals(1, applyTemplateHitCount.get());
    assertTrue(ops.supportsApplyTemplate());
    assertEquals(1, applyTemplateHitCount.get(), "Second call should use cache");
  }

  @Test
  void supportsApplyTemplate_returnsFalseWhenNotOnline() {
    mode.set(Mode.OFFLINE);
    assertFalse(ops.supportsApplyTemplate());
    assertEquals(0, applyTemplateHitCount.get());
  }

  // ==================== non-200 server response ====================

  @Test
  void countTokens_serverErrorReturnsEmpty() {
    server.removeContext("/tokenize");
    server.createContext(
        "/tokenize",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    assertEquals(Optional.empty(), ops.countTokens("hello"));
  }

  // ==================== clearCaches ====================

  @Test
  void clearCaches_resetsProbes() {
    // Prime the cache
    assertTrue(ops.supportsTokenize());
    int hitsAfterFirst = tokenizeHitCount.get();
    assertEquals(1, hitsAfterFirst);

    // Clear and re-probe
    ops.clearCaches();
    assertTrue(ops.supportsTokenize());
    assertEquals(hitsAfterFirst + 1, tokenizeHitCount.get(), "After clearCaches, should re-probe");
  }
}

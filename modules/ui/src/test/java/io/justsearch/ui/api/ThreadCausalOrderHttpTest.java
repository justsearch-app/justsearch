/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import io.justsearch.agent.api.interaction.ThreadProjection;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** WU2–WU9: real disk history and local HTTP; every restart owns fresh handlers and the same root. */
final class ThreadCausalOrderHttpTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String AT = "2026-10-23T02:04:45.355494108Z";
  private static final List<String> IDS = List.of(
      "f0000000-0000-4000-8000-000000000000", "e0000000-0000-4000-8000-000000000000",
      "d0000000-0000-4000-8000-000000000000", "c0000000-0000-4000-8000-000000000000");
  @TempDir Path tmp;

  @Test
  void exactInstantDescendingIdsKeepBothExchangesAcrossColdReadRestartAndReload() throws Exception {
    Path root = tmp.resolve("conversations");
    var store = new FileConversationStore(root);
    for (String outcome : List.of("NO_CONTENT", "ANSWER", "RETRIEVAL_TIMEOUT", "RETRIEVAL_FAILED", "FETCH_FAILED")) {
      for (boolean scoped : List.of(false, true)) {
        String id = outcome + "-" + scoped;
        store.createSession(id, "core.rag-ask");
        for (int i = 0; i < IDS.size(); i++) {
          Map<String, Object> row = new LinkedHashMap<>(Map.of("id", IDS.get(i), "ts", AT,
              "role", i % 2 == 0 ? "user" : "assistant", "content", (i % 2 == 0 ? "Q" : "E") + (i / 2 + 1)));
          if (i % 2 == 1) {
            row.put("runId", "run-" + i);
            if (!outcome.equals("ANSWER")) {
              row.put("terminalError", true);
              row.put("errorCode", outcome);
              row.put("retrievalScoped", scoped);
            }
          }
          store.appendMessage(id, "core.rag-ask", row);
        }
        byte[] original = Files.readAllBytes(root.resolve(id).resolve("messages.jsonl"));
        var history = new FileConversationStore(root).loadHistory(id);
        assertEquals(IDS, history.stream().map(row -> row.get("id")).toList(), history.toString());
        for (String stage : List.of("initial", "restart")) {
          try (var server = new Server(root, ThreadProjection.of(List.of())); var client = HttpClient.newHttpClient()) {
            for (String read : List.of(stage, stage + "-reload")) {
              String json = server.get(client, id);
              retain(id + "-" + read, json, original, history);
              JsonNode events = MAPPER.readTree(json).path("events");
              String diagnostic = "history=" + history + " response=" + json;
              assertEquals(4, events.size(), diagnostic);
              // Complete positional permutation, not a search for any ERROR (WU3).
              var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
              for (int i = 0; i < IDS.size(); i++) {
                int index = i;
                var row = events.get(index);
                String position = "events[" + index + "] " + diagnostic;
                assertions.add(() -> assertEquals(IDS.get(index), row.path("id").asString(), position));
                assertions.add(() -> assertEquals(AT, row.path("occurredAt").asString(), position));
                assertions.add(() -> assertEquals(index % 2 == 0 ? "USER_MESSAGE" : outcome.equals("ANSWER") ? "ASSISTANT_MESSAGE" : "ERROR",
                    row.path("kind").asString(), position));
                if (index % 2 == 1 && !outcome.equals("ANSWER")) {
                  assertions.add(() -> assertEquals(outcome, row.path("attributes").path("errorCode").asString(), position));
                  assertions.add(() -> assertEquals(scoped, row.path("attributes").path("retrievalScoped").asBoolean(!scoped), position));
                }
              }
              assertAll("complete equal-Instant permutation", assertions);
              assertArrayEquals(original, Files.readAllBytes(root.resolve(id).resolve("messages.jsonl")), diagnostic);
              assertEquals(history, new FileConversationStore(root).loadHistory(id), diagnostic);
            }
          }
        }
      }
    }
  }

  @Test
  void actionSlotsAndReasoningReplacementUseProvenanceWhileChatCanCrossWorkflowBoundaries() throws Exception {
    Path root = tmp.resolve("mixed");
    var store = new FileConversationStore(root);
    store.createSession("mixed", "core.rag-ask");
    for (int i = 0; i < IDS.size(); i++) {
      store.appendMessage("mixed", "core.rag-ask", Map.of("id", IDS.get(i), "ts", AT,
          "role", i % 2 == 0 ? "user" : "assistant", "content", "chat-" + i, "runId", "run-" + i));
    }
    var actions = List.of(
        action("c1", InteractionEventKind.PROGRESS, Map.of("nodeBoundary", "start", "nodeId", "first")),
        action("c2", InteractionEventKind.ASSISTANT_MESSAGE, Map.of()),
        action("d1", InteractionEventKind.ERROR, Map.of("errorCode", "ACTION_ERROR")),
        action("d2", InteractionEventKind.PROGRESS, Map.of("nodeBoundary", "end", "nodeId", "first")),
        action("e1", InteractionEventKind.PROGRESS, Map.of("nodeBoundary", "start", "nodeId", "second")),
        action("e2", InteractionEventKind.PROGRESS, Map.of("phase", "output")),
        action("f1", InteractionEventKind.PROGRESS, Map.of("nodeBoundary", "end", "nodeId", "second")));
    var projection = new ThreadProjection(actions, Map.of("run-1", List.of(Map.of("text", "trailing", "durationMs", 10))));
    byte[] original = Files.readAllBytes(root.resolve("mixed/messages.jsonl"));
    try (var server = new Server(root, projection); var client = HttpClient.newHttpClient()) {
      String json = server.get(client, "mixed");
      retain("mixed", json, original, store.loadHistory("mixed"));
      var events = MAPPER.readTree(json).path("events");
      List<String> ids = new ArrayList<>();
      events.forEach(row -> ids.add(row.path("id").asString()));
      // Old full sort's action slots are fixed; only its four chat slots change occupants.
      assertEquals(List.of(IDS.get(0), "c1", "c2", IDS.get(1), "d1", "d2", IDS.get(2), "e1", "e2", IDS.get(3), "f1"), ids, json);
      assertEquals("trailing", events.get(3).path("attributes").path("reasoning").get(0).path("text").asString(), json);
      assertEquals("ASSISTANT_MESSAGE", events.get(2).path("kind").asString(), json);
      assertEquals("ACTION_ERROR", events.get(4).path("attributes").path("errorCode").asString(), json);
      assertArrayEquals(original, Files.readAllBytes(root.resolve("mixed/messages.jsonl")));
    }
  }

  @Test
  void legacyFilesKeepDuplicateInheritedOccurrencesFilteringAndTimestampSemantics() throws Exception {
    Path root = tmp.resolve("legacy");
    var store = new FileConversationStore(root);
    store.createSession("parent", "core.rag-ask");
    // Explicit legacy bytes: appendMessage would fill absent/non-string timestamps and IDs.
    writeLegacy(root, "parent", List.of(
        Map.of("role", "user", "content", "parent Q", "ts", AT),
        Map.of("role", "assistant", "content", "parent E", "ts", AT)));
    store.branchFrom("parent", "idx-1", "child");
    writeLegacy(root, "child", List.of(
        Map.of("role", "user", "content", "child Q", "ts", "2026-10-23T03:04:45.355494108+01:00"),
        Map.of("role", "assistant", "content", "child E", "ts", AT),
        Map.of("id", "duplicate", "role", "user", "content", "duplicate Q", "ts", AT),
        Map.of("id", "duplicate", "role", "assistant", "content", "duplicate E", "ts", AT),
        Map.of("role", "system", "content", "hidden", "ts", AT),
        Map.of("role", "locked", "content", "hidden", "ts", AT)));
    byte[] original = Files.readAllBytes(root.resolve("child/messages.jsonl"));
    try (var server = new Server(root, ThreadProjection.of(List.of())); var client = HttpClient.newHttpClient()) {
      String json = server.get(client, "child");
      retain("legacy-duplicates", json, original, store.loadHistory("child"));
      var events = MAPPER.readTree(json).path("events");
      List<String> contents = new ArrayList<>();
      List<String> ids = new ArrayList<>();
      events.forEach(row -> { contents.add(row.path("content").asString()); ids.add(row.path("id").asString()); });
      assertEquals(List.of("parent Q", "parent E", "child Q", "child E", "duplicate Q", "duplicate E"), contents, json);
      assertEquals(List.of("idx-0", "idx-1", "idx-0", "idx-1", "duplicate", "duplicate"), ids, json);
      assertArrayEquals(original, Files.readAllBytes(root.resolve("child/messages.jsonl")));
    }
    store.createSession("times", "core.rag-ask");
    writeLegacy(root, "times", List.of(
        Map.of("id", "late", "role", "user", "content", "later nano", "ts", "2026-10-23T02:04:45.355494109Z"),
        Map.of("id", "missing", "role", "user", "content", "missing"),
        Map.of("id", "numeric", "role", "user", "content", "numeric", "ts", 1000),
        Map.of("id", "malformed", "role", "user", "content", "malformed", "ts", "bad"),
        Map.of("id", "early", "role", "user", "content", "earlier nano", "ts", AT),
        Map.of("id", "offset", "role", "user", "content", "same nano", "ts", "2026-10-23T03:04:45.355494108+01:00")));
    byte[] times = Files.readAllBytes(root.resolve("times/messages.jsonl"));
    try (var server = new Server(root, ThreadProjection.of(List.of())); var client = HttpClient.newHttpClient()) {
      String json = server.get(client, "times");
      retain("legacy-times", json, times, store.loadHistory("times"));
      var events = MAPPER.readTree(json).path("events");
      List<String> ids = new ArrayList<>();
      events.forEach(row -> ids.add(row.path("id").asString()));
      assertEquals(List.of("missing", "malformed", "numeric", "early", "offset", "late"), ids, json);
      assertEquals(Instant.EPOCH.toString(), events.get(0).path("occurredAt").asString(), json);
      assertEquals(Instant.ofEpochMilli(1000).toString(), events.get(2).path("occurredAt").asString(), json);
      assertArrayEquals(times, Files.readAllBytes(root.resolve("times/messages.jsonl")));
    }
  }

  @Test
  void legacyTimestampParsingAndChronologyRemainAPreservationCheckBeforeAndAfter() throws Exception {
    // Independent of the repaired duplicate-order assertion above: a fail-before there must not
    // prevent the old ISO/numeric/EPOCH semantics from actually executing on the baseline.
    Path root = tmp.resolve("time-preservation");
    var store = new FileConversationStore(root);
    store.createSession("times", "core.rag-ask");
    writeLegacy(root, "times", List.of(
        Map.of("id", "a-late", "role", "user", "content", "later", "ts", "2026-10-23T02:04:45.355494109Z"),
        Map.of("id", "missing", "role", "user", "content", "missing"),
        Map.of("id", "numeric", "role", "user", "content", "numeric", "ts", 1000),
        Map.of("id", "malformed", "role", "user", "content", "malformed", "ts", "bad"),
        Map.of("id", "z-early", "role", "user", "content", "earlier", "ts", AT),
        Map.of("id", "offset", "role", "user", "content", "same", "ts", "2026-10-23T03:04:45.355494108+01:00")));
    byte[] original = Files.readAllBytes(root.resolve("times/messages.jsonl"));
    for (int restart = 0; restart < 2; restart++) {
      try (var server = new Server(root, ThreadProjection.of(List.of())); var client = HttpClient.newHttpClient()) {
        String json = server.get(client, "times");
        List<String> timestamps = new ArrayList<>();
        MAPPER.readTree(json).path("events").forEach(row -> timestamps.add(row.path("occurredAt").asString()));
        assertEquals(List.of("1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:01Z",
            AT, AT, "2026-10-23T02:04:45.355494109Z"), timestamps, json);
        assertArrayEquals(original, Files.readAllBytes(root.resolve("times/messages.jsonl")));
      }
    }
  }

  private static InteractionEvent action(String id, InteractionEventKind kind, Map<String, Object> attributes) {
    return new InteractionEvent(id, "mixed", Instant.parse(AT), kind, "agent", id, attributes);
  }

  private static void writeLegacy(Path root, String id, List<Map<String, Object>> rows) throws Exception {
    var lines = new ArrayList<String>();
    for (var row : rows) lines.add(MAPPER.writeValueAsString(row));
    Files.write(root.resolve(id).resolve("messages.jsonl"), lines);
  }

  private void retain(String name, String json, byte[] bytes, List<Map<String, Object>> history) throws Exception {
    String destination = System.getenv("THREAD_ORDER_HTTP_DIR");
    Path evidence = destination == null ? tmp.resolve("evidence") : Path.of(destination);
    Files.createDirectories(evidence);
    Files.writeString(evidence.resolve(name + ".json"), json);
    Files.writeString(evidence.resolve(name + ".json.sha256"), sha(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    Files.write(evidence.resolve(name + ".messages.jsonl"), bytes);
    Files.writeString(evidence.resolve(name + ".messages.sha256"), sha(bytes));
    Files.writeString(evidence.resolve(name + ".history.json"), MAPPER.writeValueAsString(history));
  }

  private static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static final class Server implements AutoCloseable {
    private final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
    private final InteractionThreadController controller;
    private final Javalin app;

    Server(Path root, ThreadProjection projection) {
      var agent = mock(AgentService.class);
      when(agent.threadProjection(anyString(), any())).thenReturn(projection);
      controller = new InteractionThreadController(mock(OperationAttemptRunner.class), new FileConversationStore(root), agent, executors);
      app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      app.get("/api/thread/{id}", controller::handleGet);
      app.start("127.0.0.1", 0);
    }

    String get(HttpClient client, String id) throws Exception {
      var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/thread/" + id))
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return response.body();
    }

    @Override public void close() {
      app.stop();
      controller.shutdown();
      executors.close();
    }
  }
}

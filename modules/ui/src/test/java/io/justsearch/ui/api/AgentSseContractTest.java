package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.RetryAction;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Agent SSE contract: /api/chat/agent")
final class AgentSseContractTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  @DisplayName("budget_update and done payloads include frozen contract fields")
  void runStreamBudgetAndDoneFieldsAreStable() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));
    AgentService __agent = new ContractAgentService();

    LocalApiServer server = LocalApiServer.builder(settingsStore, tempDir.resolve("index")).agentService(__agent).build();
    try {
      String body =
          """
          {
            "messages": [{"role":"user","content":"contract check"}],
            "maxIterations": 3
          }
          """;

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.getPort() + "/api/chat/agent"))
                  .header("Content-Type", "application/json")
                  .header("Accept", "text/event-stream")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      List<SseEvent> events = parseSse(response.body());

      JsonNode budget = firstEvent(events, "budget_update");
      assertNotNull(budget, "must emit budget_update event");
      assertTrue(budget.has("phase"));
      assertTrue(budget.has("tokensConsumed"));
      assertTrue(budget.has("tokensRemaining"));
      assertEquals("iteration_start", budget.path("phase").asText());
      assertEquals(128, budget.path("tokensConsumed").asInt(-1));
      assertEquals(7000, budget.path("tokensRemaining").asInt(-1));
      // Tempdoc 577 Ext III — the run-cumulative figure is part of the frozen contract.
      assertTrue(budget.has("totalTokensConsumed"));
      assertEquals(0, budget.path("totalTokensConsumed").asInt(-1));
      // Tempdoc 577 §2.14 Root II — the cognitive-headroom figures ride the contract (0 on the
      // iteration_start phase; the llm_response phase carries the real occupancy ÷ n_ctx).
      assertTrue(budget.has("promptTokens"));
      assertEquals(0, budget.path("promptTokens").asInt(-1));
      assertTrue(budget.has("contextWindow"));
      assertEquals(0, budget.path("contextWindow").asInt(-1));
      assertTrue(budget.has("trace"));
      assertEquals("session_contract", budget.path("trace").path("runId").asText());
      assertEquals("primary", budget.path("trace").path("agentId").asText());
      assertFalse(budget.path("trace").path("spanId").isMissingNode(), "budget trace must include spanId");
      assertFalse(budget.path("trace").path("stepId").isMissingNode(), "budget trace must include stepId");
      assertTrue(budget.path("trace").path("iteration").asInt(-1) >= 0, "budget trace iteration >= 0");

      JsonNode done = firstEvent(events, "done");
      assertNotNull(done, "must emit done event");
      assertTrue(done.has("finalResponse"));
      assertTrue(done.has("iterationsUsed"));
      assertTrue(done.has("toolCallsExecuted"));
      assertTrue(done.has("totalTokensUsed"));
      assertEquals("contract complete", done.path("finalResponse").asText());
      assertEquals(2, done.path("iterationsUsed").asInt(-1));
      assertEquals(1, done.path("toolCallsExecuted").asInt(-1));
      assertEquals(256, done.path("totalTokensUsed").asInt(-1));
      assertTrue(done.has("trace"));
      assertEquals("session_contract", done.path("trace").path("runId").asText());
      assertEquals("primary", done.path("trace").path("agentId").asText());
      assertFalse(done.path("trace").path("spanId").isMissingNode(), "done trace must include spanId");
      assertFalse(done.path("trace").path("stepId").isMissingNode(), "done trace must include stepId");
      assertTrue(done.path("trace").path("iteration").asInt(-1) >= 0, "done trace iteration >= 0");
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("error payload includes typed metadata fields when provided")
  void runStreamErrorFieldsAreStable() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings-error.json"));
    AgentService __agent = new ContractAgentErrorService();

    LocalApiServer server = LocalApiServer.builder(settingsStore, tempDir.resolve("index")).agentService(__agent).build();
    try {
      String body =
          """
          {
            "messages": [{"role":"user","content":"trigger error"}],
            "maxIterations": 1
          }
          """;

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.getPort() + "/api/chat/agent"))
                  .header("Content-Type", "application/json")
                  .header("Accept", "text/event-stream")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      List<SseEvent> events = parseSse(response.body());
      JsonNode error = firstEvent(events, "error");
      assertNotNull(error, "must emit error event");
      assertEquals("LLM_TRANSIENT", error.path("errorCode").asText());
      assertEquals("TRANSIENT", error.path("errorClass").asText());
      assertEquals("RETRY", error.path("retryAction").asText());
      assertEquals(2, error.path("retryAttempt").asInt(-1));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("session last + replay endpoints expose persisted snapshot and events")
  void sessionLastAndReplayEndpointsReturnData() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings-session.json"));
    AgentService __agent = new PersistedSessionAgentService();

    LocalApiServer server = LocalApiServer.builder(settingsStore, tempDir.resolve("index")).agentService(__agent).build();
    try {
      HttpResponse<String> lastResponse =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.getPort() + "/api/chat/sessions/last"))
                  .GET()
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, lastResponse.statusCode());
      JsonNode lastJson = MAPPER.readTree(lastResponse.body());
      assertEquals("session_persisted", lastJson.path("sessionId").asText());
      assertEquals("READY_FOR_LLM", lastJson.path("state").asText());

      HttpResponse<String> eventsResponse =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + server.getPort()
                              + "/api/chat/sessions/session_persisted/events"))
                  .GET()
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, eventsResponse.statusCode());
      JsonNode eventsJson = MAPPER.readTree(eventsResponse.body());
      assertEquals("session_persisted", eventsJson.path("sessionId").asText());
      assertTrue(eventsJson.path("events").isArray());
      assertEquals(2, eventsJson.path("events").size());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("sessions / session-detail / resume-by-id / transcript endpoints (tempdoc 415 C20+C33)")
  void sessionListResumeAndTranscriptEndpoints() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    UiSettingsStore settingsStore =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.IN_MEMORY,
            tempDir.resolve("settings-sessions.json"));
    AgentService __agent = new PersistedSessionAgentService();

    LocalApiServer server =
        LocalApiServer.builder(settingsStore, tempDir.resolve("index")).agentService(__agent).build();
    try {
      // GET /api/chat/sessions
      HttpResponse<String> listResp =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:" + server.getPort() + "/api/chat/sessions"))
                  .GET()
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, listResp.statusCode());
      JsonNode list = MAPPER.readTree(listResp.body());
      assertTrue(list.path("sessions").isArray());
      assertEquals(2, list.path("sessions").size());
      assertEquals("session_persisted", list.path("sessions").get(0).path("sessionId").asText());

      // GET /api/chat/sessions/{id} — known
      HttpResponse<String> detailResp =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + server.getPort()
                              + "/api/chat/sessions/session_persisted"))
                  .GET()
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, detailResp.statusCode());
      JsonNode detail = MAPPER.readTree(detailResp.body());
      assertEquals("session_persisted", detail.path("sessionId").asText());
      assertTrue(detail.path("messages").isArray());

      // GET /api/chat/sessions/{id} — unknown → 404
      HttpResponse<String> missingDetail =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + server.getPort()
                              + "/api/chat/sessions/no-such-session"))
                  .GET()
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, missingDetail.statusCode());

      // POST /api/chat/sessions/{id}/resume/stream — known
      HttpResponse<String> resumeResp =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + server.getPort()
                              + "/api/chat/sessions/session_persisted/resume"))
                  .header("Accept", "text/event-stream")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, resumeResp.statusCode());
      List<SseEvent> resumeEvents = parseSse(resumeResp.body());
      assertNotNull(firstEvent(resumeEvents, "session_started"));
      JsonNode resumeDone = firstEvent(resumeEvents, "done");
      assertNotNull(resumeDone);
      assertEquals("resumed by id", resumeDone.path("finalResponse").asText());

      // GET /api/chat/sessions/{id}/transcript — known → 200 with bundled meta+events
      HttpResponse<String> transcriptResp =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + server.getPort()
                              + "/api/chat/sessions/session_persisted/transcript"))
                  .GET()
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, transcriptResp.statusCode());
      assertTrue(
          transcriptResp.headers().firstValue("Content-Disposition").orElse("").contains(
              "agent-session-session_persisted.json"),
          "Content-Disposition must trigger download with sessionId-prefixed filename");
      JsonNode transcript = MAPPER.readTree(transcriptResp.body());
      assertEquals(
          "session_persisted", transcript.path("meta").path("sessionId").asText());
      assertTrue(transcript.path("events").isArray());

      // GET /api/chat/sessions/{id}/transcript — unknown → 404
      HttpResponse<String> missingTranscript =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + server.getPort()
                              + "/api/chat/sessions/no-such-session/transcript"))
                  .GET()
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, missingTranscript.statusCode());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("resume-last stream endpoint emits persisted resume events")
  void resumeLastStreamEmitsEvents() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings-resume.json"));
    AgentService __agent = new PersistedSessionAgentService();

    LocalApiServer server = LocalApiServer.builder(settingsStore, tempDir.resolve("index")).agentService(__agent).build();
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + server.getPort()
                              + "/api/chat/sessions/resume-last"))
                  .header("Accept", "text/event-stream")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      List<SseEvent> events = parseSse(response.body());
      assertNotNull(firstEvent(events, "session_started"));
      JsonNode done = firstEvent(events, "done");
      assertNotNull(done);
      assertEquals("resumed response", done.path("finalResponse").asText());
    } finally {
      server.stop();
    }
  }

  private static List<SseEvent> parseSse(String body) throws Exception {
    var out = new java.util.ArrayList<SseEvent>();
    String[] blocks = body.split("\\n\\n");
    for (String block : blocks) {
      if (block.isBlank()) {
        continue;
      }
      String event = null;
      String data = null;
      for (String line : block.split("\\n")) {
        if (line.startsWith("event:")) {
          event = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
          data = line.substring(5).trim();
        }
      }
      if (event == null || data == null || data.isBlank()) {
        continue;
      }
      out.add(new SseEvent(event, MAPPER.readTree(data)));
    }
    return out;
  }

  private static JsonNode firstEvent(List<SseEvent> events, String eventName) {
    return events.stream()
        .filter(e -> eventName.equals(e.event()))
        .map(SseEvent::data)
        .findFirst()
        .orElse(null);
  }

  private record SseEvent(String event, JsonNode data) {}

  private static final class ContractAgentService implements AgentService {
    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      ToolCallRequest call = new ToolCallRequest("call_1", "search_index", "{\"query\":\"contract\"}");
      eventConsumer.accept(new AgentEvent.SessionStarted("session_contract"));
      eventConsumer.accept(new AgentEvent.AgentProgress("llm_call", "Calling LLM", 1, 3));
      eventConsumer.accept(
          new AgentEvent.AgentBudgetUpdate(
              "iteration_start",
              128,
              7000,
              0,
              // Tempdoc 577 §2.14 Root II — the projected iteration_start phase carries no occupancy.
              0,
              0,
              new TraceContext(
                  "session_contract",
                  "iter:1:budget:iteration_start",
                  "span-000001",
                  null,
                  "primary",
                  null,
                  1)));
      eventConsumer.accept(new AgentEvent.TextChunk("contract "));
      eventConsumer.accept(new AgentEvent.ToolCallProposed(call, RiskTier.LOW));
      eventConsumer.accept(new AgentEvent.ToolCallApproved("call_1"));
      eventConsumer.accept(new AgentEvent.ToolExecutionStarted("call_1", "search_index"));
      eventConsumer.accept(
          new AgentEvent.ToolExecutionCompleted(
              "call_1", OperationResult.success("ok", "exec_1")));
      eventConsumer.accept(
          new AgentEvent.AgentBudgetUpdate(
              "llm_response",
              128,
              6872,
              128,
              // Tempdoc 577 §2.14 Root II — the llm_response phase carries occupancy (promptTokens)
              // and the model's n_ctx (contextWindow): the cognitive-headroom meter's numerator/denom.
              1024,
              8192,
              new TraceContext(
                  "session_contract",
                  "iter:1:budget:llm_response",
                  "span-000002",
                  "span-000001",
                  "primary",
                  null,
                  1)));
      eventConsumer.accept(
          new AgentEvent.AgentDone(
              "contract complete",
              2,
              1,
              256,
              new TraceContext(
                  "session_contract",
                  "iter:2:done",
                  "span-000003",
                  "span-000002",
                  "primary",
                  null,
                  2)));
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  private static final class ContractAgentErrorService implements AgentService {
    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      eventConsumer.accept(new AgentEvent.SessionStarted("session_error"));
      eventConsumer.accept(
          new AgentEvent.AgentError(
              "transient backend error",
              AgentErrorCode.LLM_TRANSIENT,
              AgentErrorClass.TRANSIENT,
              RetryAction.RETRY,
              2));
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  private static final class PersistedSessionAgentService implements AgentService {
    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      eventConsumer.accept(new AgentEvent.AgentDone("unused", 0, 0, 0));
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public Map<String, Object> lastSessionSnapshot() {
      return Map.of(
          "sessionId", "session_persisted",
          "state", "READY_FOR_LLM",
          "resumable", true,
          "maxIterations", 10);
    }

    @Override
    public void resumeLastSession(Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      eventConsumer.accept(new AgentEvent.SessionStarted("session_resumed"));
      eventConsumer.accept(new AgentEvent.AgentDone("resumed response", 2, 1, 120));
    }

    @Override
    public List<Map<String, Object>> listSessions(int limit) {
      return List.of(
          Map.of(
              "sessionId", "session_persisted",
              "state", "READY_FOR_LLM",
              "resumable", true,
              "preview", "first prompt",
              "iterationsUsed", 0,
              "toolCallsExecuted", 0,
              "totalTokensUsed", 0),
          Map.of(
              "sessionId", "session_other",
              "state", "AFTER_TOOL_RESULT",
              "resumable", true,
              "preview", "another prompt"));
    }

    @Override
    public Map<String, Object> sessionSnapshot(String sessionId) {
      if (!"session_persisted".equals(sessionId)) {
        return null;
      }
      return Map.of(
          "sessionId", "session_persisted",
          "state", "READY_FOR_LLM",
          "resumable", true,
          "maxIterations", 10,
          "messages",
              List.of(Map.of("role", "user", "content", "first prompt")));
    }

    @Override
    public void resumeSession(String sessionId, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      if (!"session_persisted".equals(sessionId)) {
        eventConsumer.accept(
            new AgentEvent.AgentError(
                "No persisted snapshot found for session: " + sessionId,
                AgentErrorCode.UNSUPPORTED_RESUME_STATE,
                AgentErrorClass.PERMANENT,
                RetryAction.ABORT,
                null));
        return;
      }
      eventConsumer.accept(new AgentEvent.SessionStarted("session_persisted"));
      eventConsumer.accept(new AgentEvent.AgentDone("resumed by id", 2, 1, 120));
    }

    @Override
    public List<Map<String, Object>> sessionEvents(String sessionId) {
      if (!"session_persisted".equals(sessionId)) {
        return List.of();
      }
      return List.of(
          Map.of("eventType", "session_started", "payload", Map.of("sessionId", sessionId)),
          Map.of("eventType", "progress", "payload", Map.of("phase", "llm_call")));
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  @Test
  @DisplayName("handoff_proposed and handoff_executed payloads include contract fields")
  void handoffPayloadsAreStable() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    UiSettingsStore settingsStore =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings-handoff.json"));
    AgentService __agent = new ContractHandoffAgentService();

    LocalApiServer server =
        LocalApiServer.builder(settingsStore, tempDir.resolve("index-handoff")).agentService(__agent).build();
    try {
      String body =
          """
          {
            "messages": [{"role":"user","content":"handoff contract"}],
            "agentProfiles": [
              {"agentId": "planner", "name": "Planner", "toolSubset": []},
              {"agentId": "executor", "name": "Executor", "toolSubset": []}
            ],
            "initialAgentId": "planner",
            "maxIterations": 5
          }
          """;

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.getPort() + "/api/chat/agent"))
                  .header("Content-Type", "application/json")
                  .header("Accept", "text/event-stream")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      List<SseEvent> events = parseSse(response.body());

      JsonNode proposed = firstEvent(events, "handoff_proposed");
      assertNotNull(proposed, "must emit handoff_proposed event");
      assertEquals("planner", proposed.path("fromAgentId").asText());
      assertEquals("executor", proposed.path("toAgentId").asText());
      assertEquals("time to execute", proposed.path("reason").asText());

      JsonNode executed = firstEvent(events, "handoff_executed");
      assertNotNull(executed, "must emit handoff_executed event");
      assertEquals("planner", executed.path("fromAgentId").asText());
      assertEquals("executor", executed.path("toAgentId").asText());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("agentProfiles and initialAgentId are parsed from request body and forwarded to AgentService")
  void agentProfilesAndInitialAgentIdAreParsedFromRequest() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    UiSettingsStore settingsStore =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings-profiles.json"));
    CapturingAgentService agentService = new CapturingAgentService();
    AgentService __agent = agentService;

    LocalApiServer server =
        LocalApiServer.builder(settingsStore, tempDir.resolve("index-profiles")).agentService(__agent)
            .build();
    try {
      String body =
          """
          {
            "messages": [{"role":"user","content":"hi"}],
            "agentProfiles": [
              {"agentId": "planner", "name": "Planner", "toolSubset": []},
              {"agentId": "executor", "name": "Executor", "toolSubset": ["search_index"]}
            ],
            "initialAgentId": "planner",
            "maxIterations": 3
          }
          """;

      client.send(
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getPort() + "/api/chat/agent"))
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .timeout(Duration.ofSeconds(5))
              .build(),
          HttpResponse.BodyHandlers.ofString());

      AgentRequest captured = agentService.captured;
      assertNotNull(captured, "agentService should have received a request");
      assertEquals("planner", captured.initialAgentId());
      assertEquals(2, captured.agentProfiles().size());
      assertEquals("planner", captured.agentProfiles().get(0).agentId());
      assertEquals("Planner", captured.agentProfiles().get(0).name());
      assertEquals("executor", captured.agentProfiles().get(1).agentId());
      assertEquals(List.of("search_index"), captured.agentProfiles().get(1).toolSubset());
    } finally {
      server.stop();
    }
  }

  private static final class ContractHandoffAgentService implements AgentService {
    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      eventConsumer.accept(new AgentEvent.SessionStarted("session_handoff"));
      eventConsumer.accept(new AgentEvent.HandoffProposed("planner", "executor", "time to execute"));
      eventConsumer.accept(new AgentEvent.HandoffExecuted("planner", "executor"));
      eventConsumer.accept(new AgentEvent.AgentDone("done", 2, 0, 0));
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  private static final class CapturingAgentService implements AgentService {
    volatile AgentRequest captured;

    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      captured = request;
      eventConsumer.accept(new AgentEvent.AgentDone("done", 1, 0, 0));
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }
}

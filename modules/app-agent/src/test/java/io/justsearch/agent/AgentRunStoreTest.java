package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRunStoreTest {

  @TempDir Path tempDir;

  @Test
  void persistsSnapshotAndEvents() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "hello")), List.of(), 5);

    store.startRun("session_1", request, request.messages(), 8000);
    store.appendEvent("session_1", new AgentEvent.SessionStarted("session_1"));
    store.updateCheckpoint(
        "session_1",
        "WAITING_APPROVAL",
        request.messages(),
        1,
        0,
        30,
        "waiting approval");

    Map<String, Object> snapshot = store.readLastSnapshot();
    assertNotNull(snapshot);
    assertEquals("session_1", snapshot.get("sessionId"));
    assertEquals("WAITING_APPROVAL", snapshot.get("state"));
    assertEquals(true, snapshot.get("resumable"));

    List<Map<String, Object>> events = store.readEvents("session_1");
    assertEquals(1, events.size());
    assertEquals("session_started", events.get(0).get("eventType"));
  }

  /**
   * Tempdoc 565 §15.C fix — the run-event store is now multi-shape (agent + workflow share the root).
   * The agent-run VIEWS (Sessions, presence) must exclude workflow runs, while the unified-thread
   * cross-domain join (listRunIdsByConversation) must still include them.
   */
  @Test
  void workflowRunsExcludedFromAgentViewsButIncludedInThread() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var runEvents = store.runEvents();

    var agentMeta = new java.util.LinkedHashMap<String, Object>();
    agentMeta.put("sessionId", "agent-1");
    agentMeta.put("shapeId", "core.agent-run");
    agentMeta.put("conversationId", "conv-1");
    agentMeta.put("startedAt", "2026-06-06T00:00:00Z");
    agentMeta.put("updatedAt", "2026-06-06T00:00:01Z");
    agentMeta.put("background", true);
    runEvents.writeRunMeta("agent-1", agentMeta);

    var workflowMeta = new java.util.LinkedHashMap<String, Object>();
    workflowMeta.put("sessionId", "wf-1");
    workflowMeta.put("shapeId", "core.workflow-run");
    workflowMeta.put("conversationId", "conv-1");
    workflowMeta.put("startedAt", "2026-06-06T00:00:02Z");
    workflowMeta.put("updatedAt", "2026-06-06T00:00:03Z");
    workflowMeta.put("background", false);
    runEvents.writeRunMeta("wf-1", workflowMeta);

    // Sessions view is agent-run-only — the workflow run is excluded.
    List<Map<String, Object>> sessions = store.listSessions(10);
    assertEquals(1, sessions.size());
    assertEquals("agent-1", sessions.get(0).get("sessionId"));

    // Presence (agent-run inbox) excludes the workflow run too.
    List<Map<String, Object>> presence = store.presenceRunsSince(null);
    assertEquals(1, presence.size());
    assertEquals("agent-1", presence.get(0).get("sessionId"));

    // The unified thread (cross-shape) sees BOTH runs for the conversation.
    List<String> runIds = store.listRunIdsByConversation("conv-1");
    assertEquals(2, runIds.size());
    assertTrue(runIds.contains("agent-1"));
    assertTrue(runIds.contains("wf-1"));
  }

  /**
   * Tempdoc S4b (Search Thread) — {@code appendSearchEvent} writes a THIRD shape (alongside
   * {@code core.agent-run} / {@code core.workflow-run}) that must ALSO join the conversation via
   * {@code listRunIdsByConversation} and round-trip through the durable events.ndjson exactly like any
   * other run's events, without polluting the agent-only Sessions/presence views.
   */
  @Test
  void appendSearchEventJoinsConversationAndRoundTripsEvent() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));

    String eventId =
        store.appendSearchEvent(
            "conv-search",
            Map.of(
                "query", "invoices",
                "mode", "hybrid",
                "matchCount", 42,
                "resultCount", 10,
                "docIds", List.of("a.pdf", "b.pdf"),
                "executedAt", "2026-07-06T00:00:00Z"));

    assertNotNull(eventId, "a persisted search event returns its projected wire id");
    assertTrue(eventId.startsWith("conv-search:search:"), eventId);

    List<String> runIds = store.listRunIdsByConversation("conv-search");
    assertEquals(1, runIds.size(), "the search event's dedicated run joins the conversation");
    String runId = runIds.get(0);

    List<Map<String, Object>> events = store.readEvents(runId);
    assertEquals(1, events.size());
    assertEquals("search_executed", events.get(0).get("eventType"));
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) events.get(0).get("payload");
    assertEquals("invoices", payload.get("query"));
    assertEquals("hybrid", payload.get("mode"));

    // Not an agent run: excluded from the agent-only Sessions/presence views.
    assertTrue(store.listSessions(10).isEmpty());
    assertTrue(store.presenceRunsSince(null).isEmpty());
  }

  @Test
  void appendSearchEventReturnsNullForBlankConversationId() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    assertEquals(null, store.appendSearchEvent("", Map.of("query", "x")));
    assertEquals(null, store.appendSearchEvent(null, Map.of("query", "x")));
  }

  @Test
  void persistsTraceEnvelopeWhenPresent() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "trace")), List.of(), 3);
    store.startRun("session_trace", request, request.messages(), 8000);

    store.appendEvent(
        "session_trace",
        new AgentEvent.AgentProgress(
            "llm_call",
            "Calling LLM",
            1,
            3,
            new TraceContext(
                "session_trace",
                "iter:1:progress:llm_call",
                "span-000001",
                null,
                "primary",
                null,
                1)));

    List<Map<String, Object>> events = store.readEvents("session_trace");
    assertEquals(1, events.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) events.get(0).get("payload");
    assertNotNull(payload);
    @SuppressWarnings("unchecked")
    Map<String, Object> trace = (Map<String, Object>) payload.get("trace");
    assertNotNull(trace);
    assertEquals("session_trace", trace.get("runId"));
    assertEquals("primary", trace.get("agentId"));
    assertEquals("span-000001", trace.get("spanId"));
    assertEquals("iter:1:progress:llm_call", trace.get("stepId"));
    assertEquals(1, ((Number) trace.get("iteration")).intValue());
  }

  @Test
  void marksUnsupportedStatesAsNotResumable() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "hello")), List.of(), 5);
    store.startRun("session_2", request, request.messages(), 8000);

    store.updateCheckpoint(
        "session_2",
        "TOOL_EXECUTING",
        request.messages(),
        1,
        0,
        40,
        "tool executing");
    Map<String, Object> snapshot = store.readLastSnapshot();
    assertNotNull(snapshot);
    assertFalse(Boolean.TRUE.equals(snapshot.get("resumable")));

    store.updateCheckpoint(
        "session_2",
        "AFTER_TOOL_RESULT",
        request.messages(),
        1,
        1,
        60,
        "after tool");
    snapshot = store.readLastSnapshot();
    assertNotNull(snapshot);
    assertTrue(Boolean.TRUE.equals(snapshot.get("resumable")));
  }

  @Test
  void replayMatrix_twentyFourCrashPointRuns_keepEventOrderStable() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "matrix")), List.of(), 8);

    List<String> crashStates =
        List.of("WAITING_APPROVAL", "TOOL_EXECUTING", "AFTER_TOOL_RESULT", "LLM_STREAMING");
    Map<String, Boolean> expectedResumable =
        Map.of(
            "WAITING_APPROVAL", true,
            "TOOL_EXECUTING", false,
            "AFTER_TOOL_RESULT", true,
            "LLM_STREAMING", false);

    int runsPerState = 6;
    int totalRuns = 0;

    for (String state : crashStates) {
      for (int i = 0; i < runsPerState; i++) {
        totalRuns++;
        String sessionId = "session_matrix_" + totalRuns;
        String callId = "call_" + totalRuns;

        store.startRun(sessionId, request, request.messages(), 8000);
        List<String> expectedEventTypes = appendCrashPointEvents(store, sessionId, callId, state);
        store.updateCheckpoint(
            sessionId,
            state,
            request.messages(),
            1,
            1,
            120,
            "crash matrix state: " + state);

        List<Map<String, Object>> events = store.readEvents(sessionId);
        List<String> actualEventTypes = new ArrayList<>();
        for (Map<String, Object> event : events) {
          actualEventTypes.add(String.valueOf(event.get("eventType")));
        }
        assertEquals(
            expectedEventTypes,
            actualEventTypes,
            "Event order diverged for " + sessionId + " @" + state);

        Map<String, Object> snapshot = store.readLastSnapshot();
        assertNotNull(snapshot);
        assertEquals(sessionId, snapshot.get("sessionId"));
        assertEquals(state, snapshot.get("state"));
        assertEquals(expectedResumable.get(state), snapshot.get("resumable"));
      }
    }

    assertEquals(24, totalRuns, "Matrix must exercise 24 replay/crash runs");
  }

  private static List<String> appendCrashPointEvents(
      AgentRunStore store, String sessionId, String callId, String state) {
    var expected = new ArrayList<String>();
    expected.add(appendAndName(store, sessionId, new AgentEvent.SessionStarted(sessionId)));

    switch (state) {
      case "WAITING_APPROVAL" -> {
        expected.add(
            appendAndName(
                store,
                sessionId,
                new AgentEvent.ToolCallProposed(
                    new ToolCallRequest(callId, "search_index", "{\"query\":\"matrix\"}"),
                    RiskTier.MEDIUM)));
        expected.add(
            appendAndName(
                store,
                sessionId,
                new AgentEvent.ToolCallPendingApproval(
                    callId, "search_index", "{\"query\":\"matrix\"}", RiskTier.MEDIUM)));
      }
      case "TOOL_EXECUTING" -> {
        expected.add(
            appendAndName(
                store,
                sessionId,
                new AgentEvent.ToolCallProposed(
                    new ToolCallRequest(callId, "search_index", "{\"query\":\"matrix\"}"),
                    RiskTier.LOW)));
        expected.add(appendAndName(store, sessionId, new AgentEvent.ToolCallApproved(callId)));
        expected.add(
            appendAndName(
                store, sessionId, new AgentEvent.ToolExecutionStarted(callId, "search_index")));
      }
      case "AFTER_TOOL_RESULT" -> {
        expected.add(
            appendAndName(
                store,
                sessionId,
                new AgentEvent.ToolCallProposed(
                    new ToolCallRequest(callId, "search_index", "{\"query\":\"matrix\"}"),
                    RiskTier.LOW)));
        expected.add(appendAndName(store, sessionId, new AgentEvent.ToolCallApproved(callId)));
        expected.add(
            appendAndName(
                store, sessionId, new AgentEvent.ToolExecutionStarted(callId, "search_index")));
        expected.add(
            appendAndName(
                store,
                sessionId,
                new AgentEvent.ToolExecutionCompleted(callId, OperationResult.success("ok"))));
      }
      case "LLM_STREAMING" -> {
        expected.add(appendAndName(store, sessionId, new AgentEvent.AgentProgress("llm_call", "Call LLM", 1, 8)));
        expected.add(appendAndName(store, sessionId, new AgentEvent.TextChunk("partial")));
      }
      default -> throw new IllegalArgumentException("Unsupported test state: " + state);
    }
    return expected;
  }

  private static String appendAndName(AgentRunStore store, String sessionId, AgentEvent event) {
    store.appendEvent(sessionId, event);
    return switch (event) {
      case AgentEvent.TextChunk ignored -> "chunk";
      case AgentEvent.ToolCallProposed ignored -> "tool_call_proposed";
      case AgentEvent.ToolBatchProposed ignored -> "tool_batch_proposed";
      case AgentEvent.ToolCallPendingApproval ignored -> "tool_call_pending";
      case AgentEvent.ToolCallApproved ignored -> "tool_call_approved";
      case AgentEvent.DirectiveAcknowledged ignored -> "directive_acknowledged";
      case AgentEvent.ToolExecutionStarted ignored -> "tool_exec_started";
      case AgentEvent.ToolExecutionCompleted ignored -> "tool_exec_completed";
      case AgentEvent.ToolCallRejected ignored -> "tool_call_rejected";
      case AgentEvent.ToolCallVirtual ignored -> "tool_call_virtual";
      case AgentEvent.AgentDone ignored -> "done";
      case AgentEvent.AgentError ignored -> "error";
      case AgentEvent.AgentProgress ignored -> "progress";
      case AgentEvent.AgentBudgetUpdate ignored -> "budget_update";
      case AgentEvent.BudgetGatePending ignored -> "budget_gate";
      case AgentEvent.ContextGatePending ignored -> "context_gate";
      case AgentEvent.ContextCompacted ignored -> "context_compacted";
      case AgentEvent.SessionStarted ignored -> "session_started";
      case AgentEvent.HandoffProposed ignored -> "handoff_proposed";
      case AgentEvent.HandoffExecuted ignored -> "handoff_executed";
      case AgentEvent.ReasoningChunk ignored -> "reasoning_chunk";
      case AgentEvent.StateSnapshot ignored -> "state_snapshot";
    };
  }

  @Test
  void goldenFixture_v0_upcasterAddsSchemaVersion() throws Exception {
    Path runsDir = tempDir.resolve("agent-runs");
    Path runDir = runsDir.resolve("session_golden_v0");
    Files.createDirectories(runDir);
    copyResource("fixtures/schema-v0/meta.json", runDir.resolve("meta.json"));
    copyResource("fixtures/schema-v0/events.ndjson", runDir.resolve("events.ndjson"));
    Files.writeString(runsDir.resolve("last-session.txt"), "session_golden_v0");

    var store = new AgentRunStore(runsDir);
    Map<String, Object> snapshot = store.readLastSnapshot();

    assertNotNull(snapshot, "Should read v0 fixture");
    assertEquals("session_golden_v0", snapshot.get("sessionId"));
    assertEquals("READY_FOR_LLM", snapshot.get("state"));
    assertEquals(
        AgentRunStore.CURRENT_SCHEMA_VERSION,
        ((Number) snapshot.get("schemaVersion")).intValue(),
        "Upcaster must add schemaVersion to v0 checkpoint");
    assertTrue(Boolean.TRUE.equals(snapshot.get("resumable")));

    List<Map<String, Object>> events = store.readEvents("session_golden_v0");
    assertEquals(2, events.size());
    assertEquals("session_started", events.get(0).get("eventType"));
    assertEquals("progress", events.get(1).get("eventType"));
  }

  @Test
  void rejectsFutureSchemaVersion() throws Exception {
    Path runsDir = tempDir.resolve("agent-runs");
    Path runDir = runsDir.resolve("session_future");
    Files.createDirectories(runDir);
    Files.writeString(
        runDir.resolve("meta.json"),
        """
        {
          "sessionId": "session_future",
          "schemaVersion": 999,
          "state": "READY_FOR_LLM",
          "resumable": true,
          "resumeNote": "",
          "messages": []
        }
        """);
    Files.writeString(runsDir.resolve("last-session.txt"), "session_future");

    var store = new AgentRunStore(runsDir);
    assertThrows(
        UnsupportedOperationException.class,
        store::readLastSnapshot,
        "Should throw on checkpoint with schemaVersion > CURRENT_SCHEMA_VERSION");
  }

  @Test
  void v3EventPayloads_safetyLevel_areMigratedToRiskOnRead() throws Exception {
    // Per tempdoc 429 §F.21 C2: persisted events.ndjson entries from sessions written
    // before rev 9 carry "safetyLevel": "READ_ONLY"|"WRITE"|"DESTRUCTIVE". The
    // EventPayloadUpcaster materializes them as "risk": "low"|"medium"|"high" on read
    // so consumers see only the new vocabulary.
    Path runsDir = tempDir.resolve("agent-runs");
    Path runDir = runsDir.resolve("session_v3_legacy");
    Files.createDirectories(runDir);
    Files.writeString(
        runDir.resolve("meta.json"),
        """
        {
          "sessionId": "session_v3_legacy",
          "schemaVersion": 3,
          "state": "READY_FOR_LLM",
          "resumable": true,
          "resumeNote": "",
          "messages": []
        }
        """);
    Files.writeString(
        runDir.resolve("events.ndjson"),
        // Three events with the legacy safetyLevel field — one per legacy enum value.
        "{\"eventType\":\"tool_call_proposed\",\"payload\":{\"callId\":\"c1\","
            + "\"toolName\":\"core.search-index\",\"arguments\":\"{}\","
            + "\"safetyLevel\":\"READ_ONLY\"}}\n"
            + "{\"eventType\":\"tool_call_pending\",\"payload\":{\"callId\":\"c2\","
            + "\"toolName\":\"core.ingest-files\",\"arguments\":\"{}\","
            + "\"safetyLevel\":\"WRITE\"}}\n"
            + "{\"eventType\":\"tool_call_proposed\",\"payload\":{\"callId\":\"c3\","
            + "\"toolName\":\"core.file-operations\",\"arguments\":\"{}\","
            + "\"safetyLevel\":\"DESTRUCTIVE\"}}\n");

    var store = new AgentRunStore(runsDir);
    List<Map<String, Object>> events = store.readEvents("session_v3_legacy");
    assertEquals(3, events.size());

    for (Map<String, Object> event : events) {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = (Map<String, Object>) event.get("payload");
      assertFalse(payload.containsKey("safetyLevel"),
          "Legacy safetyLevel field must be removed by upcaster");
      assertNotNull(payload.get("risk"),
          "Migrated events must carry the new risk field");
    }
    assertEquals("low",
        ((Map<?, ?>) events.get(0).get("payload")).get("risk"));
    assertEquals("medium",
        ((Map<?, ?>) events.get(1).get("payload")).get("risk"));
    assertEquals("high",
        ((Map<?, ?>) events.get(2).get("payload")).get("risk"));
  }

  @Test
  void newRun_writesCurrentSchemaVersion() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request =
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "schema check")), List.of(), 3);
    store.startRun("session_schema", request, request.messages(), 8000);

    Map<String, Object> snapshot = store.readLastSnapshot();
    assertNotNull(snapshot);
    Object version = snapshot.get("schemaVersion");
    assertNotNull(version, "schemaVersion must be present in new runs");
    assertEquals(AgentRunStore.CURRENT_SCHEMA_VERSION, ((Number) version).intValue());
  }

  @Test
  void handoffEventsAreSerializedAndReplayed() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request =
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "handoff events")), List.of(), 5);
    store.startRun("session_handoff_events", request, request.messages(), 8000);

    store.appendEvent(
        "session_handoff_events",
        new AgentEvent.HandoffProposed("planner", "executor", "planning done"));
    store.appendEvent(
        "session_handoff_events", new AgentEvent.HandoffExecuted("planner", "executor"));

    List<Map<String, Object>> events = store.readEvents("session_handoff_events");
    assertEquals(2, events.size());

    assertEquals("handoff_proposed", events.get(0).get("eventType"));
    @SuppressWarnings("unchecked")
    Map<String, Object> proposed = (Map<String, Object>) events.get(0).get("payload");
    assertEquals("planner", proposed.get("fromAgentId"));
    assertEquals("executor", proposed.get("toAgentId"));
    assertEquals("planning done", proposed.get("reason"));

    assertEquals("handoff_executed", events.get(1).get("eventType"));
    @SuppressWarnings("unchecked")
    Map<String, Object> executed = (Map<String, Object>) events.get(1).get("payload");
    assertEquals("planner", executed.get("fromAgentId"));
    assertEquals("executor", executed.get("toAgentId"));
  }

  @Test
  void setHandoffState_persistsHandoffFields() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request =
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "handoff state")), List.of(), 5);
    store.startRun("session_set_handoff", request, request.messages(), 8000);

    // Verify initial state defaults
    Map<String, Object> initial = store.readLastSnapshot();
    assertEquals("primary", initial.get("activeAgentId"));
    @SuppressWarnings("unchecked")
    List<?> initialHistory = (List<?>) initial.get("handoffHistory");
    assertTrue(initialHistory.isEmpty());

    // Apply handoff state
    var history =
        List.of(
            Map.<String, Object>of(
                "fromAgentId", "planner",
                "toAgentId", "executor",
                "reason", "planning done",
                "timestamp", "2026-02-19T12:00:00Z"));
    store.setHandoffState("session_set_handoff", "executor", history);

    Map<String, Object> snapshot = store.readLastSnapshot();
    assertEquals("executor", snapshot.get("activeAgentId"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> persisted =
        (List<Map<String, Object>>) snapshot.get("handoffHistory");
    assertEquals(1, persisted.size());
    assertEquals("planner", persisted.get(0).get("fromAgentId"));
    assertEquals("executor", persisted.get(0).get("toAgentId"));
    assertEquals("planning done", persisted.get(0).get("reason"));
  }

  @Test
  void goldenFixture_v1_upcasterAddsHandoffFields() throws Exception {
    Path runsDir = tempDir.resolve("agent-runs");
    Path runDir = runsDir.resolve("session_golden_v1");
    Files.createDirectories(runDir);
    copyResource("fixtures/schema-v1/meta.json", runDir.resolve("meta.json"));
    copyResource("fixtures/schema-v1/events.ndjson", runDir.resolve("events.ndjson"));
    Files.writeString(runsDir.resolve("last-session.txt"), "session_golden_v1");

    var store = new AgentRunStore(runsDir);
    Map<String, Object> snapshot = store.readLastSnapshot();

    assertNotNull(snapshot, "Should read v1 fixture");
    assertEquals("session_golden_v1", snapshot.get("sessionId"));
    assertEquals(
        AgentRunStore.CURRENT_SCHEMA_VERSION,
        ((Number) snapshot.get("schemaVersion")).intValue(),
        "Upcaster must update schemaVersion in v1 checkpoint");
    assertEquals("primary", snapshot.get("activeAgentId"));
    @SuppressWarnings("unchecked")
    List<?> handoffHistory = (List<?>) snapshot.get("handoffHistory");
    assertNotNull(handoffHistory);
    assertTrue(handoffHistory.isEmpty(), "handoffHistory must default to empty list");
    @SuppressWarnings("unchecked")
    List<?> agentProfiles = (List<?>) snapshot.get("agentProfiles");
    assertNotNull(agentProfiles);
    assertTrue(agentProfiles.isEmpty(), "agentProfiles must default to empty list");
  }

  private static void copyResource(String resourcePath, Path target) throws Exception {
    try (InputStream in =
        AgentRunStoreTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
      assertNotNull(in, "Test fixture not found: " + resourcePath);
      Files.copy(in, target);
    }
  }

  // ---------------------------------------------------------------------------
  // listSessions / readSnapshot — tempdoc 415 follow-up (C20)
  // ---------------------------------------------------------------------------

  @Test
  void listSessions_returnsRecentDirsByMtime() throws Exception {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request =
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "first question")), List.of(), 5);

    store.startRun("session_a", request, request.messages(), 8000);
    Thread.sleep(50); // ensure distinct mtime
    store.startRun(
        "session_b",
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "second question")), List.of(), 5),
        List.of(Map.of("role", "user", "content", "second question")),
        8000);
    Thread.sleep(50);
    store.startRun(
        "session_c",
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "third question")), List.of(), 5),
        List.of(Map.of("role", "user", "content", "third question")),
        8000);

    var sessions = store.listSessions(10);
    assertEquals(3, sessions.size(), "Expected three persisted sessions");
    // Newest first.
    assertEquals("session_c", sessions.get(0).get("sessionId"));
    assertEquals("session_b", sessions.get(1).get("sessionId"));
    assertEquals("session_a", sessions.get(2).get("sessionId"));

    // Summary projection: heavy fields dropped, light fields present.
    var first = sessions.get(0);
    assertTrue(first.containsKey("startedAt"));
    assertTrue(first.containsKey("state"));
    assertTrue(first.containsKey("resumable"));
    assertTrue(first.containsKey("preview"));
    assertFalse(first.containsKey("messages"), "messages must not appear in summary");
    assertFalse(first.containsKey("agentProfiles"), "agentProfiles must not appear in summary");
    assertEquals("third question", first.get("preview"));

    // Limit truncates from the newest end.
    var limited = store.listSessions(2);
    assertEquals(2, limited.size());
    assertEquals("session_c", limited.get(0).get("sessionId"));
    assertEquals("session_b", limited.get(1).get("sessionId"));
  }

  @Test
  void listSessions_failsLoudlyForCorruptAuthoredMeta() throws Exception {
    var rootDir = tempDir.resolve("agent-runs");
    var store = new AgentRunStore(rootDir);
    var request =
        new AgentRequest(List.of(Map.of("role", "user", "content", "ok")), List.of(), 5);
    store.startRun("good_session", request, request.messages(), 8000);

    // Hand-craft a corrupt session directory.
    var corruptDir = rootDir.resolve("corrupt_session");
    Files.createDirectories(corruptDir);
    Files.writeString(corruptDir.resolve("meta.json"), "{ this is not valid json");

    assertThrows(
        io.justsearch.configuration.persistence.CorruptDurableStoreException.class,
        () -> store.listSessions(10));
  }

  @Test
  void readSnapshot_returnsFullMetaIncludingMessages() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    var msgs =
        List.<Map<String, Object>>of(
            Map.of("role", "system", "content", "You are helpful."),
            Map.of("role", "user", "content", "Find foo."));
    var request = new AgentRequest(msgs, List.of(), 5);
    store.startRun("full_session", request, msgs, 8000);

    var snapshot = store.readSnapshot("full_session");
    assertNotNull(snapshot);
    assertEquals("full_session", snapshot.get("sessionId"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> readBackMsgs = (List<Map<String, Object>>) snapshot.get("messages");
    assertNotNull(readBackMsgs, "Snapshot must include messages (heavy field)");
    assertEquals(2, readBackMsgs.size());
    assertEquals("Find foo.", readBackMsgs.get(1).get("content"));
  }

  @Test
  void readSnapshot_returnsNullForUnknownSessionId() {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    assertNotNull(store);
    var snap = store.readSnapshot("does-not-exist");
    org.junit.jupiter.api.Assertions.assertNull(snap);
  }
}

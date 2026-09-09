package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import io.justsearch.agent.api.interaction.ThreadProjection;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 561 P-A/P-B (correction) — GET /api/thread/{id} as a read-time projection over
 * ConversationStore (chat) + AgentService.threadEvents (agent), interleaved by timestamp. No store.
 */
final class InteractionThreadControllerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private final DefaultEngineExecutorRegistry executors = new DefaultEngineExecutorRegistry();
  private final List<InteractionThreadController> controllers = new ArrayList<>();

  @AfterEach
  void closeFixtures() {
    controllers.forEach(InteractionThreadController::shutdown);
    executors.close();
  }

  private InteractionThreadController newController(
      ConversationStore conversationStore, AgentService agentService) {
    var controller = new InteractionThreadController(conversationStore, agentService, executors);
    controllers.add(controller);
    return controller;
  }

  private JsonNode invokeGet(InteractionThreadController controller, String id) {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(id);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    AtomicReference<byte[]> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0, byte[].class));
              return ctx;
            })
        .when(ctx)
        .result(any(byte[].class));
    controller.handleGet(ctx);
    try {
      return MAPPER.readTree(captured.get());
    } catch (Exception e) {
      throw new IllegalStateException("could not parse thread response", e);
    }
  }

  @Test
  @DisplayName("interleaves chat turns (ConversationStore) and agent activity (AgentService) by timestamp")
  void interleavesBothPlanesByTimestamp() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-1"))
        .thenReturn(
            List.of(
                Map.of("id", "u1", "role", "user", "content", "find invoices", "ts",
                    "2026-01-01T00:00:01Z"),
                Map.of("id", "a1", "role", "assistant", "content", "found 12", "ts",
                    "2026-01-01T00:00:04Z"),
                // system/context messages are not thread turns
                Map.of("id", "s1", "role", "system", "content", "you are an agent", "ts",
                    "2026-01-01T00:00:00Z")));

    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-1"), any()))
        .thenReturn(ThreadProjection.of(
            List.of(
                new InteractionEvent(
                    "c1:completed",
                    "conv-1",
                    Instant.parse("2026-01-01T00:00:02Z"),
                    InteractionEventKind.TOOL_ACTIVITY,
                    "agent",
                    "",
                    Map.of("callId", "c1", "toolName", "core_search_index", "status", "completed")))));

    JsonNode body = invokeGet(newController(conversationStore, agentService), "conv-1");

    assertEquals("conv-1", body.get("conversationId").asString());
    JsonNode events = body.get("events");
    // user (1s) -> tool (2s) -> assistant (4s); the system message is dropped.
    assertEquals(3, events.size());
    assertEquals("USER_MESSAGE", events.get(0).get("kind").asString());
    assertEquals("find invoices", events.get(0).get("content").asString());
    assertEquals("TOOL_ACTIVITY", events.get(1).get("kind").asString());
    assertEquals("core_search_index", events.get(1).get("attributes").get("toolName").asString());
    assertEquals("ASSISTANT_MESSAGE", events.get(2).get("kind").asString());
    assertEquals("found 12", events.get(2).get("content").asString());
  }

  @Test
  @DisplayName(
      "863: a STAMPED delegate turn renders ONCE through the merge — one user item, one answer")
  void stampedDelegateTurnRendersExactlyOnce() {
    // The merge has no dedup (handleGet appends both planes), and `projectSv3RecordTurns` opens a
    // turn on EVERY user item, so a delegate turn present on both planes would draw twice: one turn
    // holding the question and one holding the activity. That is the objection ChatController
    // recorded against this design; the stamp answers it by making the RUN plane stop synthesising
    // (AgentRunQueryServiceThreadEventsTest), so here the agent plane contributes only the tool step.
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-stamped"))
        .thenReturn(List.of(storedUser(), storedAnswer()));

    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-stamped"), any()))
        .thenReturn(ThreadProjection.of(
            List.of(
                new InteractionEvent(
                    "c1:completed",
                    "conv-stamped",
                    Instant.parse("2026-08-25T10:00:02Z"),
                    InteractionEventKind.TOOL_ACTIVITY,
                    "agent",
                    "",
                    Map.of("callId", "c1", "toolName", "core_search_index", "status", "completed")))));

    JsonNode body =
        invokeGet(newController(conversationStore, agentService), "conv-stamped");
    JsonNode events = body.get("events");

    assertEquals(3, events.size());
    assertEquals(
        1, countOfKind(events, "USER_MESSAGE"), "exactly one question, from the answer plane");
    assertEquals(1, countOfKind(events, "ASSISTANT_MESSAGE"), "exactly one answer");
    assertEquals("delegate this", events.get(0).get("content").asString());
    assertEquals("TOOL_ACTIVITY", events.get(1).get("kind").asString());
    assertEquals("the answer", events.get(2).get("content").asString());
  }

  @Test
  @DisplayName(
      "863 A-3: the store plane carries the SAME evidence attributes the run plane's done carried")
  void storePlaneEvidenceMatchesTheRunPlanesTerminalAnswer() {
    // The parity this makes real rather than wished for: suppressing the run-plane `done` (A-2) drops
    // three attributes the store plane did not used to carry. Both sides are projected here from ONE
    // done payload — the run plane through AgentInteractionMapper, the store plane through chatTurn
    // over the row shape ConversationEngine.persistedAssistant writes (pinned against the real engine
    // by ConversationEngineTest#delegateDispatchRecordsBothTurnsWithEvidence).
    Map<String, Object> runPlaneAttributes =
        io.justsearch.agent.AgentInteractionMapper.fromRunEvent(
                Map.of(
                    "timestamp", "2026-08-25T10:00:04Z",
                    "eventType", "done",
                    "payload", donePayload()),
                "conv-stamped")
            .orElseThrow()
            .attributes();

    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-stamped")).thenReturn(List.of(storedAnswer()));
    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-stamped"), any()))
        .thenReturn(ThreadProjection.of(List.of()));

    JsonNode storePlane =
        invokeGet(newController(conversationStore, agentService), "conv-stamped")
            .get("events")
            .get(0)
            .get("attributes");

    for (String key : List.of("sources", "citations", "citationScorer", "disposition")) {
      assertEquals(
          MAPPER.valueToTree(runPlaneAttributes.get(key)),
          storePlane.get(key),
          () -> "the two planes must project '" + key + "' identically");
    }
    // Honestly absent on BOTH planes: the agent `done` produces neither, and a zero would not be true.
    assertTrue(!runPlaneAttributes.containsKey("calibration") && !storePlane.has("calibration"));
    assertTrue(!runPlaneAttributes.containsKey("claimMatches") && !storePlane.has("claimMatches"));
  }

  @Test
  @DisplayName(
      "871 §3b: a suppressed answer's TRAILING thinking lands on the answer, not on the tool before it")
  void trailingReasoningLandsOnTheAnswerNotOnThePrecedingTool() {
    // THE CHRONOLOGY DEFECT (owner-observed 2026-08-26; 863 §4.A.5 F4's "KNOWN INVERSION"). A
    // stamped run's terminal answer is suppressed on the action plane, so the block the fold had
    // attached to it lost its carrier. 863 re-homed it onto the run's LAST SURVIVING event — the
    // search tool step, which happened BEFORE the thinking — and `sv3-record.ts` draws a carrier's
    // blocks ABOVE the carrier, so the transcript read "planned → analysed the results → [the search
    // that produced them]".
    //
    // The fix is here rather than in the FE because this is the only place both planes are visible:
    // the block's true carrier is the ANSWER plane's copy of that same answer, whose timestamp is
    // after the tool's. Asserting on the wire (not on rendering) is what makes this a chronology
    // test: given the FE's draw-above-carrier rule, carrier == answer IS "after the tool".
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-chrono"))
        .thenReturn(List.of(storedUser(), storedAnswerOfRun("run-1")));

    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-chrono"), any()))
        .thenReturn(
            new ThreadProjection(
                List.of(
                    new InteractionEvent(
                        "c1:completed",
                        "conv-chrono",
                        Instant.parse("2026-08-25T10:00:02Z"),
                        InteractionEventKind.TOOL_ACTIVITY,
                        "agent",
                        "",
                        Map.of("callId", "c1", "toolName", "core_search_index", "status", "completed"))),
                Map.of("run-1", List.of(Map.of("text", "the results are thin", "durationMs", 1000)))));

    JsonNode events =
        invokeGet(newController(conversationStore, agentService), "conv-chrono")
            .get("events");

    assertEquals(3, events.size());
    assertEquals("TOOL_ACTIVITY", events.get(1).get("kind").asString());
    assertEquals("ASSISTANT_MESSAGE", events.get(2).get("kind").asString());
    // The block is on the answer…
    JsonNode blocks = events.get(2).get("attributes").get("reasoning");
    assertEquals(1, blocks.size(), "the orphaned block reaches the answer it was thought for");
    assertEquals("the results are thin", blocks.get(0).get("text").asString());
    // …and NOT on the tool step. Both halves are needed: attaching it to both would render it twice,
    // and attaching it only to the tool is precisely the defect.
    assertTrue(
        events.get(1).get("attributes").get("reasoning") == null,
        "the tool step must not carry a thought that came after it");
  }

  @Test
  @DisplayName("871 §3b: a run whose answer row projects no carrier drops the block, never misplaces it")
  void trailingReasoningWithNoMatchingAnswerIsNotMisplaced() {
    // The honest degradation. If the action plane hands across a run the answer plane does not hold
    // (it cannot, today — `answeredRunIds` is read off those very rows — but the merge must not
    // depend on that), the block stays on disk unrendered rather than landing on some other turn.
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-orphan"))
        .thenReturn(List.of(storedUser(), storedAnswerOfRun("run-1")));

    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-orphan"), any()))
        .thenReturn(
            new ThreadProjection(
                List.of(
                    new InteractionEvent(
                        "c1:completed",
                        "conv-orphan",
                        Instant.parse("2026-08-25T10:00:02Z"),
                        InteractionEventKind.TOOL_ACTIVITY,
                        "agent",
                        "",
                        Map.of("callId", "c1", "toolName", "core_search_index", "status", "completed"))),
                Map.of("run-9", List.of(Map.of("text", "homeless", "durationMs", 1)))));

    JsonNode events =
        invokeGet(newController(conversationStore, agentService), "conv-orphan")
            .get("events");

    for (JsonNode e : events) {
      assertTrue(
          e.get("attributes").get("reasoning") == null,
          "an unmatched block lands nowhere, rather than on the wrong turn");
    }
  }

  private static int countOfKind(JsonNode events, String kind) {
    int n = 0;
    for (JsonNode e : events) {
      if (kind.equals(e.get("kind").asString())) {
        n++;
      }
    }
    return n;
  }

  private static Map<String, Object> donePayload() {
    Map<String, Object> done = new java.util.LinkedHashMap<>();
    done.put("finalResponse", "the answer");
    done.put("sources", List.of(Map.of("parentDocId", "doc-7", "chunkIndex", 0, "path", "a/b.md")));
    done.put("citations", List.of(Map.of("sentenceText", "the answer", "sourceIndex", 0)));
    done.put("citationScorer", "cross-encoder");
    done.put("disposition", "BUDGET_EXHAUSTED");
    return done;
  }

  /** The user row the engine appends before the run starts. */
  private static Map<String, Object> storedUser() {
    return Map.of(
        "id", "11111111-1111-1111-1111-111111111111",
        "role", "user",
        "content", "delegate this",
        "ts", "2026-08-25T10:00:01Z");
  }

  /** The same row, stamped with the run that produced it (what makes the run "answered", 863 F2). */
  private static Map<String, Object> storedAnswerOfRun(String runId) {
    Map<String, Object> row = storedAnswer();
    row.put("runId", runId);
    return row;
  }

  /** The assistant row {@code ConversationEngine.persistedAssistant} writes at the terminal done. */
  private static Map<String, Object> storedAnswer() {
    Map<String, Object> row = new java.util.LinkedHashMap<>(donePayload());
    row.remove("finalResponse");
    row.put("id", "22222222-2222-2222-2222-222222222222");
    row.put("role", "assistant");
    row.put("content", "the answer");
    row.put("ts", "2026-08-25T10:00:04Z");
    return row;
  }

  @Test
  @DisplayName("empty conversation yields an empty events array")
  void emptyConversation() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("nope")).thenReturn(List.of());
    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("nope"), any()))
        .thenReturn(ThreadProjection.of(List.of()));

    JsonNode body = invokeGet(newController(conversationStore, agentService), "nope");
    assertEquals(0, body.get("events").size());
  }

  @Test
  @DisplayName("561 P-A: a chat turn's persisted citations + calibration are surfaced on the thread event (evidence on the record)")
  void surfacesEvidenceFromTheRecord() {
    // NOTE: this is the PROJECTION half of evidence-on-record. That the production pipeline actually
    // PRODUCES this persisted state — an EPHEMERAL RAG turn writing citations + calibration under the
    // conversationId — is proven by SubstrateDrivenEngineTest
    // #ephemeralRecordsToThreadPersistsEvidenceUnderConversationId. Both halves together close the
    // loop; neither alone is sufficient (the pre-561 gap was a green projection test over a state the
    // pipeline never created).
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-e"))
        .thenReturn(
            List.of(
                Map.of(
                    "id", "a1",
                    "role", "assistant",
                    "content", "grounded answer",
                    "ts", "2026-01-01T00:00:01Z",
                    "citations", List.of(Map.of("parentDocId", "doc-1", "startChar", 0)),
                    "calibration", Map.of("bestChunkScore", 0.91, "retrievalCoverage", 0.5))));
    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-e"), any()))
        .thenReturn(ThreadProjection.of(List.of()));

    JsonNode body =
        invokeGet(newController(conversationStore, agentService), "conv-e");
    JsonNode ev = body.get("events").get(0);
    assertEquals("ASSISTANT_MESSAGE", ev.get("kind").asString());
    JsonNode cites = ev.get("attributes").get("citations");
    assertEquals(1, cites.size());
    assertEquals("doc-1", cites.get(0).get("parentDocId").asString());
    // The producer-owned calibration is projected too (rendered FROM the record, not re-derived).
    JsonNode cal = ev.get("attributes").get("calibration");
    assertEquals(0.91, cal.get("bestChunkScore").asDouble());
  }

  @Test
  @DisplayName("848 §2.3: a chat turn's persisted reasoning is lifted onto the thread event")
  void surfacesReasoningFromTheRecord() {
    // The projection half of reasoning-on-record; SubstrateDrivenEngineTest
    // #reasoningPersistsOnTheAssistantRecord proves the pipeline actually writes this state.
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-r"))
        .thenReturn(
            List.of(
                Map.of(
                    "id", "a1",
                    "role", "assistant",
                    "content", "the answer",
                    "ts", "2026-01-01T00:00:01Z",
                    "reasoning", List.of(Map.of("text", "weighed the options", "durationMs", 1840))),
                Map.of(
                    "id", "a2",
                    "role", "assistant",
                    "content", "a turn that did not think",
                    "ts", "2026-01-01T00:00:02Z"),
                Map.of(
                    "id", "a3",
                    "role", "assistant",
                    "content", "a malformed record",
                    "ts", "2026-01-01T00:00:03Z",
                    "reasoning", "not a list")));
    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-r"), any()))
        .thenReturn(ThreadProjection.of(List.of()));

    JsonNode body =
        invokeGet(newController(conversationStore, agentService), "conv-r");
    JsonNode blocks = body.get("events").get(0).get("attributes").get("reasoning");
    assertEquals(1, blocks.size());
    assertEquals("weighed the options", blocks.get(0).get("text").asString());
    assertEquals(1840, blocks.get(0).get("durationMs").asInt());
    assertTrue(
        body.get("events").get(1).get("attributes").get("reasoning") == null,
        "a turn that did not think carries no key");
    assertTrue(
        body.get("events").get(2).get("attributes").get("reasoning") == null,
        "a non-list value is dropped, mirroring the claimMatches guard");
  }

  /** A test {@code DataKeyState} with a fixed key whose lock state the test toggles (mirrors
   * FileConversationStoreTest's FakeKey). */
  private static final class FakeDataKeyState implements io.justsearch.agent.api.encryption.DataKeyState {
    private final byte[] dek = new byte[32];
    private boolean locked;

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public boolean locked() {
      return locked;
    }

    @Override
    public byte[] dek() {
      if (locked) throw new io.justsearch.agent.api.encryption.KeyLockedException();
      return dek;
    }
  }

  @Test
  @DisplayName(
      "629 LAYER regression (tempdoc 727) — GET /api/thread/{id} over a REAL locked"
          + " FileConversationStore returns 200 with the action-plane events intact, NOT a 500 for"
          + " the whole thread (pre-fix: loadHistory's KeyLockedException hit handleGet's"
          + " catch-all Exception handler and 500'd everything, including the unencrypted agent"
          + " activity)")
  void lockedConversationStoreDoesNotFiveHundredTheThread(@TempDir java.nio.file.Path tmp) {
    var key = new FakeDataKeyState();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var conversationStore =
        new io.justsearch.app.services.conversation.FileConversationStore(tmp, cipher);
    conversationStore.appendMessage(
        "conv-locked", "core.free-chat", Map.of("role", "user", "content", "q1"));
    key.locked = true;

    AgentService agentService = mock(AgentService.class);
    when(agentService.threadProjection(eq("conv-locked"), any()))
        .thenReturn(ThreadProjection.of(
            List.of(
                new InteractionEvent(
                    "c1:completed",
                    "conv-locked",
                    Instant.parse("2026-01-01T00:00:02Z"),
                    InteractionEventKind.TOOL_ACTIVITY,
                    "agent",
                    "",
                    Map.of("callId", "c1", "toolName", "core_search_index", "status", "completed")))));

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn("conv-locked");
    when(ctx.contentType(anyString())).thenReturn(ctx);
    AtomicInteger status = new AtomicInteger(200);
    when(ctx.status(anyInt()))
        .thenAnswer(
            inv -> {
              status.set(inv.getArgument(0, Integer.class));
              return ctx;
            });
    AtomicReference<byte[]> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0, byte[].class));
              return ctx;
            })
        .when(ctx)
        .result(any(byte[].class));

    newController(conversationStore, agentService).handleGet(ctx);

    assertEquals(200, status.get(), "a locked conversation store must not 500 the whole unified thread");
    JsonNode body;
    try {
      body = MAPPER.readTree(captured.get());
    } catch (Exception e) {
      throw new IllegalStateException("could not parse thread response", e);
    }
    JsonNode events = body.get("events");
    // The locked chat message is filtered by chatTurn (role "locked" isn't user/assistant, same as
    // the existing system-message filter) but the action-plane agent-run event — which is NOT
    // encrypted — still renders, proving the thread degrades instead of going blank.
    assertEquals(1, events.size());
    assertEquals("TOOL_ACTIVITY", events.get(0).get("kind").asString());
  }

  /** Invokes {@code POST /api/thread/{id}/events}, capturing the response status + JSON body. */
  private JsonNode invokePostEvent(
      InteractionThreadController controller, String id, String requestBody, AtomicInteger statusOut) {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(id);
    when(ctx.body()).thenReturn(requestBody);
    when(ctx.status(anyInt()))
        .thenAnswer(
            inv -> {
              statusOut.set(inv.getArgument(0, Integer.class));
              return ctx;
            });
    AtomicReference<Object> capturedJson = new AtomicReference<>();
    when(ctx.json(any()))
        .thenAnswer(
            inv -> {
              capturedJson.set(inv.getArgument(0));
              return ctx;
            });
    controller.handlePostEvent(ctx);
    return capturedJson.get() == null ? null : MAPPER.valueToTree(capturedJson.get());
  }

  @Test
  @DisplayName("S4b: POST /api/thread/{id}/events persists a SEARCH event and returns its id (201)")
  void postSearchEventPersistsAndReturnsId() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    AgentService agentService = mock(AgentService.class);
    when(agentService.appendSearchEvent(eq("conv-1"), any())).thenReturn("conv-1:search:123");

    AtomicInteger status = new AtomicInteger(-1);
    JsonNode body =
        invokePostEvent(
            newController(conversationStore, agentService),
            "conv-1",
            "{\"kind\":\"SEARCH\",\"query\":\"invoices\",\"mode\":\"hybrid\","
                + "\"matchCount\":42,\"resultCount\":10,\"docIds\":[\"a.pdf\",\"b.pdf\"],"
                + "\"executedAt\":\"2026-07-06T00:00:00Z\"}",
            status);

    assertEquals(201, status.get());
    assertEquals("conv-1:search:123", body.get("id").asString());

    @SuppressWarnings("unchecked")
    Map<String, Object>[] captured = new Map[1];
    verify(agentService)
        .appendSearchEvent(
            eq("conv-1"),
            org.mockito.ArgumentMatchers.argThat(
                attrs -> {
                  captured[0] = attrs;
                  return true;
                }));
    assertEquals("invoices", captured[0].get("query"));
    assertEquals("hybrid", captured[0].get("mode"));
    assertEquals(List.of("a.pdf", "b.pdf"), captured[0].get("docIds"));
    assertEquals("2026-07-06T00:00:00Z", captured[0].get("executedAt"));
  }

  @Test
  @DisplayName("S4b: an unsupported event kind is rejected with 400")
  void postEventRejectsUnsupportedKind() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    AgentService agentService = mock(AgentService.class);

    AtomicInteger status = new AtomicInteger(-1);
    invokePostEvent(
        newController(conversationStore, agentService),
        "conv-1",
        "{\"kind\":\"BOGUS\"}",
        status);

    assertEquals(400, status.get());
  }

  @Test
  @DisplayName("S4b: a SEARCH body missing required fields is rejected with 400")
  void postEventRejectsIncompleteSearchBody() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    AgentService agentService = mock(AgentService.class);

    AtomicInteger status = new AtomicInteger(-1);
    invokePostEvent(
        newController(conversationStore, agentService),
        "conv-1",
        "{\"kind\":\"SEARCH\",\"query\":\"invoices\"}",
        status);

    assertEquals(400, status.get());
  }

  @Test
  @DisplayName("S4b: a null id from the write path (persistence failure) surfaces as 500")
  void postEventSurfaces500WhenWritePathFails() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    AgentService agentService = mock(AgentService.class);
    when(agentService.appendSearchEvent(anyString(), any())).thenReturn(null);

    AtomicInteger status = new AtomicInteger(-1);
    invokePostEvent(
        newController(conversationStore, agentService),
        "conv-1",
        "{\"kind\":\"SEARCH\",\"query\":\"invoices\",\"mode\":\"hybrid\","
            + "\"matchCount\":42,\"resultCount\":10,\"docIds\":[]}",
        status);

    assertEquals(500, status.get());
  }
}

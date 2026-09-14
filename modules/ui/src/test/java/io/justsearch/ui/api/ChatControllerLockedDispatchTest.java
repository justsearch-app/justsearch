package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 734 round-14 F4 — {@code POST /api/chat/dispatch} must not accept a turn it will drop.
 * With chat persistence encrypted and locked, every append throws, so the question reached no store
 * and no answer: the sandbox round observed two submissions answered {@code 200} whose text appears
 * nowhere in the transcript fetched after unlock — no user message, no reply, no placeholder. The
 * history read path already answers this exact condition {@code 423 Locked}; these tests pin the
 * write path to the same answer, and pin that a dispatch which records nothing is left serviceable.
 */
final class ChatControllerLockedDispatchTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ConversationShapeRef PERSISTENT_SHAPE =
      new ConversationShapeRef("core.test-persistent-chat");
  private static final ConversationShapeRef EPHEMERAL_SHAPE =
      new ConversationShapeRef("core.test-ephemeral");

  @Test
  @DisplayName("734 F4 — a locked dispatch is a typed 423, not a 200 that swallows the question")
  void lockedDispatchIsTypedLockedNot200() {
    FakeStore store = new FakeStore();
    store.locked = true;
    Captured c = dispatch(store, "{\"sessionId\":\"s1\",\"prompt\":\"what did I save?\"}");

    assertEquals(423, c.status(), "the write path answers the same status the history read does");
    assertEquals("STORE_LOCKED", c.body().get("errorCode").asString());
    assertTrue(c.body().get("locked").asBoolean(), "the wire names the condition a client can act on");
    assertTrue(
        c.body().get("error").asString().toLowerCase(java.util.Locale.ROOT).contains("unlock"),
        "the message names the remedy, not just the failure: " + c.body().get("error"));
  }

  @Test
  @DisplayName("734 F4 — a locked dispatch persists nothing AND never opens the SSE stream")
  void lockedDispatchNeitherPersistsNorStreams() {
    FakeStore store = new FakeStore();
    store.locked = true;
    Captured c = dispatch(store, "{\"sessionId\":\"s1\",\"prompt\":\"what did I save?\"}");

    assertTrue(store.appended.isEmpty(), "the refused turn must not reach the store");
    assertEquals(0, c.ai().calls, "a refused turn must not be sent to the model either");
    // The 200 the round observed came from the SSE headers committing before the engine ran; the
    // refusal has to land BEFORE that or the status can no longer be set.
    verify(c.ctx(), never()).contentType("text/event-stream; charset=utf-8");
  }

  @Test
  @DisplayName("734 F4 — after unlock the same dispatch runs the normal 200 path and records the turn")
  void unlockedDispatchIsUnaffected() {
    FakeStore store = new FakeStore();
    store.locked = false;
    Captured c = dispatch(store, "{\"sessionId\":\"s1\",\"prompt\":\"what did I save?\"}");

    assertNotEquals(423, c.status(), "an unlocked store is not refused");
    assertEquals(200, c.status(), "the handler leaves the default 200 SSE response in place");
    assertNull(c.json().get(), "no JSON error body on the normal streaming path");
    verify(c.ctx()).contentType("text/event-stream; charset=utf-8");
    assertEquals(1, c.ai().calls, "the turn reached the model");
    List<Map<String, Object>> recorded = store.appended.get("s1");
    assertEquals(2, recorded.size(), "the user turn and the assistant turn are both recorded");
    assertEquals("what did I save?", recorded.get(0).get("content"));
    assertEquals("assistant", recorded.get(1).get("role"));
  }

  @Test
  @DisplayName("734 F4 — a locked dispatch that would record NOTHING stays serviceable")
  void lockedDispatchOfNonRecordingShapeStillRuns() {
    FakeStore store = new FakeStore();
    store.locked = true;
    // An EPHEMERAL shape with neither sessionId nor conversationId writes to no store, so the lock
    // cannot discard anything: gating bluntly on "locked" would break a turn that works fine.
    Captured c = dispatch(store, EPHEMERAL_SHAPE, "{\"prompt\":\"summarize this\"}");

    assertNotEquals(423, c.status(), "a turn that persists nothing has nothing to lose to the lock");
    assertEquals(1, c.ai().calls, "it ran");
    assertTrue(store.appended.isEmpty(), "and, as declared, wrote nothing");
  }

  @Test
  @DisplayName("734 F4 — the gate reads the store's own lock state, session by session")
  void lockedEphemeralShapeStillRefusesWhenItWouldRecordToAThread() {
    FakeStore store = new FakeStore();
    store.locked = true;
    // Same EPHEMERAL shape, but the unified surface stamps `conversationId` on every dispatch — that
    // IS a write key (tempdoc 561 P-A/P-B), so this turn would be recorded and dropped.
    Captured c = dispatch(store, EPHEMERAL_SHAPE, "{\"conversationId\":\"uc-1\",\"prompt\":\"hi\"}");

    assertEquals(423, c.status());
    assertTrue(store.appended.isEmpty());
  }

  @Test
  @DisplayName(
      "863 A-9 — a DELEGATE dispatch against a locked store is refused 423 instead of running with"
          + " a swallowed writeMeta")
  void lockedDelegateDispatchIsRefusedRatherThanSilentlyDropped() {
    // The pre-863 behaviour was accepted-and-dropped on BOTH planes: `core.agent-run` had no
    // conversation-store write key, so this gate said "nothing to lose", and on the run plane
    // `AgentRunStore.startRun`'s `writeMeta` threw against the locked key and was swallowed by a bare
    // `catch (Exception e) { LOG.warn(...) }` (AgentRunStore.java:192-194). The run executed, nothing
    // durable was written, and the reader was told nothing. Declaring the shape recordsToThread gives
    // the dispatch a write key, which is what makes this gate answer — the fix for the silent drop is
    // a consequence of the stamp, not a separate patch.
    FakeStore store = new FakeStore();
    store.locked = true;
    AtomicInteger agentRuns = new AtomicInteger();

    Captured c = dispatchDelegate(store, agentRuns);

    assertEquals(423, c.status(), "the reader learns the store is locked instead of losing the run");
    assertEquals("STORE_LOCKED", c.body().get("errorCode").asString());
    assertEquals(0, agentRuns.get(), "and the agent loop never started");
    assertTrue(store.appended.isEmpty());
  }

  @Test
  @DisplayName("863 A-9 — the same delegate dispatch runs normally once the store is unlocked")
  void unlockedDelegateDispatchRunsAndRecords() {
    FakeStore store = new FakeStore();
    store.locked = false;
    AtomicInteger agentRuns = new AtomicInteger();

    Captured c = dispatchDelegate(store, agentRuns);

    assertNotEquals(423, c.status());
    assertEquals(1, agentRuns.get(), "the delegate run executed");
    assertEquals(
        2, store.appended.get("uc-locked").size(), "and both of its turns reached the record");
  }

  @Test
  @DisplayName(
      "863 F3 — /api/chat/agent answers the locked store 423 too, not a generic BAD_REQUEST SSE"
          + " error after the stream is already committed")
  void lockedLegacyAgentRouteIsRefusedBeforeTheStreamCommits() {
    // The route the FE still falls back to (`AgentSessionController.streamViaHost`'s fallbackUrl)
    // commits SSE headers BEFORE parsing the body, and never asked the lock question — it did not
    // have to, because a delegate run had no conversation-store write key. Declaring the shape
    // recordsToThread gives it one, so the engine's user-turn append would now throw INTO the
    // committed stream and the reader would be told "BAD_REQUEST" about a locked store.
    FakeStore store = new FakeStore();
    store.locked = true;
    AtomicInteger agentRuns = new AtomicInteger();

    Captured c = legacyAgentRun(store, agentRuns);

    assertEquals(423, c.status(), "the same typed answer its two sibling dispatch routes give");
    assertEquals("STORE_LOCKED", c.body().get("errorCode").asString());
    assertTrue(c.body().get("locked").asBoolean());
    assertEquals(0, agentRuns.get(), "and the agent loop never started");
    // The SSE stream was never opened, which is what makes a status settable at all.
    verify(c.ctx(), never()).contentType("text/event-stream; charset=utf-8");
  }

  @Test
  @DisplayName("863 F3 — the same legacy route is untouched when the store is unlocked")
  void unlockedLegacyAgentRouteRuns() {
    FakeStore store = new FakeStore();
    store.locked = false;
    AtomicInteger agentRuns = new AtomicInteger();

    Captured c = legacyAgentRun(store, agentRuns);

    assertNotEquals(423, c.status());
    assertEquals(1, agentRuns.get(), "the delegate run executed");
    assertEquals(2, store.appended.get("uc-locked").size(), "and both turns reached the record");
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private static Captured legacyAgentRun(FakeStore store, AtomicInteger agentRuns) {
    io.justsearch.agent.api.AgentService agent =
        new StubDelegateAgent(
            sink -> {
              agentRuns.incrementAndGet();
              sink.accept(new io.justsearch.agent.api.AgentEvent.AgentDone("the answer", 1, 0, 9));
            });
    ConversationEngine engine =
        new ConversationEngine(
            io.justsearch.app.services.conversation.CoreConversationShapeCatalog.catalog(),
            List.of(
                new io.justsearch.app.services.conversation.ToolIteratingShapeRunner(() -> agent)),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            OnlineAiService::unavailable,
            store);
    AgentController controller =
        new AgentController(new io.justsearch.core.execution.TestEngineExecutors(),
            () -> agent, engine, new AgentSseWriter(new SseWriter(null), () -> agent, null), null);

    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<Object> json = new AtomicReference<>();
    String requestBody =
        "{\"conversationId\":\"uc-locked\",\"maxIterations\":1,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"delegate this\"}]}";
    Context ctx = mockContext(requestBody, status, json);
    controller.handleRunStream(ctx);
    JsonNode parsed = json.get() == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(json.get());
    return new Captured(status.get(), parsed, json, ctx, null);
  }

  private static Captured dispatchDelegate(FakeStore store, AtomicInteger agentRuns) {
    io.justsearch.agent.api.AgentService agent =
        new StubDelegateAgent(
            sink -> {
              agentRuns.incrementAndGet();
              sink.accept(new io.justsearch.agent.api.AgentEvent.AgentDone("the answer", 1, 0, 9));
            });
    ConversationEngine engine =
        new ConversationEngine(
            io.justsearch.app.services.conversation.CoreConversationShapeCatalog.catalog(),
            List.of(
                new io.justsearch.app.services.conversation.ToolIteratingShapeRunner(() -> agent)),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            OnlineAiService::unavailable,
            store);
    ChatController controller = new ChatController(new io.justsearch.core.execution.TestEngineExecutors(), engine, new SseWriter(null), null, store);

    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<Object> json = new AtomicReference<>();
    String requestBody =
        "{\"shapeId\":\"core.agent-run\",\"conversationId\":\"uc-locked\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"delegate this\"}],\"maxIterations\":1}";
    Context ctx = mockContext(requestBody, status, json);
    try {
      controller.dynamicHandler("/api/chat/dispatch").handle(ctx);
    } catch (Exception e) {
      throw new AssertionError("dispatch threw", e);
    }
    JsonNode parsed = json.get() == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(json.get());
    return new Captured(status.get(), parsed, json, ctx, null);
  }

  /** The minimum {@code AgentService} the delegate shape's runner needs to reach {@code done}. */
  private record StubDelegateAgent(
      java.util.function.Consumer<java.util.function.Consumer<io.justsearch.agent.api.AgentEvent>>
          script)
      implements io.justsearch.agent.api.AgentService {

    @Override
    public void runAgent(
        io.justsearch.agent.api.AgentRequest request,
        java.util.function.Consumer<io.justsearch.agent.api.AgentEvent> eventConsumer,
        EngineContext engineContext) {
      script.accept(eventConsumer);
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public List<io.justsearch.agent.api.registry.Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<io.justsearch.agent.api.registry.Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public List<Map<String, Object>> sessionEvents(String sessionId) {
      return List.of();
    }

    @Override
    public List<io.justsearch.agent.api.interaction.InteractionEvent> threadEvents(
        String conversationId) {
      return List.of();
    }
  }

  private record Captured(
      int status, JsonNode body, AtomicReference<Object> json, Context ctx, ScriptedAi ai) {}

  private static Captured dispatch(FakeStore store, String body) {
    return dispatch(store, PERSISTENT_SHAPE, body);
  }

  private static Captured dispatch(FakeStore store, ConversationShapeRef shapeId, String body) {
    ScriptedAi ai = new ScriptedAi("the answer");
    ConversationEngine engine =
        new ConversationEngine(
            ConversationShapeCatalog.of(
                "core", List.of(persistentShape(), ephemeralShape())),
            List.of(),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of(new PromptEchoInjector())),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            () -> ai,
            store);
    ChatController controller = new ChatController(new io.justsearch.core.execution.TestEngineExecutors(), engine, new SseWriter(null), null, store);

    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<Object> json = new AtomicReference<>();
    String requestBody =
        body.replaceFirst("\\{", "{\"shapeId\":\"" + shapeId.value() + "\",");
    Context ctx = mockContext(requestBody, status, json);
    try {
      controller.dynamicHandler("/api/chat/dispatch").handle(ctx);
    } catch (Exception e) {
      throw new AssertionError("dispatch threw", e);
    }
    JsonNode parsed = json.get() == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(json.get());
    return new Captured(status.get(), parsed, json, ctx, ai);
  }

  private static Context mockContext(String body, AtomicInteger status, AtomicReference<Object> json) {
    Context ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/api/chat/dispatch");
    when(ctx.body()).thenReturn(body);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    // The SSE writer takes a per-context lock via attributeOrCompute; a null would NPE the write.
    when(ctx.attributeOrCompute(anyString(), any())).thenReturn(new Object());
    doAnswer(inv -> {
          status.set(inv.getArgument(0, Integer.class));
          return ctx;
        })
        .when(ctx)
        .status(anyInt());
    doAnswer(inv -> {
          json.set(inv.getArgument(0));
          return ctx;
        })
        .when(ctx)
        .json(any(Object.class));
    return ctx;
  }

  private static ConversationShape persistentShape() {
    return shape(PERSISTENT_SHAPE, PersistenceMode.PERSISTENT);
  }

  private static ConversationShape ephemeralShape() {
    return shape(EPHEMERAL_SHAPE, PersistenceMode.EPHEMERAL);
  }

  private static ConversationShape shape(ConversationShapeRef id, PersistenceMode persistence) {
    return new ConversationShape(
        id,
        new Presentation(
            new I18nKey("test.label"), new I18nKey("test.desc"), Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        persistence,
        List.of(),
        List.of(PromptEchoInjector.ID),
        List.of(),
        null,
        List.of(),
        true);
  }

  /** Stands in for {@code core.user-prompt}: turns the request's prompt into the user message. */
  private static final class PromptEchoInjector implements ContextInjector {
    static final String ID = "core.test-prompt-echo";

    @Override
    public String id() {
      return ID;
    }

    @Override
    public InjectorResult inject(ConversationContext ctx) {
      Object prompt = ctx.requestBody().get("prompt");
      Map<String, Object> msg = new LinkedHashMap<>();
      msg.put("role", "user");
      msg.put("content", prompt == null ? "" : prompt.toString());
      return InjectorResult.messagesOnly(List.of(msg));
    }
  }

  /**
   * A conversation store whose lock state the test toggles. Locked appends throw
   * {@code KeyLockedException} — exactly what {@code StoreCipher.seal} does through
   * {@code FileConversationStore.appendMessage} when the data key is locked.
   */
  private static final class FakeStore implements ConversationStore {
    final Map<String, List<Map<String, Object>>> appended = new LinkedHashMap<>();
    boolean locked;

    @Override
    public boolean isLocked() {
      return locked;
    }

    @Override
    public List<Map<String, Object>> loadHistory(String sessionId) {
      if (locked) {
        throw new KeyLockedException();
      }
      return appended.getOrDefault(sessionId, List.of());
    }

    @Override
    public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {
      if (locked) {
        throw new KeyLockedException();
      }
      appended.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new LinkedHashMap<>(message));
    }

    @Override
    public List<SessionSummary> listSessions(String shapeId, int limit) {
      return List.of();
    }

    @Override
    public Optional<SessionSummary> getSessionMeta(String sessionId) {
      return Optional.empty();
    }

    @Override
    public void deleteSession(String sessionId) {}

    @Override
    public void branchFrom(String parentSessionId, String branchPointMessageId, String newSessionId) {}

    @Override
    public void setContextFloor(String sessionId, String floorMessageId) {}

    @Override
    public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
      return loadHistory(sessionId);
    }

    @Override
    public void compactContext(String sessionId, String floorMessageId, String summaryText) {}

    @Override
    public void excludeMessage(String sessionId, String messageId, boolean excluded) {}

    @Override
    public List<String> excludedMessageIds(String sessionId) {
      return List.of();
    }
  }

  /** Minimal streaming LLM: one scripted response, counting calls. */
  private static final class ScriptedAi implements OnlineAiService {
    private final String response;
    int calls;

    ScriptedAi(String response) {
      this.response = response;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      calls++;
      sink.onContent().accept(response);
      sink.onComplete().accept("stop");
    }
  }
}

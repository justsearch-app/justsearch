/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.DemandScope;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.inference.chat.ChatResidency;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/chat/conversations/{sessionId}/auto-title}: the model's name for a conversation,
 * which the web UI asks for on its own once the first question is answered (scenario S7: work the
 * window starts by itself must not count as a person acting, and must not bypass the chat lane).
 *
 * <p>And the conditional title write ({@code If-None-Match: *}) a browser's late legacy name uses,
 * so that it can never replace a name a person gave the conversation meanwhile.
 */
final class ChatControllerAutoTitleTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.WEBVIEW,
      "local-webview", Optional.empty(), Optional.empty(), "tier", "http",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  /** What the model was asked, and under which declared demand. */
  private record Asked(String prompt, int maxTokens, SamplingParams sampling, DemandKind demand) {}

  private final FakeStore store = new FakeStore();
  private final List<Asked> asked = new ArrayList<>();
  private final List<DemandKind> demanded = new ArrayList<>();
  private Function<String, CompletableFuture<String>> model =
      prompt -> CompletableFuture.completedFuture("Renewal Lock Failure");
  private ChatRefusal laneRefusal;

  @Test
  void namesAnUntitledConversationFromItsStoredFirstExchangeAsBackgroundInference() {
    String longQuestion = "why did the renewal fail? " + "x".repeat(300) + "TAIL-NOT-SENT";
    store.create("uc-1", List.of(
        message("user", longQuestion), message("assistant", "The lock held."),
        message("user", "and then?"), message("assistant", "It expired.")));

    Captured c = autoTitle("uc-1");

    assertEquals(200, c.status());
    assertTrue(c.body().get("generated").asBoolean());
    assertEquals("Renewal Lock Failure", c.body().get("title").asString());
    assertEquals("auto", c.body().get("titleSource").asString());
    assertEquals("Renewal Lock Failure", store.titles.get("uc-1"));
    assertEquals("auto", store.sources.get("uc-1"));

    assertEquals(List.of(DemandKind.BACKGROUND), demanded, "admitted by the lane as background");
    assertEquals(1, asked.size());
    Asked call = asked.get(0);
    assertEquals(DemandKind.BACKGROUND, call.demand(), "and declared so to the inference service");
    assertTrue(call.prompt().contains("why did the renewal fail?"));
    assertTrue(call.prompt().contains("The lock held."));
    assertFalse(call.prompt().contains("TAIL-NOT-SENT"), "each half clipped to 200 characters");
    assertFalse(call.prompt().contains("It expired."), "only the first exchange names it");
    assertEquals(ChatController.AUTO_TITLE_MAX_TOKENS, call.maxTokens());
    assertEquals(Boolean.FALSE, call.sampling().enableThinking(), "no reasoning pass for a title");
  }

  @Test
  void itsOnlyInputIsTheConversationId() {
    store.create("uc-1", List.of(message("user", "q"), message("assistant", "a")));
    Captured c = autoTitle("uc-1");
    assertEquals(200, c.status());
    verify(c.ctx(), never()).bodyAsClass(any());
    verify(c.ctx(), never()).body();
    verify(c.ctx(), never()).queryParam(anyString());
  }

  @Test
  void aNamedConversationKeepsItsNameAndNoModelIsAsked() {
    store.create("uc-1", List.of(message("user", "q"), message("assistant", "a")));
    store.setTitle("uc-1", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);

    Captured c = autoTitle("uc-1");

    assertEquals(200, c.status());
    assertFalse(c.body().get("generated").asBoolean());
    assertEquals("Renewal postmortem", c.body().get("title").asString());
    assertEquals("user", c.body().get("titleSource").asString());
    assertTrue(asked.isEmpty());
    assertTrue(demanded.isEmpty(), "not even a lane demand");
  }

  @Test
  void aRenameThatLandsWhileTheModelIsNamingItWins() {
    store.create("uc-1", List.of(message("user", "q"), message("assistant", "a")));
    model = prompt -> {
      store.setTitle("uc-1", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);
      return CompletableFuture.completedFuture("Renewal Lock Failure");
    };

    Captured c = autoTitle("uc-1");

    assertEquals(200, c.status());
    assertFalse(c.body().get("generated").asBoolean());
    assertEquals("Renewal postmortem", c.body().get("title").asString());
    assertEquals("Renewal postmortem", store.titles.get("uc-1"));
    assertEquals("user", store.sources.get("uc-1"));
  }

  @Test
  void aBusyLaneRefusesWithItsTypedReasonAndATimeToAskAgain() {
    store.create("uc-1", List.of(message("user", "q"), message("assistant", "a")));
    laneRefusal = new ChatRefusal(RefusalReason.BUSY, Optional.of(Duration.ofSeconds(10)), false);

    Captured c = autoTitle("uc-1");

    assertEquals(503, c.status());
    assertEquals("CHAT_UNAVAILABLE", c.body().get("errorCode").asString());
    assertEquals("BUSY", c.body().get("reason").asString());
    assertEquals(10_000L, c.body().get("retryAfterMs").asLong());
    assertTrue(asked.isEmpty(), "the model is not asked past a refusal");
    assertNull(store.titles.get("uc-1"));
  }

  @Test
  void aModelThatCannotAnswerNowIsATransientRefusal() {
    store.create("uc-1", List.of(message("user", "q"), message("assistant", "a")));
    model = prompt -> CompletableFuture.failedFuture(new java.io.IOException("connection refused"));

    Captured c = autoTitle("uc-1");

    assertEquals(503, c.status());
    assertEquals("SERVICE_UNAVAILABLE", c.body().get("errorCode").asString());
    assertEquals(ChatController.AUTO_TITLE_RETRY_AFTER_MS, c.body().get("retryAfterMs").asLong());
    assertNull(store.titles.get("uc-1"));
  }

  @Test
  void refusalsWaitingCannotChange() {
    assertEquals(404, autoTitle("uc-missing").status());
    store.create("uc-unanswered", List.of(message("user", "q")));
    Captured unanswered = autoTitle("uc-unanswered");
    assertEquals(409, unanswered.status());
    assertEquals("INVALID_STATE", unanswered.body().get("errorCode").asString());
    store.create("uc-long", List.of(message("user", "q"), message("assistant", "a")));
    model = prompt -> CompletableFuture.completedFuture("word ".repeat(40));
    assertEquals(422, autoTitle("uc-long").status());
    assertTrue(store.titles.isEmpty());
    assertTrue(demanded.size() == 1 && asked.size() == 1, "only the answered one reached the model");
  }

  @Test
  void quotesAroundTheModelsReplyAreNotPartOfTheName() {
    store.create("uc-1", List.of(message("user", "q"), message("assistant", "a")));
    model = prompt -> CompletableFuture.completedFuture("  \"Lease Terms\"\n");
    autoTitle("uc-1");
    assertEquals("Lease Terms", store.titles.get("uc-1"));
  }

  @Test
  void aConditionalTitleWriteNeverReplacesAName() {
    store.create("uc-1", List.of(message("user", "q")));
    store.setTitle("uc-1", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);

    Captured refused = setTitle("uc-1", "{\"title\":\"Legacy\",\"source\":\"user\"}", "*");
    assertEquals(412, refused.status());
    assertEquals("Renewal postmortem", refused.body().get("title").asString());
    assertEquals("user", refused.body().get("titleSource").asString());
    assertEquals("Renewal postmortem", store.titles.get("uc-1"));

    store.create("uc-2", List.of(message("user", "q")));
    Captured stored = setTitle("uc-2", "{\"title\":\"Legacy\",\"source\":\"user\"}", " * ");
    assertEquals(200, stored.status());
    assertEquals("Legacy", store.titles.get("uc-2"));

    // Without the precondition a person's rename replaces whatever is there, as before.
    Captured rename = setTitle("uc-1", "{\"title\":\"Person chose this\"}", null);
    assertEquals(200, rename.status());
    assertEquals("Person chose this", store.titles.get("uc-1"));
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private record Captured(int status, JsonNode body, Context ctx) {}

  private static Map<String, Object> message(String role, String content) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", role);
    m.put("content", content);
    return m;
  }

  private ChatController controller() {
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(inv -> {
      List<Map<String, Object>> messages = inv.getArgument(0);
      String prompt = String.valueOf(messages.get(0).get("content"));
      asked.add(new Asked(prompt, inv.getArgument(1), inv.getArgument(2), DemandScope.current()));
      return model.apply(prompt);
    });
    ChatController controller = new ChatController(new TestEngineExecutors(), null,
        new SseWriter(null), null, store, () -> ai);
    ChatResidency residency = mock(ChatResidency.class);
    try {
      when(residency.demand(any())).thenAnswer(inv -> {
        demanded.add(inv.getArgument(0, Demand.class).kind());
        if (laneRefusal != null) throw laneRefusal;
        return CompletableFuture.completedFuture(null);
      });
    } catch (ChatRefusal impossible) {
      throw new AssertionError(impossible);
    }
    controller.setResidencyGate(new ChatResidencyGate(() -> residency, null));
    return controller;
  }

  private Captured autoTitle(String sessionId) {
    return invoke(c -> c::handleAutoTitle, sessionId, null, null);
  }

  private Captured setTitle(String sessionId, String body, String ifNoneMatch) {
    return invoke(c -> c::handleSetTitle, sessionId, body, ifNoneMatch);
  }

  private interface HandlerPick {
    io.javalin.http.Handler pick(ChatController controller);
  }

  private Captured invoke(HandlerPick pick, String sessionId, String body, String ifNoneMatch) {
    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<Object> json = new AtomicReference<>();
    Context ctx = mock(Context.class);
    when(ctx.pathParam("sessionId")).thenReturn(sessionId);
    when(ctx.<EngineContext>attribute(anyString())).thenReturn(CONTEXT);
    when(ctx.header("If-None-Match")).thenReturn(ifNoneMatch);
    if (body != null) {
      when(ctx.bodyAsClass(Map.class)).thenAnswer(inv -> MAPPER.readValue(body, Map.class));
    }
    doAnswer(inv -> {
      status.set(inv.getArgument(0, Integer.class));
      return ctx;
    }).when(ctx).status(anyInt());
    doAnswer(inv -> {
      json.set(inv.getArgument(0));
      return ctx;
    }).when(ctx).json(any(Object.class));
    try {
      pick.pick(controller()).handle(ctx);
    } catch (Exception e) {
      throw new AssertionError("handler threw", e);
    }
    JsonNode parsed = json.get() == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(json.get());
    return new Captured(status.get(), parsed, ctx);
  }

  /** A conversation store holding history and names in memory. */
  private static final class FakeStore implements ConversationStore {
    final Map<String, List<Map<String, Object>>> history = new LinkedHashMap<>();
    final Map<String, String> titles = new LinkedHashMap<>();
    final Map<String, String> sources = new LinkedHashMap<>();

    void create(String sessionId, List<Map<String, Object>> messages) {
      history.put(sessionId, messages);
    }

    @Override
    public void setTitle(String sessionId, String title, String titleSource) {
      if (!history.containsKey(sessionId)) return;
      titles.put(sessionId, title.trim());
      sources.put(sessionId, TITLE_SOURCE_AUTO.equals(titleSource) ? TITLE_SOURCE_AUTO : TITLE_SOURCE_USER);
    }

    @Override
    public Optional<SessionSummary> getSessionMeta(String sessionId) {
      if (!history.containsKey(sessionId)) return Optional.empty();
      return Optional.of(new SessionSummary(sessionId, "core.rag-ask", 1L, 2L,
          history.get(sessionId).size(), "q", null, null, null, null,
          titles.get(sessionId), sources.get(sessionId)));
    }

    @Override
    public List<SessionSummary> listSessions(String shapeId, int limit) {
      return List.of();
    }

    @Override
    public List<Map<String, Object>> loadHistory(String sessionId) {
      return history.getOrDefault(sessionId, List.of());
    }

    @Override
    public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {}

    @Override
    public void deleteSession(String sessionId) {}

    @Override
    public void branchFrom(String parentSessionId, String branchPointMessageId, String newSessionId) {}

    @Override
    public void setContextFloor(String sessionId, String floorMessageId) {}

    @Override
    public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
      return List.of();
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
}

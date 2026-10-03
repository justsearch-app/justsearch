/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.app.services.conversation.AgentRunShape;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.CoreConversationShapeCatalog;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import io.justsearch.app.services.conversation.ToolIteratingShapeRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 863 — THE HEADLINE, end to end over the real store.
 *
 * <p>859 §5's open C-persistence defect, measured live: {@code GET
 * /api/chat/conversations/{id}/history} answered {@code {"messages":[]}} for a delegate conversation,
 * so the legacy window rendered an empty transcript for a conversation that had a complete durable
 * record. This exercises the whole path with no mock in the middle of it — a real
 * {@link ConversationEngine} dispatching the real {@code core.agent-run} shape through the real
 * {@link ToolIteratingShapeRunner}, writing to a real {@link FileConversationStore}, read back
 * through the real {@link ChatController#handleLoadHistory}.
 *
 * <p>Before this slice the dispatch appended nothing, so the assertion below reads
 * {@code messages: []}. It is the one test that would go red if the write site were removed.
 */
final class ChatControllerDelegateHistoryTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "863: a NEW delegate conversation answers /history with the user turn AND the answer,"
          + " carrying the answer's evidence")
  void delegateConversationHistoryIsNoLongerEmpty() {
    FileConversationStore store = new FileConversationStore(tempDir.resolve("conversations"));
    var source =
        new AgentEvent.AgentSource("doc-7", 0, "renewals/2026.md", "2026", "…", 1, 9, "Renewals");
    AgentService agent =
        stubAgent(
            sink -> {
              sink.accept(new AgentEvent.SessionStarted("run-1", TraceContext.none()));
              sink.accept(
                  new AgentEvent.AgentDone(
                      "The renewal failed because the card expired.",
                      2,
                      1,
                      512,
                      List.of(source),
                      List.of(),
                      "cross-encoder",
                      "COMPLETED"));
            });

    dispatchDelegateRun(store, agent, "uc-delegate", "why did the renewal fail?");

    JsonNode body = loadHistory(store, agent, "uc-delegate");
    JsonNode messages = body.get("messages");

    assertEquals(2, messages.size(), "the reader's question AND the delegate's answer");
    assertEquals("user", messages.get(0).get("role").asString());
    assertEquals("why did the renewal fail?", messages.get(0).get("content").asString());
    assertEquals("assistant", messages.get(1).get("role").asString());
    assertEquals(
        "The renewal failed because the card expired.", messages.get(1).get("content").asString());
    // The evidence rides the record, so a reloaded delegate answer keeps its Sources pane and its
    // scorer stamp (863 §4.A.4).
    assertEquals(1, messages.get(1).get("sources").size());
    assertEquals("cross-encoder", messages.get(1).get("citationScorer").asString());
    assertEquals("COMPLETED", messages.get(1).get("disposition").asString());

    // The store row is what makes the conversation store-backed, which is what makes RENAME
    // available on its sidebar row and its message ids addressable by ?fromMsgId= (863 A-6/A-8).
    assertTrue(store.getSessionMeta("uc-delegate").isPresent(), "a real session backs it now");
    assertEquals(
        AgentRunShape.ID.value(),
        store.getSessionMeta("uc-delegate").orElseThrow().shapeId(),
        "opened by the delegate tier");
  }

  @Test
  @DisplayName(
      "863 A-10.1: a delegate turn in a MIXED conversation does not relabel it — first shape wins")
  void delegateTurnDoesNotRelabelAMixedConversation() {
    FileConversationStore store = new FileConversationStore(tempDir.resolve("conversations"));
    // The conversation is opened by an ask-tier turn ...
    store.appendMessage("uc-mixed", "core.free-chat", Map.of("role", "user", "content", "hello"));
    store.appendMessage("uc-mixed", "core.free-chat", Map.of("role", "assistant", "content", "hi"));

    // ... and then one delegate turn joins it.
    dispatchDelegateRun(
        store, stubAgent(sink -> sink.accept(new AgentEvent.AgentDone("done", 1, 0, 1))),
        "uc-mixed", "now go and check the renewals");

    assertEquals(
        "core.free-chat",
        store.getSessionMeta("uc-mixed").orElseThrow().shapeId(),
        "the conversation's shape is the shape that OPENED it — one later turn in another mode must"
            + " not re-tag the whole transcript, nor change which ?shapeId= filter it answers to");
    assertEquals(4, store.loadHistory("uc-mixed").size(), "and the delegate turn is still recorded");
  }

  @Test
  @DisplayName("863 A-10.1: a branch keeps the shape it inherited; its own appends do not relabel it")
  void aBranchKeepsTheShapeItInherited() {
    FileConversationStore store = new FileConversationStore(tempDir.resolve("conversations"));
    store.appendMessage("uc-a", "core.free-chat", Map.of("role", "user", "content", "hello"));
    store.appendMessage("uc-a", "core.free-chat", Map.of("role", "assistant", "content", "hi"));
    store.branchFrom("uc-a", store.loadHistory("uc-a").get(1).get("id").toString(), "uc-branch");

    store.appendMessage("uc-branch", "core.rag-ask", Map.of("role", "user", "content", "and now?"));

    assertEquals(
        "core.free-chat",
        store.getSessionMeta("uc-branch").orElseThrow().shapeId(),
        "first-wins holds across the branch boundary — the inherited declaration is a real one");
  }

  @Test
  @DisplayName("863 F1: every turn's record declares the shape that dispatched it")
  void everyRecordedTurnDeclaresItsShape() {
    FileConversationStore store = new FileConversationStore(tempDir.resolve("conversations"));
    store.appendMessage("uc-mixed", "core.free-chat", Map.of("role", "user", "content", "hello"));
    dispatchDelegateRun(
        store, stubAgent(sink -> sink.accept(new AgentEvent.AgentDone("done", 1, 0, 1))),
        "uc-mixed", "now delegate");

    List<Map<String, Object>> history = store.loadHistory("uc-mixed");
    // Per MESSAGE, not per session: the session's own shapeId is first-wins for the whole
    // conversation (A-10.1), so it cannot answer "which tier dispatched THIS turn" in a mixed one.
    assertEquals("core.free-chat", history.get(0).get("shapeId"));
    assertEquals(AgentRunShape.ID.value(), history.get(1).get("shapeId"), "the delegate question");
    assertEquals(AgentRunShape.ID.value(), history.get(2).get("shapeId"), "and its answer");
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private static void dispatchDelegateRun(
      FileConversationStore store, AgentService agent, String conversationId, String question) {
    ConversationEngine engine =
        new ConversationEngine(
            CoreConversationShapeCatalog.catalog(),
            List.of(new ToolIteratingShapeRunner(() -> agent)),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            io.justsearch.app.api.OnlineAiService::unavailable,
            store);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messages", List.of(Map.of("role", "user", "content", question)));
    body.put("conversationId", conversationId);
    body.put("maxIterations", 1);
    engine.run(AgentRunShape.ID, body, Audience.USER, ev -> {}, TestRequestContexts.browser());
  }

  private static JsonNode loadHistory(
      FileConversationStore store, AgentService agent, String sessionId) {
    ChatController controller =
        new ChatController(new io.justsearch.core.execution.TestEngineExecutors(),
            null,
            new SseWriter(null),
            null,
            store,
            io.justsearch.app.api.OnlineAiService::unavailable,
            () -> agent);
    Context ctx = mock(Context.class);
    when(ctx.pathParam("sessionId")).thenReturn(sessionId);
    AtomicReference<Object> json = new AtomicReference<>();
    doAnswer(
            inv -> {
              json.set(inv.getArgument(0));
              return ctx;
            })
        .when(ctx)
        .json(any());
    controller.handleLoadHistory(ctx);
    return MAPPER.valueToTree(json.get());
  }

  private static AgentService stubAgent(Consumer<Consumer<AgentEvent>> script) {
    return new AgentService() {
      @Override
      public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
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
      public List<Operation> availableOperations() {
        return List.of();
      }

      /** No catalog to filter, so the offering is the (empty) available set. */
      @Override
      public List<Operation> offeredOperations() {
        return availableOperations();
      }

      @Override
      public List<Map<String, Object>> sessionEvents(String sessionId) {
        return List.of();
      }

      @Override
      public List<InteractionEvent> threadEvents(String conversationId) {
        return List.of();
      }
    };
  }
}

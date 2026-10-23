/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.shapes.FreeChatShape;
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import io.justsearch.agent.api.conversation.SingleHopController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StoppedExtractionHistoryTest {
  @TempDir Path dir;
  private static final String PARTIAL = "{\"city\":42,\"fact\":\"UNVALIDATED_STOP_FACT\",";

  @Test void cancelledExtractionHidesUnacceptedTextWithoutRewritingLegacyRecord() throws Exception {
    seed("core.extract");
    Path saved = dir.resolve("stopped/messages.jsonl");
    String before = Files.readString(saved);
    var answer = new FileConversationStore(dir).loadHistory("stopped").get(1);
    assertFalse(String.valueOf(answer.get("content")).contains("UNVALIDATED_STOP_FACT"), "cancelled JSON is not an answer");
    assertEquals("CANCELLED", answer.get("disposition"));
    assertEquals("stopped-answer", answer.get("id"));
    assertEquals("saved-hash", answer.get("hash"));
    assertEquals(before, Files.readString(saved), "read projection must not rewrite saved conversations");
    assertTrue(before.contains("UNVALIDATED_STOP_FACT"), "the older cancelled record remains saved");
  }

  @Test void cancelledExtractionNeverEntersEffectiveContextAfterReopenOrBranch() {
    seed("core.extract");
    var reopened = new FileConversationStore(dir);
    assertTrue(reopened.loadEffectiveContext("stopped").stream().noneMatch(m -> "assistant".equals(m.get("role"))), "cancelled extraction is excluded from prompt history");
    reopened.branchFrom("stopped", "stopped-answer", "branch");
    var branch = new FileConversationStore(dir);
    assertEquals("CANCELLED", branch.loadHistory("branch").get(1).get("disposition"));
    assertTrue(branch.loadEffectiveContext("branch").stream().noneMatch(m -> "assistant".equals(m.get("role"))));
  }

  @Test void aFloorAtTheCancelledAnswerStillExcludesEarlierContext() {
    seed("core.extract");
    var store = new FileConversationStore(dir);
    store.setContextFloor("stopped", "stopped-answer");
    store.appendMessage("stopped", "core.free-chat", Map.of("role", "user", "content", "Later question"));
    var context = new FileConversationStore(dir).loadEffectiveContext("stopped");
    assertFalse(context.stream().anyMatch(m -> "extract the city".equals(m.get("content"))), "hiding the cancelled row must not move the context floor");
    assertTrue(context.stream().anyMatch(m -> "Later question".equals(m.get("content"))));
  }

  @Test void laterAskDoesNotSendLegacyCancelledExtractionToModel() {
    seed("core.extract");
    var request = followUp(new FileConversationStore(dir));
    assertFalse(request.messages().stream().anyMatch(m -> "assistant".equals(m.get("role")) && String.valueOf(m.get("content")).contains("UNVALIDATED_STOP_FACT")), "a later Ask must not receive unaccepted extraction facts");
    assertTrue(request.messages().stream().anyMatch(m -> "What facts were established?".equals(m.get("content"))));
  }

  @Test void ordinaryCancelledAnswersStillDisplayAndReachLaterContext() {
    for (String shape : List.of("core.free-chat", "core.rag-ask", "core.summarize")) {
      var store = new FileConversationStore(dir.resolve(shape));
      seed(store, shape);
      var answer = new FileConversationStore(dir.resolve(shape)).loadHistory("stopped").get(1);
      assertEquals(PARTIAL, answer.get("content"), shape);
      assertEquals("CANCELLED", answer.get("disposition"), shape);
      assertNotEquals(true, answer.get("terminalError"), shape);
      assertTrue(followUp(store).messages().stream().anyMatch(m -> PARTIAL.equals(m.get("content"))), shape);
    }
  }

  @Test void completedExtractionStillDisplaysAndReachesContext() {
    var store = new FileConversationStore(dir);
    store.createSession("completed", "core.extract");
    store.appendMessage("completed", "core.extract", Map.of("role", "user", "content", "extract"));
    store.appendMessage("completed", "core.extract", Map.of("role", "assistant", "content", "{\"city\":\"Paris\"}"));
    assertEquals("{\"city\":\"Paris\"}", store.loadHistory("completed").get(1).get("content"));
    assertEquals(2, store.loadEffectiveContext("completed").size());
  }

  private void seed(String shape) { seed(new FileConversationStore(dir), shape); }

  private void seed(FileConversationStore store, String shape) {
    store.createSession("stopped", shape);
    store.appendMessage("stopped", shape, Map.of("role", "user", "content", "extract the city"));
    store.appendMessage("stopped", shape, Map.of("role", "assistant", "content", PARTIAL,
        "disposition", "CANCELLED", "id", "stopped-answer", "hash", "saved-hash"));
  }

  private OnlineAiService.StreamRequest followUp(FileConversationStore store) {
    var captured = new AtomicReference<OnlineAiService.StreamRequest>();
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    doAnswer(call -> {
      captured.set(call.getArgument(0));
      OnlineAiService.StreamSink sink = call.getArgument(1);
      sink.onContent().accept("No validated extraction facts.");
      sink.onComplete().accept("stop");
      return null;
    }).when(ai).stream(any(), any());
    var engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(), List.of(),
        PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.of(new ExternalContextInjector(() -> ai), UserPromptInjector.INSTANCE)),
        StreamConsumerRegistry.of(List.of(new io.justsearch.app.services.conversation.spi.MemoryExtractionConsumer(mock(io.justsearch.agent.api.memory.MemoryStore.class)))), IterationControllerRegistry.of(List.of(SingleHopController.INSTANCE)), () -> ai, store);
    engine.run(FreeChatShape.ID, Map.of("sessionId", "stopped", "prompt", "What facts were established?"),
        Audience.USER, event -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    assertNotNull(captured.get(), "the later model request must actually execute");
    return captured.get();
  }
}

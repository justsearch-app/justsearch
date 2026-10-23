/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.shapes.ExtractShape;
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import io.justsearch.app.services.conversation.spi.ValidatingController;
import io.justsearch.app.services.conversation.spi.ValidationConsumer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StructuredExtractionTest {
  @TempDir Path dir;
  private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\"}}}";

  @Test void malformedJsonAndWrongTypesRetry() {
    for (String rejected : List.of("{oops}", "{}", "{\"city\":42}", "{\"city\":\"Rome\"} {}")) {
      var ctx = context(SCHEMA);
      var consumer = new ValidationConsumer();
      var correction = consumer.onDone(rejected, ctx);
      assertEquals(false, ctx.attributes().get("validation.passed"), rejected);
      assertFalse(correction.messageDeltas().isEmpty());
      consumer.onDone("{\"city\":\"Paris\"}", ctx);
      assertEquals(true, ctx.attributes().get("validation.passed"));
    }
  }

  @Test void unsupportedOrInvalidSchemaFailsVisibly() {
    for (String schema : List.of("{\"type\":\"no-such-type\"}", "[]", "{\"$ref\":\"https://invalid.example/schema\"}")) {
      var events = run(schema, List.of("{\"city\":\"Paris\"}"), "bad-schema");
      assertTrue(events.stream().anyMatch(e -> e.name().equals("error")), schema);
      assertFalse(events.stream().anyMatch(e -> e.name().equals("done")), schema);
      assertTrue(new FileConversationStore(dir).loadHistory("bad-schema").stream()
          .anyMatch(m -> Boolean.TRUE.equals(m.get("terminalError"))));
    }
  }

  @Test void rejectedContradictionNeverBecomesHistoryOrBranchContext() {
    String rejected = "{\"city\":42,\"fact\":\"Rome is the answer\"}";
    String accepted = "{\"city\":\"Paris\"}";
    var events = run(SCHEMA, List.of(rejected, accepted), "extract-1");
    assertEquals(accepted, events.stream().filter(e -> e.name().equals("done")).findFirst().orElseThrow().payload().get("finalResponse"));
    var restarted = new FileConversationStore(dir);
    var answers = restarted.loadHistory("extract-1").stream().filter(m -> "assistant".equals(m.get("role"))).toList();
    assertEquals(List.of(accepted), answers.stream().map(m -> m.get("content")).toList());
    assertFalse(restarted.loadEffectiveContext("extract-1").toString().contains("Rome"));
    restarted.branchFrom("extract-1", String.valueOf(answers.get(0).get("id")), "branch-1");
    assertFalse(new FileConversationStore(dir).loadEffectiveContext("branch-1").toString().contains("Rome"));
    assertTrue(new FileConversationStore(dir).loadEffectiveContext("branch-1").toString().contains("Paris"));
  }

  @Test void exhaustedRetriesAreSavedAsFailureWithoutRejectedAnswer() {
    var events = run(SCHEMA, List.of("{oops}"), "failed-1");
    assertTrue(events.stream().anyMatch(e -> e.name().equals("error")));
    var history = new FileConversationStore(dir).loadHistory("failed-1");
    assertTrue(history.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("terminalError")))) ;
    assertFalse(history.toString().contains("{oops}"));
    assertFalse(new FileConversationStore(dir).isTurnOpen("failed-1"));
  }

  @Test void crashedExtractIsNotACompletedAnswer() {
    var store = new FileConversationStore(dir);
    store.createSession("crashed", "core.extract");
    store.appendMessage("crashed", "core.extract", Map.of("role", "user", "content", "extract"));
    store.appendMessage("crashed", "core.extract", Map.of("role", "assistant", "content", "{\"city\":42}"));
    store.setTurnOpen("crashed", true);
    var history = new FileConversationStore(dir).loadHistory("crashed");
    assertTrue(history.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("terminalError"))), history.toString());
    assertTrue(new FileConversationStore(dir).loadEffectiveContext("crashed").stream()
        .noneMatch(m -> "assistant".equals(m.get("role"))));
  }

  private EngineConversationContext context(String schema) {
    return new EngineConversationContext(List.of(), Audience.USER, null, "core.extract",
        Map.of("schema", schema), io.justsearch.app.services.TestEngineContexts.internal());
  }

  @Test void selectedSourcesSurviveRestartWithLiteralAcceptedJson() {
    var events = run(SCHEMA, List.of("{\"city\":\"Paris\"}"), "with-source",
        Map.of("selection", Map.of("kind", "item", "itemKind", "search-hit", "itemId", "docs/city.md")));
    assertTrue(events.stream().anyMatch(e -> e.name().equals("rag.citations")));
    var answer = new FileConversationStore(dir).loadHistory("with-source").get(1);
    assertInstanceOf(List.class, answer.get("citations"));
    assertTrue(answer.get("citations").toString().contains("docs/city.md"));
  }

  @Test void oversizedTypedInstructionIsRefusedAndSaved() {
    var events = run(SCHEMA, List.of("{\"city\":\"Paris\"}"), "too-large",
        Map.of("prompt", "instruction".repeat(20_000)));
    assertTrue(events.stream().anyMatch(e -> e.name().equals("error")));
    assertFalse(events.stream().anyMatch(e -> e.name().equals("done")));
    assertEquals("INPUT_TOO_LARGE", new FileConversationStore(dir).loadHistory("too-large").get(1).get("errorCode"));
  }

  private List<SseEvent> run(String schema, List<String> outputs, String id) {
    return run(schema, outputs, id, Map.of());
  }

  private List<SseEvent> run(String schema, List<String> outputs, String id, Map<String, Object> extra) {
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    var index = new AtomicInteger();
    doAnswer(call -> {
      OnlineAiService.StreamSink sink = call.getArgument(1);
      sink.onContent().accept(outputs.get(Math.min(index.getAndIncrement(), outputs.size() - 1)));
      sink.onComplete().accept("stop");
      return null;
    }).when(ai).stream(any(), any());
    var docs = mock(io.justsearch.app.api.DocumentService.class);
    when(docs.fetch(anyString(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        new io.justsearch.app.api.DocumentService.DocumentRecord("docs/city.md", "City: Paris", Map.of())));
    var engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(), List.of(),
        PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.of(new ExternalContextInjector(() -> ai), new io.justsearch.app.services.conversation.spi.SelectionContextInjector(docs), UserPromptInjector.INSTANCE)),
        StreamConsumerRegistry.of(List.of(new ValidationConsumer())),
        IterationControllerRegistry.of(List.of(new ValidatingController())), () -> ai, new FileConversationStore(dir));
    var events = new ArrayList<SseEvent>();
    var body = new java.util.LinkedHashMap<String, Object>(Map.of("schema", schema, "prompt", "extract city", "conversationId", id));
    body.putAll(extra);
    engine.run(ExtractShape.ID, body,
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    return events;
  }
}

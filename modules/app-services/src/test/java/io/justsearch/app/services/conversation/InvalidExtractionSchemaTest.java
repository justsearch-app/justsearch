/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.shapes.ExtractShape;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.app.services.conversation.spi.SelectionContextInjector;
import io.justsearch.app.services.conversation.spi.ValidatingController;
import io.justsearch.app.services.conversation.spi.ValidationConsumer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class InvalidExtractionSchemaTest {
  @TempDir Path dir;

  @ParameterizedTest(name = "rejects invalid schema {0}")
  @CsvSource(delimiter = '|', textBlock = """
      required | {"type":"object","required":"city"}
      properties | {"type":"object","properties":[]}
      type | {"type":"no-such-type"}
      required | {"properties":{"address":{"required":"city"}}}
      type | {"$defs":{"address":{"type":"no-such-type"}}}
      required | {"allOf":[{"required":"city"}]}
      properties | {"$schema":"http://json-schema.org/draft-07/schema#","properties":[]}
      """)
  void invalidStructureFailsWithReasonAndStaysFailedAfterStoreReopen(String keyword, String schema) {
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    doAnswer(call -> {
      OnlineAiService.StreamSink sink = call.getArgument(1);
      sink.onContent().accept("{}");
      sink.onComplete().accept("stop");
      return null;
    }).when(ai).stream(any(), any());
    var store = new FileConversationStore(dir);
    var engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(), List.of(),
        PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of(new ExternalContextInjector(() -> ai),
            new SelectionContextInjector(mock(io.justsearch.app.api.DocumentService.class)), UserPromptInjector.INSTANCE)),
        StreamConsumerRegistry.of(List.of(new ValidationConsumer())),
        IterationControllerRegistry.of(List.of(new ValidatingController())), () -> ai, store);
    var events = new ArrayList<SseEvent>();
    engine.run(ExtractShape.ID, Map.of("schema", schema, "prompt", "extract city", "conversationId", "bad-schema"),
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(events.stream().anyMatch(e -> e.name().equals("error")), "invalid schema must fail: " + schema);
    assertFalse(events.stream().anyMatch(e -> e.name().equals("done")), "invalid schema must never complete");
    assertTrue(events.stream().filter(e -> e.name().equals("error")).anyMatch(e -> e.payload().toString().contains(keyword)),
        "failure must name " + keyword + ": " + events);
    for (var reader : List.of(store, new FileConversationStore(dir))) {
      var history = reader.loadHistory("bad-schema");
      assertTrue(history.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("terminalError"))
          && m.toString().contains(keyword)), history.toString());
      assertFalse(history.stream().anyMatch(m -> "assistant".equals(m.get("role")) && "{}".equals(m.get("content"))),
          "invalid extraction must not become a saved answer");
      assertFalse(reader.loadEffectiveContext("bad-schema").stream().anyMatch(m -> "assistant".equals(m.get("role"))));
    }
  }

  @Test void validNestedSchemasAndLocalReferencesStillApply() {
    for (String schema : List.of("{}", "{\"type\":\"object\",\"properties\":{\"optional\":false}}",
        "{\"$defs\":{\"city\":{\"type\":[\"string\",\"null\"]}},\"properties\":{\"city\":{\"$ref\":\"#/$defs/city\"}},\"required\":[\"city\"]}",
        "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}")) {
      var ctx = new EngineConversationContext(List.of(), Audience.USER, null, "core.extract",
          Map.of("schema", schema), io.justsearch.app.services.TestEngineContexts.internal());
      assertDoesNotThrow(() -> new ValidationConsumer().onDone("{\"city\":\"Paris\"}", ctx));
      assertEquals(true, ctx.attributes().get("validation.passed"), schema);
    }
  }
}

package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.CoreConversationShapeCatalog;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import io.justsearch.app.services.conversation.shapes.RAGAskShape;
import io.justsearch.indexing.rag.ContextBudgeter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnswerShapeGrammar} (tempdoc 822 slice S6, §1.2-1.3 + §5.3).
 *
 * <p>Content assertions encode the fragment's <em>intent</em>, not its wording — §1.5's live A/B
 * exists to change the wording, so a golden string here would be a gate against the very thing the
 * slice is designed to iterate on.
 */
final class AnswerShapeGrammarTest {

  private static final ConversationShapeRef PIN_SHAPE_ID =
      new ConversationShapeRef("core.answer-shape-pin");

  @Test
  @DisplayName("ID is stable and namespaced under core")
  void idIsCoreNamespaced() {
    assertEquals("core.answer-shape-grammar", AnswerShapeGrammar.ID);
    assertEquals(AnswerShapeGrammar.ID, AnswerShapeGrammar.INSTANCE.id());
  }

  @Test
  @DisplayName("Singleton instance is reused")
  void singletonInstance() {
    assertNotNull(AnswerShapeGrammar.INSTANCE);
    assertEquals(AnswerShapeGrammar.INSTANCE, AnswerShapeGrammar.INSTANCE);
  }

  @Test
  @DisplayName("contribute returns the shape-guidance fragment at priority 20")
  void contributeReturnsFragmentAtPriority20() {
    PromptFragment fragment = AnswerShapeGrammar.INSTANCE.contribute(armB()).orElseThrow();
    assertEquals(20, fragment.priority(), "priority 20: after the style preamble (10), before"
        + " catalog descriptors (50-69) and dynamic context (80-99)");
    assertTrue(fragment.text().contains("backticks"), "should instruct on literal-string markup");
    assertTrue(
        Pattern.compile("[Dd]o not").matcher(fragment.text()).find(),
        "should carry at least one anti-inflation negative constraint");
  }

  @Test
  @DisplayName(
      "Cycle 1 wording: the multi-part heading clause LEADS and the plain-paragraph default is"
          + " scoped to the single-fact case")
  void cycle1ClauseOrder() {
    String text = AnswerShapeGrammar.INSTANCE.contribute(armB()).orElseThrow().text();
    int headingClause = text.indexOf("two or more distinct parts");
    int singleFactClause = text.indexOf("single-fact answer stays plain paragraphs");
    assertTrue(headingClause >= 0, "cycle 1 must keep a multi-part heading clause");
    assertTrue(singleFactClause >= 0, "cycle 1 must scope plain paragraphs to the single-fact case");
    assertTrue(
        headingClause < singleFactClause,
        "the cycle-0 failure was the plain-paragraph clause LEADING and suppressing the headings"
            + " criterion 1 measures — the multi-part clause must come first");
    // All three anti-inflation protections stay, and stay inside the single-fact clause.
    String singleFact = text.substring(singleFactClause);
    for (String guard :
        List.of("split one thought into bullets", "look thorough", "restate the answer at the end")) {
      assertTrue(singleFact.contains(guard), () -> "anti-inflation protection lost: " + guard);
    }
    assertTrue(
        text.contains("Always put") && text.contains("every one of them, every time"),
        "the backtick clause is an obligation in cycle 1, not a permission — the unguided baseline"
            + " already emits backticks in 18 of 24 runs");
  }

  @Test
  @DisplayName("contribute is stateless — same result regardless of the rest of the request body")
  void stateless() {
    var ctxA = stubCtxWithBody(Map.of("question", "foo", AnswerShapeGrammar.ARM_SWITCH_KEY, true));
    var ctxB =
        stubCtxWithBody(
            Map.of("question", "bar", "extra", "data", AnswerShapeGrammar.ARM_SWITCH_KEY, true));
    assertEquals(
        AnswerShapeGrammar.INSTANCE.contribute(ctxA).orElseThrow().text(),
        AnswerShapeGrammar.INSTANCE.contribute(ctxB).orElseThrow().text());
  }

  @Test
  @DisplayName("§1.5 arm switch: defaults OFF while the fragment is provisional")
  void armSwitchDefaultsOff() {
    assertNull(
        stubCtx().requestBody().get(AnswerShapeGrammar.ARM_SWITCH_KEY),
        "precondition: no arm flag in the body — this is every shipped caller's request");
    assertFalse(
        AnswerShapeGrammar.enabled(stubCtx()),
        "the cycle-0 A/B failed criteria 1 and 3, so §1.5 keeps the fragment out of the default"
            + " path until a cycle passes all four");
    assertTrue(AnswerShapeGrammar.INSTANCE.contribute(stubCtx()).isEmpty());
    // An unrelated body key must not be mistaken for the switch.
    assertFalse(AnswerShapeGrammar.enabled(stubCtxWithBody(Map.of("question", "q"))));
  }

  @Test
  @DisplayName("§1.5 arm switch: the opt-in flag is actually consulted, per request")
  void armSwitchIsConsulted() {
    for (Object on : List.of(Boolean.TRUE, "true", "TRUE", " true ")) {
      var armB = stubCtxWithBody(Map.of(AnswerShapeGrammar.ARM_SWITCH_KEY, on));
      assertTrue(AnswerShapeGrammar.enabled(armB), () -> "'" + on + "' must select arm B");
      assertTrue(
          AnswerShapeGrammar.INSTANCE.contribute(armB).isPresent(),
          "the flag is read per request, so both arms can interleave in one process");
    }
    for (Object off : List.of(Boolean.FALSE, "false", "no", "1", "")) {
      var armA = stubCtxWithBody(Map.of(AnswerShapeGrammar.ARM_SWITCH_KEY, off));
      assertFalse(AnswerShapeGrammar.enabled(armA), () -> "'" + off + "' must stay on arm A");
      assertTrue(
          AnswerShapeGrammar.INSTANCE.contribute(armA).isEmpty(),
          "arm A must contribute no fragment at all — not an edited one");
    }
  }

  @Test
  @DisplayName("Registered on the ask shape, after RAGQAStyle")
  void registeredOnAskShape() {
    assertEquals(
        List.of(RAGQAStyle.ID, AnswerShapeGrammar.ID),
        RAGAskShape.definition().promptContributorIds(),
        "core.rag-ask composes exactly the style preamble then the answer-shape grammar");
  }

  @Test
  @DisplayName("§6 Q3 'not yet': no other core shape composes the contributor")
  void noOtherShapeComposesIt() {
    List<String> others = new ArrayList<>();
    for (ConversationShape shape : CoreConversationShapeCatalog.catalog().definitions()) {
      if (shape.id().equals(RAGAskShape.ID)) {
        continue;
      }
      if (shape.promptContributorIds().contains(AnswerShapeGrammar.ID)) {
        others.add(shape.id().value());
      }
    }
    assertEquals(
        List.of(),
        others,
        "AnswerShapeGrammar is registered on the ask tier ONLY (822 §6 open question 3 resolved"
            + " 'not yet'). Extending it to another tier requires a re-run of the §1.5 A/B for"
            + " that tier — update this test deliberately, with the A/B result.");
  }

  @Test
  @DisplayName("Composition pin: the assembled ask prompt carries the grammar once, after the style")
  void compositionPinAgainstTheRealAssembler() {
    var llm = new ScriptedAi();
    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of(
                "core", List.of(pinShape(RAGAskShape.definition().promptContributorIds()))),
            List.of(),
            PromptContributorRegistry.of(
                List.<PromptContributor>of(RAGQAStyle.INSTANCE, AnswerShapeGrammar.INSTANCE)),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            () -> llm);

    // The arm-B opt-in travels in the request body, so the pin exercises the composed prompt the
    // A/B's arm B actually sends — with the flag absent the grammar is correctly not there at all.
    engine.run(
        PIN_SHAPE_ID,
        Map.of(AnswerShapeGrammar.ARM_SWITCH_KEY, true),
        Audience.USER,
        ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    String systemPrompt = (String) llm.calls.get(0).get(0).get("content");
    String grammar = AnswerShapeGrammar.INSTANCE.contribute(armB()).orElseThrow().text();
    String style = RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow().text();

    int first = systemPrompt.indexOf(grammar);
    assertTrue(first >= 0, "assembled prompt must carry the shape grammar");
    assertEquals(
        first,
        systemPrompt.lastIndexOf(grammar),
        "the shape grammar must appear exactly once — a second copy means a duplicate"
            + " registration or a forked prompt string");
    assertTrue(
        systemPrompt.indexOf(style) < first,
        "priority 10 (style) must precede priority 20 (shape grammar) in the assembled prompt");
  }

  @Test
  @DisplayName("Default path: a request without the opt-in gets no grammar in the assembled prompt")
  void defaultPathCarriesNoGrammar() {
    var llm = new ScriptedAi();
    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of(
                "core", List.of(pinShape(RAGAskShape.definition().promptContributorIds()))),
            List.of(),
            PromptContributorRegistry.of(
                List.<PromptContributor>of(RAGQAStyle.INSTANCE, AnswerShapeGrammar.INSTANCE)),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            () -> llm);

    engine.run(PIN_SHAPE_ID, Map.of("question", "q"), Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    String systemPrompt = (String) llm.calls.get(0).get(0).get("content");
    String grammar = AnswerShapeGrammar.INSTANCE.contribute(armB()).orElseThrow().text();
    assertFalse(
        systemPrompt.contains(grammar),
        "the provisional fragment must not reach the model on the shipped path — the registration"
            + " stays (so the engine resolves the id) but the contribution is opt-in");
    assertTrue(
        systemPrompt.contains(RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow().text()),
        "the style preamble is unaffected by the arm switch");
  }

  @Test
  @DisplayName(
      "S1 cross-reference: citation authority stays single, and its ordinals match the"
          + " numbered section headers S1 emits")
  void citationAuthorityMatchesSectionHeaders() {
    // S1's emitter: ContextBudgeter.sectionHeader renders "[n] label\n", 1-based.
    assertEquals("[1] notes.md\n", ContextBudgeter.sectionHeader(1, "notes.md"));
    assertEquals("[2] notes.md\n", ContextBudgeter.sectionHeader(2, "notes.md"));

    String style = RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow().text();
    assertTrue(
        style.contains(ContextBudgeter.sectionHeader(1, "").trim())
            && style.contains(ContextBudgeter.sectionHeader(2, "").trim()),
        "the ask prompt's citation instruction must name the same bracketed 1-based ordinals"
            + " the context sections are headed with — if sectionHeader's format changes, this"
            + " instruction changes with it");

    // 822 §1.2: the shape grammar must NOT add a second citation authority.
    String grammar = AnswerShapeGrammar.INSTANCE.contribute(armB()).orElseThrow().text();
    assertFalse(
        Pattern.compile("(?i)\\bcite|\\bcitation|\\bsources?\\b").matcher(grammar).find(),
        "the shape grammar must carry no citation instruction — RAGQAStyle is the single"
            + " citation authority (822 §1.2)");
    assertFalse(
        Pattern.compile("\\[\\d+\\]").matcher(grammar).find(),
        "the shape grammar must not name bracketed ordinals");
  }

  private static ConversationShape pinShape(List<String> contributorIds) {
    return new ConversationShape(
        PIN_SHAPE_ID,
        new Presentation(
            new I18nKey("test.label"), new I18nKey("test.desc"), Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        contributorIds,
        List.of(),
        List.of(),
        SingleHopController.ID,
        List.of(),
        true);
  }

  /** Captures the messages the engine sends; returns one canned response. */
  private static final class ScriptedAi implements OnlineAiService {
    final List<List<Map<String, Object>>> calls = new ArrayList<>();

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
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused in test"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused in test"));
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      List<Map<String, Object>> snapshot = new ArrayList<>(request.messages().size());
      for (Map<String, Object> m : request.messages()) {
        snapshot.add(new LinkedHashMap<>(m));
      }
      calls.add(snapshot);
      sink.onContent().accept("ok");
      sink.onComplete().accept("stop");
    }
  }

  private static ConversationContext stubCtx() {
    return stubCtxWithBody(Map.of());
  }

  /** A request that opts into the provisional fragment — the §1.5 arm-B body. */
  private static ConversationContext armB() {
    return stubCtxWithBody(Map.of(AnswerShapeGrammar.ARM_SWITCH_KEY, true));
  }

  private static ConversationContext stubCtxWithBody(Map<String, Object> body) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> attrs = new HashMap<>();

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public String sessionId() {
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return Map.copyOf(body);
      }

      @Override
      public Map<String, Object> attributes() {
        return attrs;
      }
    };
  }
}

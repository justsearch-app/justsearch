/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.shapes;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.services.conversation.spi.AnswerShapeGrammar;
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.app.services.conversation.spi.QueryRewriteInjector;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.conversation.spi.RAGDoneEnricher;
import io.justsearch.app.services.conversation.spi.RAGQAStyle;
import io.justsearch.app.services.conversation.spi.StreamingCitationMatcher;
import java.util.List;
import java.util.Optional;

/**
 * RAG ask {@link ConversationShape}.
 *
 * <p>Per tempdoc 491 §C3: replaces the legacy {@code /api/ask/stream} endpoint with a
 * substrate-driven shape. Composes {@link RAGQAStyle} (prompt) + {@link RAGContext}
 * (retrieval + fallback + truncation) + {@link CitationMatcher} (post-hoc citation matching)
 * + {@link RAGDoneEnricher} (done-payload enrichment).
 *
 * <p>Cell: {@link IterationMode#ONE_SHOT} × {@link PersistenceMode#EPHEMERAL}.
 */
public final class RAGAskShape {

  public static final ConversationShapeRef ID = new ConversationShapeRef("core.rag-ask");

  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.rag-ask.label");

  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.rag-ask.description");

  /** SSE event vocabulary: substrate defaults plus namespaced RAG events. */
  private static final List<String> EVENT_SCHEMA =
      List.of(
          "chunk", "reasoning_chunk", "rag.meta", "rag.citations", "rag.citation_delta",
          // Tempdoc 603 C2 — the decontextualized standalone question (transparency, conversation-layer).
          "rag.rewrite",
          "rag.citation_checking", "rag.citation_matches", "done", "error");

  private RAGAskShape() {}

  public static ConversationShape definition() {
    return new ConversationShape(
        ID,
        new Presentation(LABEL_KEY, DESCRIPTION_KEY, Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        // Tempdoc 822 §1.3 (S6) — AnswerShapeGrammar (priority 20) follows RAGQAStyle (10):
        // identity/style first, then the output-shape rules. Ask tier only, by decision.
        List.of(RAGQAStyle.ID, AnswerShapeGrammar.ID),
        // 603 C2 — QueryRewriteInjector runs BEFORE RAGContext: decontextualize a follow-up, then retrieve.
        List.of(ExternalContextInjector.ID, QueryRewriteInjector.ID, RAGContext.ID),
        // Tempdoc 561 P-E: the passive learning producer runs on RAG-ask turns too.
        List.of(
            StreamingCitationMatcher.ID,
            RAGDoneEnricher.ID,
            io.justsearch.app.services.conversation.spi.MemoryExtractionConsumer.ID),
        SingleHopController.ID,
        EventDescriptor.namesOnly(EVENT_SCHEMA),
        // recordsToThread (tempdoc 863 §4.A.1) — unchanged from the retired derivation. EPHEMERAL
        // context, and still on the record: the two axes are independent (561 P-A/P-B).
        true);
  }
}

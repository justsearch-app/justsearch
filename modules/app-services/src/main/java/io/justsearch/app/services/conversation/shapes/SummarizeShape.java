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
import io.justsearch.app.services.conversation.spi.DocAccess;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import io.justsearch.app.services.conversation.spi.SelectionContextInjector;
import io.justsearch.app.services.conversation.spi.StreamingCitationMatcher;
import io.justsearch.app.services.conversation.spi.SummarizationStyle;
import io.justsearch.app.services.conversation.spi.SummaryDoneEnricher;
import java.util.List;
import java.util.Optional;

/**
 * The {@link ConversationShape} that summarizes a single document.
 *
 * <p>Per tempdoc 491 §9 Phase C1: the simplest fresh shape, replacing the legacy
 * {@code /api/summarize/stream} endpoint. Validates the substrate-driven execution path
 * end-to-end with one {@link io.justsearch.agent.api.conversation.PromptContributor}
 * ({@link SummarizationStyle}) and one
 * {@link io.justsearch.agent.api.conversation.ContextInjector} ({@link DocAccess}).
 *
 * <p>Cell: {@link IterationMode#ONE_SHOT} × {@link PersistenceMode#EPHEMERAL}.
 */
public final class SummarizeShape {

  /** Stable {@link ConversationShapeRef} for the summarize shape. */
  public static final ConversationShapeRef ID = new ConversationShapeRef("core.summarize");

  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.summarize.label");

  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.summarize.description");

  /**
   * SSE event vocabulary: standard substrate-emitted events.
   *
   * <p>Tempdoc 836 S2S3 — {@code rag.citation_matches} is DECLARED here because it is produced
   * here: {@link StreamingCitationMatcher} is a registered stream consumer of this shape and emits
   * it at {@code onDone}. It already reached the browser (the engine forwards consumer events
   * without filtering against this list), so the declaration is the manifest catching up with
   * reality — and 836 §5.11 pins the two together: the event is declared IFF the matcher is a
   * consumer.
   */
  private static final List<String> EVENT_SCHEMA =
      List.of(
          "chunk",
          "reasoning_chunk",
          "rag.citations",
          "rag.citation_delta",
          "rag.citation_matches",
          "done",
          "error");

  private SummarizeShape() {}

  /** Build the {@link ConversationShape} manifest entry. */
  public static ConversationShape definition() {
    // Per tempdoc 526 §12.4: SelectionContextInjector handles typed body.selection;
    // DocAccess remains for the no-selection (full-doc) path. DocAccess returns empty()
    // when body.selection is present so the slice is not injected twice (§12.3 D2).
    return new ConversationShape(
        ID,
        new Presentation(LABEL_KEY, DESCRIPTION_KEY, Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        List.of(SummarizationStyle.ID),
        List.of(SelectionContextInjector.ID, DocAccess.ID, UserPromptInjector.ID),
        List.of(StreamingCitationMatcher.ID, SummaryDoneEnricher.ID),
        SingleHopController.ID,
        EventDescriptor.namesOnly(EVENT_SCHEMA),
        // recordsToThread (tempdoc 863 §4.A.1) — an answer-plane turn, as the retired derivation
        // already computed for every USER-audience substrate-driven shape.
        true);
  }
}

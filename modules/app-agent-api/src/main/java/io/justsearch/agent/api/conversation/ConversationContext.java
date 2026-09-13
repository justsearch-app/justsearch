/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import io.justsearch.agent.api.registry.Audience;
import java.util.List;
import java.util.Map;

/**
 * Runtime context passed to substrate SPIs ({@link PromptContributor}, {@link ContextInjector},
 * {@link StreamConsumer}, {@link IterationController}) during a {@code ConversationEngine}
 * invocation.
 *
 * <p>Per tempdoc 491 §5.1: SPIs are stateless; the context carries the per-request state they
 * need (message history, iteration count, request audience, session id for persistent shapes).
 * The interface is read-only — SPIs do not mutate the context directly; they return values
 * (e.g., {@link StreamConsumerResult#messageDeltas}) that the engine integrates into the next
 * iteration's context.
 *
 * <p>Phase B carries the minimal context needed for the agent shape (shape-driven mode largely
 * bypasses the context; substrate-driven mode fills it as fresh shapes land in Phase C). The
 * interface is expected to grow as more shapes register their needs — extensions add methods
 * with sensible defaults so existing SPI implementations are not broken.
 */
public interface ConversationContext {

  /** Immutable request attribution, carried independently of the model-visible body. */
  io.justsearch.core.context.EngineContext engineContext();

  /**
   * The current message list (OpenAI shape — role/content/tool_call_id maps). Includes the
   * system prompt as message[0] when the engine has assembled one; SPIs that need to know
   * the assembled system prompt can inspect index 0.
   */
  List<Map<String, Object>> messages();

  /**
   * Zero-based iteration count within the current user request. Always 0 for {@link
   * IterationMode#ONE_SHOT} shapes. Incremented by the engine before invoking SPIs for the
   * next iteration in {@link IterationMode#WITHIN_TURN_ITERATION} shapes.
   */
  int iteration();

  /** The audience under which this request is being processed (from §5.4 trust gating). */
  Audience audience();

  /**
   * Stable session identifier for {@link PersistenceMode#PERSISTENT} shapes; {@code null}
   * for {@link PersistenceMode#EPHEMERAL} shapes.
   */
  String sessionId();

  /**
   * The id of the {@code ConversationShape} currently being run (e.g. {@code "core.extract"}),
   * or {@code null} when the context was built outside the engine.
   *
   * <p>A shared SPI serves several shapes, and some of its output is only correct relative to the
   * requesting one — {@code SelectionContextInjector} fronts an injected selection with an
   * instruction, and "summarize this" is wrong for an extraction. The request body is not a
   * reliable source for this: only the dynamic {@code /api/chat/dispatch} route carries a
   * {@code shapeId} field; the per-shape routes ({@code /api/chat/extract}, …) do not.
   */
  default String shapeId() {
    return null;
  }

  /**
   * The raw request body that initiated this conversation (parsed JSON as a {@code Map}).
   *
   * <p>Per tempdoc 491 Phase C: shape-specific request fields (e.g., {@code docId},
   * {@code docIds}, {@code question}) live in the body and are consumed by SPIs that know
   * their shape's schema (e.g., {@code DocAccess} reads {@code docId} + {@code content};
   * {@code RAGContext} reads {@code docIds} + {@code question}). The engine does not
   * interpret shape-specific fields — that knowledge stays with the SPI implementations
   * the shape composes.
   *
   * <p>Returned map is read-only / unmodifiable; SPIs must not mutate it.
   */
  Map<String, Object> requestBody();

  /**
   * Mutable cross-SPI scratch map for the current request.
   *
   * <p>Per tempdoc 491 §C2.0 (substrate enhancement): allows SPIs participating in the same
   * shape to share request-scoped state without piping it through the message list. Canonical
   * use: {@code RAGContext} stores retrieved citations + chunk metadata; {@code CitationMatcher}
   * and {@code RAGDoneEnricher} read them. Keys should be SPI-scoped (e.g.,
   * {@code "rag.citations"}, {@code "rag.chunksUsed"}) to avoid collisions.
   *
   * <p>The map is empty at request start; entries written by earlier-running SPIs are visible
   * to later-running ones in the same request. Not persisted across requests.
   */
  Map<String, Object> attributes();
}

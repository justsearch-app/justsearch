/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runtime for a {@link io.justsearch.agent.api.registry.ConversationShape} in
 * {@link io.justsearch.agent.api.conversation.ExecutionMode#SHAPE_DRIVEN} mode.
 *
 * <p>Per tempdoc 491 §5.4: shape-driven shapes register a runner; the engine delegates to
 * the runner for the entire conversation lifecycle. The runner is responsible for its own
 * iteration loop, SPI composition (if any), and event emission. Used to encapsulate
 * existing implementations whose iteration logic is correctness-critical (the agent loop is
 * the canonical example).
 *
 * <p>Substrate-driven shapes do not register a {@link ShapeRunner} — the engine drives the
 * per-iteration loop directly via the four substrate SPIs.
 *
 * <p>The runner receives an opaque {@code body} (parsed from the HTTP request body), the
 * request's {@link Audience}, and a sink to emit {@link SseEvent}s. The runner blocks until
 * the conversation completes; the engine in turn keeps the HTTP SSE response open until the
 * runner returns.
 */
public interface ShapeRunner {

  /** The {@link ConversationShapeRef} this runner implements. */
  ConversationShapeRef shapeId();

  /**
   * Run the conversation end-to-end. Blocks until completion. Emit progress and result via
   * the sink.
   *
   * @param body request body parsed as a key/value map (shape-specific schema)
   * @param audience the request's invocation audience (already validated against the
   *     shape's declared audience before this is invoked)
   * @param sink the SSE event sink
   */
  void run(Map<String, Object> body, Audience audience, Consumer<SseEvent> sink, EngineContext engineContext);
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * SPI: subscribes to LLM model output events; may emit SSE events, execute side effects, and
 * report message-list deltas for the next iteration.
 *
 * <p>Per tempdoc 491 §5.1 (data-flow audit): one of the four substrate SPIs. The contract
 * supports three things — emit typed SSE events, execute side effects (operation dispatch,
 * approval gates, URL navigation), and return message deltas the next iteration must see.
 * The "read-only consumer" framing was wrong; consumers may mutate the next iteration's
 * input.
 *
 * <p>Prior-art examples: the agent loop's tool-call parser (parses tool-call grammar,
 * dispatches via {@code OperationDispatcher}, runs approval gates, appends tool-result
 * messages) and the RAG-handler's citation matcher (post-hoc matches retrieved chunks to
 * generated text, emits {@code rag.citation_matches} events). The {@link StreamConsumer}
 * contract is designed forward to support both mid-stream and post-stream consumers.
 *
 * <p>Example consumers (not exhaustive): {@code ToolCallExecutor} (mid-stream parser, internal
 * to the {@code ToolIterating} shape), {@code URLExtractor} (post-{@code onDone} URL parsing
 * for the URL-emission shape), {@code CitationMatcher} (post-{@code onDone} citation matching
 * for the RAG ask shape).
 *
 * <p><strong>Phase B status</strong>: the SPI interface is defined; the only consumer
 * registered in Phase B is the tool-call executor inside the agent loop's encapsulated body.
 * Fresh consumers land in Phase C with the RAG ask and URL emission shape migrations.
 */
public interface StreamConsumer {

  /**
   * Stable identifier within the substrate's consumer registry. Used in
   * {@code ConversationShape.streamConsumerIds} to compose this consumer into a shape's
   * manifest.
   */
  String id();

  /**
   * Slice 491 §9.D Phase E (G4) — which shape trust tiers may compose this consumer.
   * Default = all tiers. Restrictive implementations override (e.g., a consumer that
   * dispatches privileged operations may return {@code EnumSet.of(TrustTier.CORE)} so
   * untrusted-plugin shapes can't reach it).
   */
  default java.util.Set<io.justsearch.agent.api.registry.TrustTier> allowedShapeTiers() {
    return java.util.EnumSet.allOf(io.justsearch.agent.api.registry.TrustTier.class);
  }

  /**
   * Invoked by the engine for each chunk of streamed text. The chunk text is the incremental
   * delta from the model; the engine forwards the running accumulator via the context.
   *
   * <p>Mid-stream consumers (e.g., tool-call grammar parsers) do their work here. Post-stream
   * consumers (e.g., URL extractors that need the full response) typically return
   * {@link StreamConsumerResult#empty} from this method.
   *
   * @param chunkText the incremental text just emitted by the model
   * @param ctx the per-request context (accumulator visible via context)
   * @return events/side-effects/message-deltas produced by this chunk (may be empty)
   */
  StreamConsumerResult onChunk(String chunkText, ConversationContext ctx);

  /**
   * Invoked by the engine when the model stream completes. Post-stream consumers do their
   * work here (parsing the full response, dispatching URLs, matching citations).
   *
   * @param fullText the full assistant text emitted in this iteration
   * @param ctx the per-request context
   * @return events/side-effects/message-deltas produced at stream end (may be empty)
   */
  StreamConsumerResult onDone(String fullText, ConversationContext ctx);

  /**
   * Finish with an emitter for progress that must reach the reader before this call returns.
   * Existing consumers retain their two-argument contract; final events still belong in the result.
   */
  default StreamConsumerResult onDone(
      String fullText, ConversationContext ctx, java.util.function.Consumer<SseEvent> progress) {
    return onDone(fullText, ctx);
  }
}

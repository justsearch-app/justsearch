/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ShapeRunner} for the agent shape — {@link AgentRunShape#ID} ({@code core.agent-run}).
 *
 * <p>Per tempdoc 491 §5.4 + §9 Phase B: the agent loop's body
 * ({@code AgentLoopService} + {@code AgentSession} + {@code AgentRunStore}) is encapsulated
 * unchanged. This runner is the encapsulation boundary: it parses the engine's opaque
 * {@code body} into an {@link AgentRequest}, delegates to {@code AgentService#runAgent},
 * and translates each emitted {@link AgentEvent} into an {@link SseEvent} that the engine
 * forwards to the response stream.
 *
 * <p>The translation logic mirrors the pre-substrate {@code AgentController.writeAgentEvent}
 * exactly so the FE consumer ({@code AgentSurface.ts}) sees the same event vocabulary it has
 * always seen. This is the FE-compatibility commitment: under the encapsulation contract,
 * the wire-shape contract does not change.
 */
public final class ToolIteratingShapeRunner implements ShapeRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ToolIteratingShapeRunner.class);

  private final Supplier<AgentService> agentServiceSupplier;
  private final StreamConsumerRegistry streamConsumers;
  // Tempdoc 550 F6: the shared intent-gate evaluator the proposed-batch projection reads (nullable;
  // late-bound by production wiring). Lets this conversation-shape path annotate the batch with the
  // same verdict the /api/agent path does, via the one ProposedBatchProjection.
  private io.justsearch.app.services.intent.IntentGateEvaluator intentGateEvaluator;

  /** Wire the shared intent-gate evaluator used to annotate the proposed tool batch (F6). */
  public void setIntentGateEvaluator(io.justsearch.app.services.intent.IntentGateEvaluator evaluator) {
    this.intentGateEvaluator = evaluator;
  }

  /**
   * Constructs a runner that resolves the agent service lazily, without composed
   * stream consumers. Equivalent to passing an empty registry — `AgentRunShape` declares
   * a {@code streamConsumerIds} list but with no registry registrations the runner
   * fails-loud on resolution (per F1 contract). This constructor is intended for tests
   * that don't exercise the substrate-driven stream-consumer composition; production
   * callers should use the registry-aware constructor below.
   */
  public ToolIteratingShapeRunner(Supplier<AgentService> agentServiceSupplier) {
    this(agentServiceSupplier, StreamConsumerRegistry.of(List.of()));
  }

  /**
   * Slice 491 §9.D Phase E (C4 + F1) — registry-driven constructor. The runner takes the
   * full {@link StreamConsumerRegistry} (mirroring {@code ConversationEngine}) and, at
   * {@link AgentEvent.AgentDone}, resolves each id in {@link
   * AgentRunShape#definition()}'s {@code streamConsumerIds()} via the registry, invoking
   * {@link StreamConsumer#onDone(String, ConversationContext)} in declaration order.
   * Each consumer's emitted {@link SseEvent}s are forwarded to the sink ahead of the
   * translated {@code done} event so URL-related events bracket the assistant response.
   *
   * <p>Result: declarations on {@code AgentRunShape.streamConsumerIds()} become
   * load-bearing — adding a new id wires it through this runner with no code change
   * here. Removes the prior C4.A "extract by id but bypass the registry" pattern.
   */
  public ToolIteratingShapeRunner(
      Supplier<AgentService> agentServiceSupplier, StreamConsumerRegistry streamConsumers) {
    this.agentServiceSupplier =
        Objects.requireNonNull(agentServiceSupplier, "agentServiceSupplier");
    this.streamConsumers = Objects.requireNonNull(streamConsumers, "streamConsumers");
  }

  @Override
  public ConversationShapeRef shapeId() {
    return AgentRunShape.ID;
  }

  @Override
  public void run(Map<String, Object> body, Audience audience, Consumer<SseEvent> sink) {
    AgentService agent = agentServiceSupplier.get();
    if (agent == null || !agent.isAvailable()) {
      sink.accept(
          new SseEvent(
              "error",
              Map.of(
                  "error", "Agent capability is not available",
                  "errorCode", "SERVICE_UNAVAILABLE")));
      return;
    }

    AgentRequest request;
    try {
      request = parseRequest(body);
    } catch (Exception e) {
      LOG.warn("Failed to parse agent request body", e);
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", e.getMessage());
      err.put("errorCode", "BAD_REQUEST");
      sink.accept(new SseEvent("error", err));
      return;
    }

    LOG.debug(
        "Dispatching agent shape (audience={}, profiles={}, maxIterations={})",
        audience,
        request.agentProfiles().size(),
        request.maxIterations());
    // Slice 491 §9.D Phase E (C4 + F1) — accumulate assistant text across TextChunk
    // events so the registry-resolved stream consumers can run at AgentDone. The agent
    // loop blocks the caller until it terminates; events stream to the sink in real time
    // via the AgentEvent → SseEvent translation below. Stream-consumer SSE events
    // (e.g., navigate.url_*) are interposed between the final chunk and the done event
    // so a FE consumer sees them in lexical order with the response text they describe.
    final StringBuilder assistantText = new StringBuilder();
    // Tempdoc 550 F6: resolve the tool index once, then project each proposed batch through the
    // shared ProposedBatchProjection (same as the /api/agent path) with the shared evaluator.
    final Map<String, io.justsearch.agent.api.registry.Operation> opsByToolName =
        ProposedBatchProjection.indexByToolName(agent.availableOperations());
    agent.runAgent(
        request,
        event -> {
          if (event instanceof AgentEvent.TextChunk chunk) {
            assistantText.append(chunk.text());
          }
          if (event instanceof AgentEvent.AgentDone) {
            applyStreamConsumers(assistantText.toString(), audience, sink);
          }
          sink.accept(
              AgentEventSseTranslator.translate(event, intentGateEvaluator, opsByToolName));
        });
  }

  /**
   * F1 — resolve each id in {@code AgentRunShape.streamConsumerIds()} via the registry
   * and invoke {@code onDone} in declaration order. Forwards every consumer's emitted
   * {@link SseEvent}s to the sink. Stream-consumer exceptions are isolated from the
   * agent loop — logged + the remaining consumers run + the agent's done event still
   * fires.
   */
  private void applyStreamConsumers(
      String fullText, Audience audience, Consumer<SseEvent> sink) {
    List<String> ids = AgentRunShape.definition().streamConsumerIds();
    if (ids.isEmpty() || fullText.isEmpty()) {
      return;
    }
    ConversationContext ctx = simpleContext(audience);
    for (String id : ids) {
      StreamConsumer consumer = streamConsumers.findById(id).orElse(null);
      if (consumer == null) {
        // Declared in the shape manifest but not registered in this process. Log so the
        // gap is visible without breaking the agent run — substrate-driven shapes need
        // the same consumer set; an absence here is a wiring bug, not a runtime
        // condition the user should see surfaced as an agent error.
        LOG.warn(
            "AgentRunShape declares streamConsumerId '{}' but no consumer is registered;"
                + " skipping",
            id);
        continue;
      }
      try {
        StreamConsumerResult result = consumer.onDone(fullText, ctx);
        if (result == null) continue;
        for (SseEvent ev : result.events()) {
          sink.accept(ev);
        }
      } catch (RuntimeException e) {
        LOG.warn("StreamConsumer '{}' failed for agent shape run", id, e);
      }
    }
  }

  /**
   * Build a minimal read-only {@link ConversationContext} for the agent shape's URL
   * extraction. The agent runner doesn't expose its full message history at this layer; URL
   * extraction only needs the assistant's full text + audience for the trust-lattice gate
   * inside {@code URLExtractor#onDone}.
   */
  private static ConversationContext simpleContext(Audience audience) {
    return new ConversationContext() {
      @Override
      public List<Map<String, Object>> messages() {
        return Collections.emptyList();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return audience;
      }

      @Override
      public String sessionId() {
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return Map.of();
      }

      @Override
      public Map<String, Object> attributes() {
        return new HashMap<>();
      }
    };
  }

  /**
   * Parse the engine's opaque body Map into an {@link AgentRequest}. Mirrors the parsing
   * previously inlined in {@code AgentController.handleRunStream} (modules/ui/.../AgentController.java
   * pre-substrate); centralized here as part of the agent shape's encapsulation boundary.
   */
  @SuppressWarnings("unchecked")
  static AgentRequest parseRequest(Map<String, Object> body) {
    if (body == null) {
      throw new IllegalArgumentException("Request body is missing");
    }
    Object messagesObj = body.get("messages");
    if (!(messagesObj instanceof List<?> messagesList)) {
      throw new IllegalArgumentException("messages must be a list");
    }
    List<Map<String, Object>> messages = new ArrayList<>();
    for (Object m : messagesList) {
      if (m instanceof Map<?, ?> mm) {
        messages.add((Map<String, Object>) mm);
      } else {
        throw new IllegalArgumentException("Each message must be a JSON object");
      }
    }

    List<String> selectedTools = List.of();
    Object toolsObj = body.get("tools");
    if (toolsObj instanceof List<?> toolsList) {
      List<String> tmp = new ArrayList<>(toolsList.size());
      for (Object t : toolsList) {
        if (t != null) tmp.add(t.toString());
      }
      selectedTools = tmp;
    }

    int maxIterations = asInt(body.get("maxIterations"), 1);

    List<AgentProfile> profiles = List.of();
    Object profilesObj = body.get("agentProfiles");
    if (profilesObj instanceof List<?> profilesList) {
      List<AgentProfile> tmp = new ArrayList<>(profilesList.size());
      for (Object p : profilesList) {
        if (p instanceof Map<?, ?> pm) {
          tmp.add(AgentProfile.fromMap((Map<String, Object>) pm));
        }
      }
      profiles = tmp;
    }

    String initialAgentId = body.get("initialAgentId") == null ? null : body.get("initialAgentId").toString();
    Integer maxHandoffs = asNullableInt(body.get("maxHandoffs"));
    // Tempdoc 561 P-A/P-B: the chat conversationId the FE stamps so this agent run's thread events
    // land under the same interaction record as the surrounding chat turns (one unified thread).
    String conversationId =
        body.get("conversationId") == null ? null : body.get("conversationId").toString();
    // Tempdoc 561 P-D: the FE stamps the autonomy dial onto the request so the backend issuance policy
    // (IntentGateEvaluator.agentGate) decides the gate — the FE no longer re-derives it. Without this
    // pass-through the level was dropped at the HTTP→AgentRequest boundary and defaulted to ASSIST
    // (caught live: auto + a MEDIUM write still gated as typed_confirm).
    String autonomyLevel =
        body.get("autonomyLevel") == null ? null : body.get("autonomyLevel").toString();
    // Tempdoc S7 — the FE's scope-chip selection: when present, every SearchTool call in this run
    // is filtered to just these paths (mirrors RAGContext#extractDocIds's body.get("docIds") shape
    // for the core.rag-ask path — same wire key, same list-of-strings convention).
    List<String> docIds = extractDocIds(body);
    // Tempdoc 859 §D §2.1 — the FE sends the EFFORT RUNG NAME, never a token count: only the backend
    // can see the model's n_ctx, so AgentBudgetPolicy owns the sizing. Absent/unknown ⇒ Standard,
    // which is what every caller that predates the rung (the legacy window, seam adopters, a resumed
    // run) deliberately gets.
    String effort = body.get("effort") == null ? null : body.get("effort").toString();
    // Tempdoc 863 §4.A.3 — the ENGINE's stamp, not a caller field. `ShapeRunner.run` receives no
    // ConversationShape, so this runner cannot ask `recordsToThread()`; `ConversationEngine`
    // resolves the write key and writes the answer into the dispatch body under this key (always,
    // true or false), which is why only `Boolean.TRUE` counts and a string a client happened to post
    // does not. It rides into `AgentRunStore.startRun`'s meta, where the thread projection reads it.
    boolean recordsToThread =
        Boolean.TRUE.equals(body.get(ConversationEngine.RECORDS_TO_THREAD_KEY));

    // Lane F PR 0b — the optional per-run sampling override. Unlike `effort` (a rung NAME the
    // backend sizes) these are the raw knobs, so they are VALIDATED rather than coerced: a client
    // that sends `sampling: {"temperature": "hot"}` gets the same IllegalArgumentException 400 the
    // `messages` checks above raise, not a silently ignored field.
    AgentRequest.SamplingOverride sampling = extractSamplingOverride(body);

    return new AgentRequest(
        messages,
        selectedTools,
        maxIterations,
        profiles,
        initialAgentId,
        maxHandoffs,
        conversationId,
        autonomyLevel,
        docIds,
        effort,
        recordsToThread,
        sampling);
  }

  /**
   * Parses the body's optional {@code sampling} object (lane F PR 0b).
   *
   * <p>Absent or explicitly null ⇒ null (no override — byte-identical to the behaviour before the
   * field existed). Present but not a JSON object, or carrying a non-numeric value for any of the
   * three keys, is a malformed request and throws — {@code AgentController} turns that into the
   * same {@code BAD_REQUEST} SSE error shape every other bad field produces. Unknown keys are
   * ignored, matching how the rest of this opaque body is read.
   */
  private static AgentRequest.SamplingOverride extractSamplingOverride(Map<String, Object> body) {
    Object raw = body.get("sampling");
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("sampling must be a JSON object");
    }
    Double temperature = samplingDouble(map.get("temperature"), "sampling.temperature");
    Double topP = samplingDouble(map.get("top_p"), "sampling.top_p");
    // The same bounds SamplingParams enforces, checked HERE so an out-of-range value is a 400 at
    // the boundary rather than an IllegalArgumentException thrown mid-run, several LLM calls in.
    if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
      throw new IllegalArgumentException("sampling.temperature must be 0.0-2.0, got " + temperature);
    }
    if (topP != null && (topP < 0.0 || topP > 1.0)) {
      throw new IllegalArgumentException("sampling.top_p must be 0.0-1.0, got " + topP);
    }
    Number seedNumber = samplingNumber(map.get("seed"), "sampling.seed");
    Long seed = seedNumber == null ? null : seedNumber.longValue();
    return new AgentRequest.SamplingOverride(temperature, topP, seed);
  }

  private static Double samplingDouble(Object value, String field) {
    Number n = samplingNumber(value, field);
    return n == null ? null : n.doubleValue();
  }

  private static Number samplingNumber(Object value, String field) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n;
    }
    if (value instanceof String str) {
      try {
        return Double.valueOf(str.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(field + " must be a number, got \"" + str + "\"");
      }
    }
    throw new IllegalArgumentException(field + " must be a number");
  }

  /** Tempdoc S7 — parse the body's optional {@code docIds} array; absent/malformed = empty (unscoped). */
  @SuppressWarnings("unchecked")
  private static List<String> extractDocIds(Map<String, Object> body) {
    Object raw = body.get("docIds");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object o : list) {
      if (o == null) continue;
      String s = o.toString();
      if (!s.isBlank()) out.add(s);
    }
    return out;
  }

  private static int asInt(Object o, int fallback) {
    if (o instanceof Number n) return n.intValue();
    if (o instanceof String s) {
      try {
        return Integer.parseInt(s);
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static Integer asNullableInt(Object o) {
    if (o == null) return null;
    if (o instanceof Number n) return n.intValue();
    if (o instanceof String s) {
      try {
        return Integer.parseInt(s);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input to an agent conversation.
 *
 * @param messages chat messages in OpenAI format
 * @param selectedToolNames tool names to make available (empty = all tools)
 * @param maxIterations maximum agent loop iterations (1 for Plan-then-Execute)
 * @param agentProfiles named agent role definitions (empty = single-agent mode)
 * @param initialAgentId starting agent ID; null = first profile or "primary"
 * @param maxHandoffs maximum handoffs per agent pair before cycle detection fires; null = profiles
 *     * 3
 * @param conversationId tempdoc 561 P-A/P-B — the cross-plane interaction-log conversationId (the
 *     chat conversation this agent run belongs to). Null = the run is its own thread (the agent loop
 *     falls back to the run sessionId). The FE supplies the chat conversationId so an agent run and
 *     the surrounding chat turns interleave in one unified thread.
 * @param autonomyLevel tempdoc 561 P-D — the user's autonomy-dial preference as a wire token
 *     ({@code "watch"|"assist"|"auto"}); null/unknown → ASSIST. The FE supplies the dial so the ONE
 *     backend issuance policy ({@code IntentGateEvaluator.agentGate}) computes the gate and the FE
 *     obeys it, instead of the FE re-deriving auto-approval from risk (the collapsed 2nd authority).
 * @param docIds tempdoc S7 — the FE's scope-chip selection (document paths); empty/null = unscoped
 *     (unchanged behavior). When non-empty, every {@code SearchTool} invocation in this run is
 *     filtered to just these paths, regardless of what the LLM's own tool-call arguments say
 *     ({@code AgentToolDispatcher.scopeToolCall} merges it into the search call's arguments;
 *     {@code SearchTool} threads it into {@code KnowledgeSearchRequest.Filters.docIds}).
 * @param effort tempdoc 859 §D §2.1 — the run's EFFORT rung as a wire token ({@code
 *     "quick"|"standard"|"thorough"}); null/unknown → Standard. It is the rung NAME, never a token
 *     count: sizing is a backend policy ({@code AgentBudgetPolicy}) because only the backend can see
 *     the model's {@code n_ctx}, and an FE-authored count would be a second sizing authority.
 * @param recordsToThread tempdoc 863 §4.A.3 — TRUE when the dispatching {@code ConversationEngine}
 *     is itself appending this run's turns to the canonical conversation record. It is an ENGINE
 *     decision, not a caller preference: the engine resolves the write key and stamps the answer into
 *     the dispatch body, the runner carries it here, and {@code AgentRunStore.startRun} persists it in
 *     the run meta — which is where the thread projection reads it to suppress its own synthesised
 *     copies of turns the record already holds. False for every run the engine does not record:
 *     standalone runs, background runs, and every run written before the stamp existed.
 * @param sampling lane F PR 0b — the caller's optional sampling override for THIS run, applied over
 *     the {@code SamplingParams.AGENT} constant every agent LLM call otherwise uses. Null (the
 *     absent field) is byte-identical to the behaviour before the field existed. It exists because
 *     the agent path is shape-driven and never reaches the chat path's request-derived sampling, so
 *     a capture had no way to hold the trajectory still: the same narrow question completed in 3
 *     iterations in one capture and hit the iteration cap in the next. Each component is
 *     independently nullable — an override that sets only {@code seed} keeps AGENT's 0.7 / 0.8.
 */
public record AgentRequest(
    List<Map<String, Object>> messages,
    List<String> selectedToolNames,
    int maxIterations,
    List<AgentProfile> agentProfiles,
    String initialAgentId,
    Integer maxHandoffs,
    String conversationId,
    String autonomyLevel,
    List<String> docIds,
    String effort,
    boolean recordsToThread,
    SamplingOverride sampling) {

  /**
   * A per-run sampling override, every component independently optional (lane F PR 0b).
   *
   * <p>Deliberately NOT {@code SamplingParams}: this is the WIRE shape a caller may send, carrying
   * only the three knobs a reproducible capture needs. {@code SamplingParams} additionally carries
   * backend-owned decisions ({@code tool_choice}, grammar, {@code response_format}, thinking
   * suppression) that the agent loop computes per turn and a request must never be able to set —
   * folding these three into that record on the wire would hand a client all of them.
   *
   * @param temperature sampling temperature, or null to keep the agent preset's
   * @param topP nucleus sampling mass, or null to keep the agent preset's
   * @param seed llama-server RNG seed, or null to omit it (today's behaviour)
   */
  public record SamplingOverride(Double temperature, Double topP, Long seed) {

    /** True when the override would change nothing — every component absent. */
    public boolean isEmpty() {
      return temperature == null && topP == null && seed == null;
    }
  }

  public AgentRequest {
    Objects.requireNonNull(messages, "messages");
    selectedToolNames = selectedToolNames == null ? List.of() : List.copyOf(selectedToolNames);
    if (maxIterations < 1) {
      throw new IllegalArgumentException("maxIterations must be >= 1, got " + maxIterations);
    }
    agentProfiles = agentProfiles == null ? List.of() : List.copyOf(agentProfiles);
    docIds = docIds == null ? List.of() : List.copyOf(docIds);
    // initialAgentId, maxHandoffs, conversationId, autonomyLevel, effort null are valid —
    // defaulted at runtime (effort's default is the Standard rung, AgentBudgetPolicy).
    // An override with every component absent is normalised to null so "no override" has ONE
    // representation and downstream sites never have to distinguish the two (lane F PR 0b).
    sampling = (sampling == null || sampling.isEmpty()) ? null : sampling;
  }

  /**
   * Back-compat constructor (pre-lane-F-PR-0b, no {@code sampling}) — delegates with null, which is
   * exactly "use {@code SamplingParams.AGENT} unchanged", the behaviour every caller had.
   */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs,
      String conversationId,
      String autonomyLevel,
      List<String> docIds,
      String effort,
      boolean recordsToThread) {
    this(
        messages,
        selectedToolNames,
        maxIterations,
        agentProfiles,
        initialAgentId,
        maxHandoffs,
        conversationId,
        autonomyLevel,
        docIds,
        effort,
        recordsToThread,
        null);
  }

  /**
   * Back-compat constructor (pre-863, no {@code recordsToThread}) — delegates with {@code false}.
   * Every older overload below chains through this one, so a caller that predates the stamp declares
   * the truth about itself: it is not the engine, and it is recording nothing to the canonical
   * record. Only {@code ToolIteratingShapeRunner} (which reads the engine's stamp off the dispatch
   * body) uses the canonical constructor.
   */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs,
      String conversationId,
      String autonomyLevel,
      List<String> docIds,
      String effort) {
    this(
        messages,
        selectedToolNames,
        maxIterations,
        agentProfiles,
        initialAgentId,
        maxHandoffs,
        conversationId,
        autonomyLevel,
        docIds,
        effort,
        false);
  }

  /** Back-compat constructor (pre-859 §D, no effort) — delegates with effort null ⇒ Standard. */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs,
      String conversationId,
      String autonomyLevel,
      List<String> docIds) {
    this(
        messages,
        selectedToolNames,
        maxIterations,
        agentProfiles,
        initialAgentId,
        maxHandoffs,
        conversationId,
        autonomyLevel,
        docIds,
        null);
  }

  /** Back-compat constructor (pre-S7, no docIds) — delegates with docIds empty (unscoped). */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs,
      String conversationId,
      String autonomyLevel) {
    this(
        messages,
        selectedToolNames,
        maxIterations,
        agentProfiles,
        initialAgentId,
        maxHandoffs,
        conversationId,
        autonomyLevel,
        List.of());
  }

  /** Back-compat constructor (561 P-A/P-B, no autonomyLevel) — delegates with autonomyLevel=null. */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs,
      String conversationId) {
    this(messages, selectedToolNames, maxIterations, agentProfiles, initialAgentId, maxHandoffs,
        conversationId, null);
  }

  /** Back-compat constructor (pre-561, no conversationId) — delegates with conversationId=null. */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs) {
    this(messages, selectedToolNames, maxIterations, agentProfiles, initialAgentId, maxHandoffs, null);
  }

  /** Convenience constructor — maxHandoffs defaults to null (auto-computed from profile count). */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId) {
    this(messages, selectedToolNames, maxIterations, agentProfiles, initialAgentId, null);
  }

  /** Backward-compatible constructor for single-agent (no profiles) requests. */
  public AgentRequest(
      List<Map<String, Object>> messages, List<String> selectedToolNames, int maxIterations) {
    this(messages, selectedToolNames, maxIterations, List.of(), null);
  }

  public static AgentRequest singleTurn(List<Map<String, Object>> messages) {
    return new AgentRequest(messages, List.of(), 1);
  }
}

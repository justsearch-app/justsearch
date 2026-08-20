/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tempdoc 561 P-A/P-B (correction) — the READ-TIME projection of a persisted {@code AgentRunStore}
 * event into a plane-neutral {@link InteractionEvent} for the unified thread.
 *
 * <p>This is a projection, not a producer: the agent's activity is already durable in
 * {@code AgentRunStore.events.ndjson} (§10: "the live thread is reconstructable from events.ndjson").
 * The unified thread reads those records and maps them here — it does NOT write a second store. Only
 * the events that constitute the durable thread become interaction events; transient/streaming events
 * (chunks, proposed/approved/started, progress, budget, session_started) map to empty.
 *
 * <p>Input is one {@code events.ndjson} record: {@code {timestamp: ISO, eventType: String, payload:
 * {…}}} (the shape {@code AgentRunStore.appendEvent} writes via {@code toPayload}).
 */
public final class AgentInteractionMapper {

  /** 24h — a sanity ceiling on a folded block's duration, not a product limit (see {@code addBlock}). */
  private static final long MAX_PLAUSIBLE_REASONING_MS = 24L * 60L * 60L * 1000L;

  private AgentInteractionMapper() {}

  /**
   * Project one persisted run event to its thread event, or empty if it is not durable thread
   * content.
   *
   * @param record one {@code events.ndjson} record ({@code timestamp}/{@code eventType}/{@code
   *     payload})
   * @param conversationId the chat conversation this run belongs to
   */
  public static Optional<InteractionEvent> fromRunEvent(
      Map<String, Object> record, String conversationId) {
    if (!(record.get("eventType") instanceof String eventType)) {
      return Optional.empty();
    }
    Map<String, Object> payload =
        record.get("payload") instanceof Map<?, ?> m ? castMap(m) : Map.of();
    Instant at = parseTs(record.get("timestamp"));
    String stamp = String.valueOf(at.toEpochMilli());
    return switch (eventType) {
      case "done" -> {
        // Tempdoc 565 §26.I (Fix A) — a WORKFLOW terminal `done` (it carries `nodesExecuted`) is NOT an
        // answer bubble: the workflow's content lives in the per-node `node_output` events that bracket
        // inside each node, and the done's `finalResponse` merely repeats the LAST node's output.
        // Skipping it here prevents the last node rendering twice on a reloaded workflow run. An AGENT
        // `done` (no `nodesExecuted`) IS the answer and falls through.
        if (payload.containsKey("nodesExecuted")) {
          yield Optional.empty();
        }
        // Tempdoc 565 §3.A/persistence — carry the answer's grounding sources + per-sentence
        // citations on the persisted assistant message so a reloaded thread renders the same Sources
        // pane + inline marks from the record (mirrors the RAG path at
        // ConversationEngine.persistedAssistant, which attaches citations/claimMatches).
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (payload.get("sources") instanceof List<?> srcs && !srcs.isEmpty()) {
          attributes.put("sources", srcs);
        }
        if (payload.get("citations") instanceof List<?> cites && !cites.isEmpty()) {
          attributes.put("citations", cites);
        }
        // Tempdoc 859 §4 — the producer stamp travels onto the persisted assistant message beside
        // the citations it describes. Without it a RELOADED delegate answer would be admitted by
        // the pre-stamp allowance forever, so the gate would exist and never fire on the record
        // path — the same read-site-only defect the stamp exists to close.
        if (payload.get("citationScorer") instanceof String scorer && !scorer.isBlank()) {
          attributes.put("citationScorer", scorer);
        }
        yield Optional.of(
            new InteractionEvent(
                conversationId + ":assistant:" + stamp,
                conversationId,
                at,
                InteractionEventKind.ASSISTANT_MESSAGE,
                "agent",
                str(payload.get("finalResponse")),
                attributes));
      }
      // Tempdoc 565 §12.3.B — `tool_call_proposed` fires for EVERY tool (incl. auto-run ones that
      // never reach `pending`), carrying the tool's identity (toolName + arguments + risk). The FE
      // projection merges all TOOL_ACTIVITY events for a callId, so this supplies the verb+target the
      // compact tool row needs on the record (reload) — the terminal completed/rejected events add the
      // outcome/evidence but carry no identity.
      case "tool_call_proposed" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":proposed",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "toolName", payload.get("toolName"),
                      "arguments", payload.get("arguments"),
                      "status", "proposed",
                      "risk", payload.get("risk"))));
      case "tool_call_pending" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":pending",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "toolName", payload.get("toolName"),
                      "arguments", payload.get("arguments"),
                      "status", "pending",
                      "risk", payload.get("risk"))));
      // Tempdoc 565 §15.C — the workflow run (now projected through this ONE thread mapper) carries a
      // tool's identity on `tool_exec_started` for auto-run steps that never reach `pending` (the agent
      // path supplies identity via `tool_call_proposed`). The FE merges all TOOL_ACTIVITY by callId, so
      // this adds the verb+target to the same card the terminal `tool_exec_completed` fills out.
      case "tool_exec_started" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":started",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "toolName", payload.get("toolName"),
                      "status", "executing")));
      case "tool_exec_completed" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":completed",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "status", "completed",
                      "success", payload.get("success"),
                      "output", payload.get("output"),
                      // Tempdoc 561 #6: carry the producer evidence onto the record event so the
                      // record render shows the same evidence cards as the live overlay.
                      "structuredData", payload.get("structuredData"))));
      case "tool_call_rejected" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":rejected",
                  conversationId,
                  at,
                  attrs("callId", payload.get("callId"), "status", "rejected", "reason",
                      payload.get("reason"))));
      case "error" ->
          Optional.of(
              new InteractionEvent(
                  conversationId + ":error:" + stamp,
                  conversationId,
                  at,
                  InteractionEventKind.ERROR,
                  "agent",
                  str(payload.get("error")),
                  attrs("errorCode", payload.get("errorCode"))));
      case "handoff_executed" ->
          Optional.of(
              new InteractionEvent(
                  conversationId + ":handoff:" + stamp,
                  conversationId,
                  at,
                  InteractionEventKind.HANDOFF,
                  "agent",
                  "",
                  attrs("fromAgentId", payload.get("fromAgentId"), "toAgentId",
                      payload.get("toAgentId"))));
      // Tempdoc 565 §26.A/§26.B — the workflow run's STRUCTURE: a node boundary surfaces as a
      // PROGRESS event carrying `nodeBoundary`/`nodeId`/`nodeKind`, so the record-side projection
      // brackets a node's steps into a run segment (the FE `assignRunSegments` pass) exactly as the
      // live side does. Before §26 these were dropped here (the `default` no-op), so a reloaded
      // workflow run lost its node grouping. The nodeId doubles as the segment label.
      case "node_started" ->
          Optional.of(
              new InteractionEvent(
                  nodeEventId(conversationId, payload.get("index"), 1, payload.get("nodeId"), stamp),
                  conversationId,
                  at,
                  InteractionEventKind.PROGRESS,
                  "agent",
                  "",
                  attrs(
                      "nodeBoundary", "start",
                      "nodeId", payload.get("nodeId"),
                      "nodeKind", payload.get("kind"),
                      "label", payload.get("nodeId"))));
      case "node_completed" ->
          Optional.of(
              new InteractionEvent(
                  nodeEventId(conversationId, payload.get("index"), 3, payload.get("nodeId"), stamp),
                  conversationId,
                  at,
                  InteractionEventKind.PROGRESS,
                  "agent",
                  "",
                  attrs(
                      "nodeBoundary", "end",
                      "nodeId", payload.get("nodeId"),
                      "nodeKind", payload.get("kind"),
                      "label", payload.get("nodeId"))));
      // Tempdoc 565 §26.I (Fix A) — a workflow LlmStep's full output, persisted as the node's durable
      // ASSISTANT_MESSAGE. Its id sorts BETWEEN node_started (role 1) and node_completed (role 3) even on
      // a same-millisecond timestamp tie (role 2), so the reloaded projection brackets it INSIDE the node
      // segment — making reload identical to the live render (which builds the same text from the chunks).
      case "node_output" ->
          Optional.of(
              new InteractionEvent(
                  nodeEventId(conversationId, payload.get("index"), 2, payload.get("nodeId"), stamp),
                  conversationId,
                  at,
                  InteractionEventKind.ASSISTANT_MESSAGE,
                  "agent",
                  str(payload.get("output")),
                  Map.of()));
      // Tempdoc S4b (Search Thread) — the manually-triggered search action's durable event, written by
      // `AgentRunStore.appendSearchEvent` (its own small `core.search-event`-shaped run, joined to the
      // conversation exactly like a workflow run). Carries the search's identity/outcome verbatim so the
      // reloaded thread renders the same committed search card the live UI showed.
      case "search_executed" ->
          Optional.of(
              new InteractionEvent(
                  searchEventId(conversationId, at),
                  conversationId,
                  at,
                  InteractionEventKind.SEARCH,
                  "user",
                  "",
                  attrs(
                      "query", payload.get("query"),
                      "mode", payload.get("mode"),
                      "matchCount", payload.get("matchCount"),
                      "resultCount", payload.get("resultCount"),
                      "docIds", payload.get("docIds"),
                      "executedAt", payload.get("executedAt"))));
      // Tempdoc 848 §2.4 — NOT dropped: reasoning chunks are FOLDED by `fromRunEvents` into blocks
      // that ride on the turn they belong to. Stated as its own case rather than left to `default` so
      // the vocabulary is legible — a per-chunk thread event would mean ~445 events for one turn.
      case "reasoning_chunk" -> Optional.empty();
      default -> Optional.empty();
    };
  }

  /**
   * Tempdoc 848 §2.4 — project a whole run's persisted events, folding its {@code reasoning_chunk}
   * records into {@code {text, durationMs}} blocks that attach to the turn they belong to.
   *
   * <p>A read-time fold rather than a new durable event type: the journal ALREADY holds every chunk
   * (`AgentRunStore.appendEvent` journals all of them), so a second durable representation would be
   * the fork shape the surface registers exist to prevent.
   *
   * <p>Block boundaries key on the LLM STEP, not on bare contiguity. {@code "chunk"} (the journal
   * name for {@code AgentEvent.TextChunk}) is TRANSPARENT: reasoning runs separated only by text
   * coalesce into ONE block, with the intervening text excluded. This matters because reasoning and
   * text share one stream — on a think-tag-leaking build {@code ThinkTagStreamFilter} reroutes inline
   * {@code <think>} markup into the reasoning sink mid-stream, so naive contiguity would shatter one
   * step into several blocks on one build family and not the other, for identical model behaviour.
   * Every other event type ({@code tool_*}, {@code node_*}, {@code done}, {@code error}…) is a
   * genuine step boundary and cuts the block.
   *
   * <p>{@code durationMs} carries the SAME semantic as the answer plane and the live controller:
   * from the block's first reasoning token to the first non-reasoning output that follows it.
   *
   * <p>Blocks left unflushed at the end of the walk attach to the run's terminal event (its
   * {@code ERROR} if it produced one, else its last event): what the model thought before a run was
   * halted or failed was really produced, and the ask WINDOW records exactly that at all four of its
   * terminals. Scope limit (848 §2.4): the ask plane's SERVER side does not — a failed
   * {@code streamLlm} throws before any assistant record is written, so its reasoning survives only
   * in-session. Closing that would mean persisting a partial assistant turn on error, which is a
   * turn-semantics change beyond this charter.
   */
  public static List<InteractionEvent> fromRunEvents(
      List<Map<String, Object>> records, String conversationId) {
    List<InteractionEvent> out = new ArrayList<>();
    List<Map<String, Object>> pending = new ArrayList<>();
    StringBuilder runText = new StringBuilder();
    Instant runStart = null;
    Instant runFirstOutput = null;
    Instant lastSeen = null;

    for (Map<String, Object> record : records) {
      String eventType = record.get("eventType") instanceof String s ? s : "";
      Instant at = parseTs(record.get("timestamp"));
      lastSeen = at;
      if ("reasoning_chunk".equals(eventType)) {
        if (runStart == null) {
          runStart = at;
        }
        Map<String, Object> payload =
            record.get("payload") instanceof Map<?, ?> m ? castMap(m) : Map.of();
        runText.append(str(payload.get("text")));
        continue;
      }
      if (runStart != null && runFirstOutput == null) {
        runFirstOutput = at;
      }
      if (runStart != null && !"chunk".equals(eventType)) {
        addBlock(pending, runText.toString(), runStart, runFirstOutput);
        runText.setLength(0);
        runStart = null;
        runFirstOutput = null;
      }
      Optional<InteractionEvent> projected = fromRunEvent(record, conversationId);
      if (projected.isEmpty()) {
        continue;
      }
      InteractionEvent event = projected.get();
      if (event.kind() == InteractionEventKind.ASSISTANT_MESSAGE && !pending.isEmpty()) {
        out.add(withReasoning(event, pending));
        pending = new ArrayList<>();
      } else {
        out.add(event);
      }
    }

    if (runStart != null) {
      addBlock(pending, runText.toString(), runStart, runFirstOutput != null ? runFirstOutput : lastSeen);
    }
    if (!pending.isEmpty() && !out.isEmpty()) {
      int target = out.size() - 1;
      for (int i = out.size() - 1; i >= 0; i--) {
        if (out.get(i).kind() == InteractionEventKind.ERROR) {
          target = i;
          break;
        }
      }
      out.set(target, withReasoning(out.get(target), pending));
    }
    return out;
  }

  /** Append one folded block, unless the run produced no actual thinking text. */
  private static void addBlock(
      List<Map<String, Object>> blocks, String text, Instant start, Instant end) {
    if (text.isBlank()) {
      return;
    }
    long durationMs = end == null ? 0L : Math.max(0L, end.toEpochMilli() - start.toEpochMilli());
    // A record whose `timestamp` was missing or unparseable parses to `Instant.EPOCH` (`parseTs`), so
    // ONE bad timestamp in a run would otherwise render "Thought for 56 years" next to the answer. A
    // duration past any plausible thinking interval is not a measurement, so report none rather than
    // a fabricated one — the block's TEXT is still real and still shown.
    if (durationMs > MAX_PLAUSIBLE_REASONING_MS) {
      durationMs = 0L;
    }
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("text", text);
    block.put("durationMs", durationMs);
    blocks.add(block);
  }

  /**
   * Tempdoc 848 §2.4 — attributes are written by RECONSTRUCTION, never mutation:
   * {@code InteractionEvent}'s compact constructor does {@code Map.copyOf}, so the map the delegate
   * returned is immutable and {@code put} on it would throw at runtime.
   */
  private static InteractionEvent withReasoning(
      InteractionEvent event, List<Map<String, Object>> blocks) {
    Map<String, Object> merged = new LinkedHashMap<>(event.attributes());
    merged.put("reasoning", List.copyOf(blocks));
    return new InteractionEvent(
        event.id(),
        event.conversationId(),
        event.occurredAt(),
        event.kind(),
        event.originator(),
        event.content(),
        merged);
  }

  private static InteractionEvent toolActivity(
      String id, String conversationId, Instant at, Map<String, Object> attributes) {
    return new InteractionEvent(
        id, conversationId, at, InteractionEventKind.TOOL_ACTIVITY, "agent", "", attributes);
  }

  /** Build an attribute map, skipping null values (Map.copyOf rejects nulls). */
  private static Map<String, Object> attrs(Object... kv) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      Object value = kv[i + 1];
      if (value != null) {
        m.put((String) kv[i], value);
      }
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }

  private static String str(Object o) {
    return o instanceof String s ? s : o == null ? "" : String.valueOf(o);
  }

  /**
   * Tempdoc 565 §26.I — a workflow node event's stable id, built so LEXICAL order == TEMPORAL order on a
   * same-millisecond timestamp tie: {@code …:node:<5-digit index>:<role 1=start|2=output|3=end>:<nodeId>:<ms>}.
   * The FE sort tiebreaker is {@code id.localeCompare}, so without the index+role ordering a tie between
   * {@code node_output} and {@code node_completed} (emitted back-to-back) would sort the {@code end}
   * boundary first and render the node's output OUTSIDE its segment (the reload defect Fix A targets); the
   * index keeps node N's {@code end} ahead of node N+1's {@code start} on the cross-node tie.
   */
  private static String nodeEventId(
      String conversationId, Object indexObj, int role, Object nodeId, String stamp) {
    int idx = indexObj instanceof Number n ? n.intValue() : 0;
    return conversationId
        + ":node:"
        + String.format(java.util.Locale.ROOT, "%05d", idx)
        + ":"
        + role
        + ":"
        + str(nodeId)
        + ":"
        + stamp;
  }

  /**
   * Tempdoc S4b — a SEARCH event's stable projected id, shared by the read-time projection here and
   * the write-time return value ({@code AgentRunStore.appendSearchEvent}) so the id the write path
   * hands back to the caller is the SAME id the event will carry on the next {@code GET /api/thread}.
   */
  static String searchEventId(String conversationId, Instant at) {
    return conversationId + ":search:" + at.toEpochMilli();
  }

  static Instant parseTs(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
    }
    if (raw instanceof Number n) {
      return Instant.ofEpochMilli(n.longValue());
    }
    return Instant.EPOCH;
  }
}

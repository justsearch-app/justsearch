/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tempdoc 561 P-A/P-B (correction) — the READ-TIME projection of a persisted {@code AgentRunStore}
 * event into a plane-neutral {@link InteractionEvent} for the unified thread.
 *
 * <p>This is a projection, not a producer: the agent's activity is already durable in
 * {@code AgentRunStore.events.ndjson} (§10: "the live thread is reconstructable from events.ndjson").
 * The unified thread reads those records and maps them here — it does NOT write a second store. Only
 * the events that constitute the durable thread become interaction events; transient/streaming events
 * (chunks, approved, budget, session_started, and the LIVENESS half of {@code progress}) map to
 * empty. The accountability half of {@code progress} does not — see the case's own rule.
 *
 * <p>Input is one {@code events.ndjson} record: {@code {timestamp: ISO, eventType: String, payload:
 * {…}}} (the shape {@code AgentRunStore.appendEvent} writes via {@code toPayload}).
 */
public final class AgentInteractionMapper {

  /** 24h — a sanity ceiling on a folded block's duration, not a product limit (see {@code addBlock}). */
  private static final long MAX_PLAUSIBLE_REASONING_MS = 24L * 60L * 60L * 1000L;

  /**
   * Tempdoc 859 §D (F6 follow-up) — the {@code progress} phases that become durable thread notes. The
   * classification rule, and why everything else stays ephemeral, is stated at the {@code "progress"}
   * case in {@link #fromRunEvent}.
   */
  static final Set<String> DURABLE_PROGRESS_PHASES =
      Set.of(
          AgentEvent.AgentProgress.PHASE_BUDGET_RAISED,
          AgentEvent.AgentProgress.PHASE_CONTEXT_GATE_UNANSWERED,
          AgentEvent.AgentProgress.PHASE_CONTEXT_GATE_REAPPLIED,
          AgentEvent.AgentProgress.PHASE_CONTEXT_COMPACTED,
          // 859 D live-defect D4 — the run overriding the reader's own CONTINUE. If any phase in this
          // set has to survive a reload it is this one: it is the justification for a compaction the
          // reader did not ask for, and it is emitted two lines above PHASE_CONTEXT_COMPACTED, which
          // does survive.
          AgentEvent.AgentProgress.PHASE_CONTEXT_COMPACTED_TO_FIT,
          // Owner decision 2026-08-26 — the reader's own Stop. Durable because a cancel that races
          // the run's terminal leaves no other trace: the loop never re-reads the flag, the
          // disposition stays COMPLETED, and the act would be gone by the next reload.
          AgentEvent.AgentProgress.PHASE_STOP_REQUESTED);

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
    return fromRunEvent(record, conversationId, 0);
  }

  /**
   * As {@link #fromRunEvent(Map, String)}, with the record's ORDINAL — its 0-based position in the
   * run's journal, which is the run's true emission order (the journal is append-only and read back
   * in write order).
   *
   * <p>Tempdoc 859 §D (F6 follow-up) — it exists for the same reason {@code nodeEventId}'s role
   * ordinal does: the FE tiebreaker on equal timestamps is {@code id.localeCompare}, so an id with no
   * ordinal in it sorts by whatever text it happens to contain. For the progress notes that is the
   * PHASE NAME, and {@code "context_compacted"} sorts BEFORE {@code "context_gate_reapplied"} while
   * being emitted AFTER it — so a reloaded run could draw the compaction above the note explaining
   * why it happened. Those two are emitted back-to-back ({@code AgentStepRunner}'s second-crossing
   * path), so the millisecond tie is the normal case there, not a rare one.
   *
   * <p>A per-phase ordinal would NOT have worked: {@code budget_raised} has two emit sites, one
   * before the context block and one after it, so its position relative to the compaction notes is
   * not a property of the phase. The journal index is the only ordinal that is right at both sites.
   */
  public static Optional<InteractionEvent> fromRunEvent(
      Map<String, Object> record, String conversationId, int ordinal) {
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
        // Tempdoc 859 §D §2.6 — the terminal DISPOSITION travels onto the persisted assistant
        // message beside the answer it describes. Without it, a run that was cut short would say so
        // while it was on screen and stop saying so after a reload — an honesty fact that expires is
        // worse than one that was never made, because the reader has already learned to trust it.
        if (payload.get("disposition") instanceof String disposition && !disposition.isBlank()) {
          attributes.put("disposition", disposition);
        }
        yield Optional.of(
            new InteractionEvent(
                terminalAnswerIdPrefix(conversationId) + stamp,
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
                      "structuredData", payload.get("structuredData"),
                      // Tempdoc 878 §D.4 — and carry the model-visibility label for the same
                      // reason. `attrs` drops nulls, so an unmeasured completion writes neither key
                      // and the reloaded card stays silent exactly as the live one does. Omitting
                      // them here would make a run say less on reload than it said while running —
                      // the live-vs-record divergence 867 built one renderer to prevent.
                      "outputCharsToModel", payload.get("outputCharsToModel"),
                      "truncatedForModel", payload.get("truncatedForModel"))));
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
      // Tempdoc 859 §D (F6 follow-up) — a progress note is DURABLE when it records a decision the run
      // took on the reader's behalf, or a change it made to the run's material inputs. It is
      // EPHEMERAL when it narrates what the run is doing right now.
      //
      // The first kind is the accountability record §D's guard rail promises ("every silent continue
      // is NARRATED", SearchV3View.ts:2627): a raise the reader never approved, a gate that asked and
      // proceeded when nobody answered, a context decision re-applied without asking again, a
      // compaction that dropped earlier turns out of the prompt the answer was produced from. A
      // disclosure that expires the moment the conversation is reloaded is worse than one never made,
      // because the reader has already learned to trust it — the same argument §D §2.6 made for
      // `disposition` on the persisted answer.
      //
      // The second kind is a spinner. Its durable trace already exists in the events around it, and
      // the whole ephemeral set is: `llm_call` (once per iteration, and the iteration is visible in
      // the steps it produced), `init` (restates `session_started`), `finalizing` (its outcome is the
      // terminal `disposition`), `budget_gate_held` and `context_gate_held` (the ASK; every way either
      // one RESOLVES is a durable note above or a terminal disposition), `retry_after_tool_failure`
      // (both the failure and the retry are already durable tool events), `run_unobserved_parked` (a
      // park the run left again), and `workflow:*` (mirrors the workflow's own node journal).
      // Persisting those would add a record row per iteration saying nothing the record does not.
      //
      // DEFAULT IS EPHEMERAL, deliberately: a phase added later must not start polluting every
      // reloaded conversation by accident. A new accountability phase is declared as a constant beside
      // its emit site and listed in DURABLE_PROGRESS_PHASES here — the two-site agreement is why those
      // tokens are constants and the liveness ones are literals, and
      // `AgentInteractionMapperTest.everyDeclaredPhaseConstantIsClassified` fails the build if a new
      // constant is declared and neither list claims it.
      case "progress" -> {
        String phase = str(payload.get("phase"));
        if (!DURABLE_PROGRESS_PHASES.contains(phase)) {
          yield Optional.empty();
        }
        yield Optional.of(
            new InteractionEvent(
                progressEventId(conversationId, ordinal, phase, stamp),
                conversationId,
                at,
                InteractionEventKind.PROGRESS,
                "agent",
                // The narration itself, in `content` — where BOTH windows' note renderers read it
                // from (`sv3-record` note text, `runStepPresentation.stepLabel`'s label-or-content
                // choice). `phase` rides the attributes as the typed token, exactly as the live entry
                // carries it. `budget_raised` is deliberately absent from `PROGRESS_PHASE_LABELS` so
                // it falls back to this message: a static label would erase the amount ("+12,000
                // tokens"), and the same holds for the compaction's dropped count. (`context_compacted`
                // IS in that table — runStepPresentation.ts:86 — so the OTHER window still shows its
                // fixed label there; that is a pre-existing choice this note does not change.)
                str(payload.get("message")),
                attrs("phase", phase, "severity", payload.get("severity"))));
      }
      // WP6.2 — a user's direction, with what became of it. Each report (queued, then applied or
      // not applied) is its own record; `fromRunEvents` keeps one event per direction, at its first
      // place, carrying the final outcome. A record from before WP6.2 has no id and no outcome, and
      // an acknowledgement alone never proved the direction reached the model, so it shows nothing.
      case "directive_acknowledged" -> {
        if (!(payload.get("directiveId") instanceof String directiveId)) {
          yield Optional.empty();
        }
        yield Optional.of(
            new InteractionEvent(
                conversationId + ":direction:" + directiveId,
                conversationId,
                at,
                InteractionEventKind.PROGRESS,
                "user",
                str(payload.get("directiveText")),
                attrs(
                    "steer", Boolean.TRUE,
                    "phase", "direction",
                    "directiveOutcome", payload.get("outcome"),
                    "directiveReason", payload.get("reason"))));
      }
      // Tempdoc 848 §2.4 — NOT dropped: reasoning chunks are FOLDED by `fromRunEvents` into blocks
      // that ride on the turn they belong to. Stated as its own case rather than left to `default` so
      // the vocabulary is legible — a per-chunk thread event would mean ~445 events for one turn.
      case "reasoning_chunk" -> Optional.empty();
      // Tempdoc 865 §7.7 — THE REST OF `AgentRunShape`'s VOCABULARY, DECLARED NON-PROJECTING.
      //
      // Every kind is born durable on the wire: `AgentEventPayloads`' `name()`/`base()` switches are
      // over a SEALED interface, so the compiler forces a decision about each one. Here the switch is
      // over a String with a `default`, so a kind nobody wrote a case for is silently non-durable —
      // and "nobody decided" is indistinguishable from "someone decided no". `budget_raised` is that
      // asymmetry's output, not an oversight, and this block removes the asymmetry's remaining reach
      // over `AgentRunShape` by making the answer something a person wrote.
      //
      // ZERO BEHAVIOUR CHANGE: every name below already reached `default -> Optional.empty()`. The
      // `default` arm is RETAINED, and still bites, because this mapper also serves vocabularies
      // `AgentRunShape` does not declare — the workflow node journal and `search_executed` above.
      // The reasons, by group:
      //   * `session_started`, `state_snapshot` — run plumbing. Identity the conversation already
      //     has, and a live replay aid for a mid-run re-attach; neither says anything about the turn.
      //   * `chunk` — the answer text arriving in pieces. `done.finalResponse` persists the whole of
      //     it, which is why the record path rebuilds the message from the terminal, not the stream.
      //   * `tool_batch_proposed`, `tool_call_approved`, `tool_call_rejected`'s sibling
      //     `tool_call_virtual`, `handoff_proposed` — a PROPOSAL whose OUTCOME is already durable:
      //     the executed call, the rejection and the executed handoff all persist above.
      //   * `budget_update`, `budget_gate`, `context_gate`, `context_compacted` — live meters and
      //     open questions. Every way one of them RESOLVES into a fact the reader must keep is
      //     already a durable `progress` note (`DURABLE_PROGRESS_PHASES`) or the terminal
      //     `disposition` — the 859 §D split this block must not silently re-litigate.
      //   * `intent.resolution` — not an `AgentEvent` variant at all, but the composed
      //     `core.url-extractor` StreamConsumer's own namespaced event. Its payload is still typed
      //     as name-only (`EventDescriptor.nameOnly`), so there is no declared shape to project;
      //     naming it here records that as an open question rather than an answer nobody gave.
      case "session_started",
              "chunk",
              "tool_batch_proposed",
              "tool_call_approved",
              "tool_call_virtual",
              "handoff_proposed",
              "budget_update",
              "budget_gate",
              "context_gate",
              "context_compacted",
              "state_snapshot",
              "intent.resolution" ->
          Optional.empty();
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
   * <p>Tempdoc 859 §A §1.3 — a cut block is held in {@code pending} and flushed onto the NEXT event
   * that actually projects, whatever kind it is: the tool row, the handoff, the answer. It is NOT
   * attached to the event that cut it, because most cutting events are invisible downstream — a
   * projection must name a carrier that exists in the PROJECTED stream, not one that exists in the
   * source journal. Nor is it retargeted onto the next {@code ASSISTANT_MESSAGE}, the rule this
   * replaces: on an agent run the only assistant message is the terminal {@code done}, so every
   * block in a multi-step run landed on it and the reader got a wall of thoughts detached from the
   * steps that produced them.
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
    Map<String, Integer> directionAt = new java.util.HashMap<>();

    for (int ordinal = 0; ordinal < records.size(); ordinal++) {
      Map<String, Object> record = records.get(ordinal);
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
      Optional<InteractionEvent> projected = fromRunEvent(record, conversationId, ordinal);
      if (projected.isEmpty()) {
        continue;
      }
      InteractionEvent event = projected.get();
      // WP6.2 — a later report on a direction already shown updates it in place: the final outcome
      // replaces "queued", and a late "queued" never undoes a final one.
      Integer shown = directionAt.get(event.id());
      if (shown != null) {
        InteractionEvent first = out.get(shown);
        if (!AgentEvent.DirectiveAcknowledged.QUEUED.equals(event.attributes().get("directiveOutcome"))) {
          var attributes = new LinkedHashMap<>(first.attributes());
          attributes.remove("directiveReason");
          attributes.putAll(event.attributes());
          out.set(shown, new InteractionEvent(first.id(), first.conversationId(), first.occurredAt(),
              first.kind(), first.originator(), first.content(), attributes));
        }
        continue;
      }
      if (Boolean.TRUE.equals(event.attributes().get("steer"))) {
        directionAt.put(event.id(), out.size());
      }
      // Tempdoc 859 §A §1.3 — flush onto the next event that ACTUALLY PROJECTS, of ANY kind. The
      // event that CUTS a region is very often one this projection drops (`budget_update` is emitted
      // the instant each LLM stream ends, and `handoff_proposed` / the gates / a LIVENESS `progress`
      // project nothing either), so a rule keyed on the cutting event names a carrier that does not
      // exist downstream. §D's F6 follow-up changes WHICH events project, not this rule: an
      // accountability `progress` note now carries, exactly as the live side already does
      // (`AgentSessionController.onProgress` appends an entry, so the open region is committed and the
      // note follows it — `AgentSessionController.test.ts` C-7b). Its cut/carry pair therefore agrees
      // with the live one, and the record's carrier set stays a SUBSET of the live one, with the hold
      // rule covering the difference losslessly (M-2).
      // Retargeting onto the next ASSISTANT_MESSAGE — the rule this replaces — is why a
      // seven-step run's seven blocks all landed on its single terminal answer and drew as a wall.
      // Chronologically this is exact: the block was produced BEFORE the cutting event, the cutting
      // event renders nothing, so the block renders immediately before the next thing that does.
      if (!pending.isEmpty()) {
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
  public static InteractionEvent withReasoning(
      InteractionEvent event, List<Map<String, Object>> blocks) {
    Map<String, Object> merged = new LinkedHashMap<>(event.attributes());
    // Tempdoc 859 §A — APPEND, never replace. Under the flush rule a carrier can be written twice:
    // once by the ordinary flush and once by the trailing rule below, when a journal's last records
    // reason with no projecting event after them. Overwriting would silently drop the first set.
    List<Object> all = new ArrayList<>();
    if (event.attributes().get("reasoning") instanceof List<?> existing) {
      all.addAll(existing);
    }
    all.addAll(blocks);
    merged.put("reasoning", List.copyOf(all));
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
  static Map<String, Object> castMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }

  private static String str(Object o) {
    return o instanceof String s ? s : o == null ? "" : String.valueOf(o);
  }

  /**
   * Tempdoc 863 §4.A.3 (A-2) — the id prefix the AGENT terminal {@code done} mints its single
   * {@code ASSISTANT_MESSAGE} under. Exported so the one caller that suppresses that event for a
   * stamped run ({@code AgentRunQueryService.threadEvents}) keys on the mint itself rather than on
   * "the last assistant event": a WORKFLOW run's per-node answers are also {@code ASSISTANT_MESSAGE}s
   * and mint under {@code :node:} instead, so they are untouched by that filter, and a rule keyed on
   * position would have silently eaten the last node of every workflow run.
   */
  public static String terminalAnswerIdPrefix(String conversationId) {
    return conversationId + ":assistant:";
  }

  /** Is this the event {@link #terminalAnswerIdPrefix} describes — the agent run's own answer? */
  public static boolean isTerminalAnswer(InteractionEvent event, String conversationId) {
    return event.kind() == InteractionEventKind.ASSISTANT_MESSAGE
        && event.id().startsWith(terminalAnswerIdPrefix(conversationId));
  }

  /**
   * Tempdoc 859 §D (F6 follow-up) — a durable progress note's stable id, built on the SAME rule
   * {@code nodeEventId} states: LEXICAL order == TEMPORAL order on a same-millisecond tie, because
   * the FE tiebreaker is {@code id.localeCompare}. The journal ORDINAL leads the phase for exactly
   * that reason — ordered by phase name, {@code context_compacted} would sort ahead of the
   * {@code context_gate_reapplied} note that explains it, drawing the effect above its cause on the
   * one path that emits them back-to-back.
   */
  private static String progressEventId(
      String conversationId, int ordinal, String phase, String stamp) {
    return conversationId
        + ":progress:"
        + String.format(java.util.Locale.ROOT, "%05d", ordinal)
        + ":"
        + phase
        + ":"
        + stamp;
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

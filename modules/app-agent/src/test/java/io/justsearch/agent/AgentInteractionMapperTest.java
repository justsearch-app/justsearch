package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-A/P-B (correction) — read-time projection of a persisted {@code AgentRunStore} event
 * record (the {@code events.ndjson} shape) into a thread {@link InteractionEvent}. This is a
 * projection of the durable agent record, not a write to a second store.
 */
final class AgentInteractionMapperTest {

  private static final String CONV = "conv-1";

  private static Map<String, Object> rec(String eventType, Map<String, Object> payload) {
    return Map.of("timestamp", "2026-01-01T00:00:01Z", "eventType", eventType, "payload", payload);
  }

  private static InteractionEvent mapped(String eventType, Map<String, Object> payload) {
    return AgentInteractionMapper.fromRunEvent(rec(eventType, payload), CONV).orElseThrow();
  }

  @Test
  @DisplayName("done -> ASSISTANT_MESSAGE carrying the final response")
  void doneToAssistant() {
    InteractionEvent e =
        mapped("done", Map.of("finalResponse", "here is the answer", "iterationsUsed", 3));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, e.kind());
    assertEquals("agent", e.originator());
    assertEquals("here is the answer", e.content());
    assertEquals(CONV, e.conversationId());
    assertTrue(e.attributes().isEmpty(), "no grounding -> empty attributes");
  }

  @Test
  @DisplayName("done -> ASSISTANT_MESSAGE projects the answer's grounding sources + citations (565)")
  void doneCarriesGroundingEvidence() {
    Map<String, Object> source =
        Map.of("parentDocId", "f:/docs/x.md", "startLine", 1, "endLine", 25, "excerpt", "an excerpt");
    Map<String, Object> cite =
        Map.of("sentenceText", "a grounded sentence", "sourceIndex", 0, "similarity", 0.7);
    InteractionEvent e =
        mapped(
            "done",
            Map.of(
                "finalResponse", "the answer",
                "sources", List.of(source),
                "citations", List.of(cite)));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, e.kind());
    assertEquals("the answer", e.content());
    // §3.A/persistence — the grounding rides the persisted ASSISTANT_MESSAGE attributes so a reloaded
    // thread renders the same Sources pane + inline marks.
    assertEquals(List.of(source), e.attributes().get("sources"));
    assertEquals(List.of(cite), e.attributes().get("citations"));
  }

  @Test
  @DisplayName("tool_call_proposed -> TOOL_ACTIVITY carrying the tool identity (toolName + arguments) (565 §12.3.B)")
  void proposedToolCarriesIdentity() {
    InteractionEvent e =
        mapped(
            "tool_call_proposed",
            Map.of(
                "callId", "call-9",
                "toolName", "core_search",
                "arguments", "{\"query\":\"x\"}",
                "risk", "low"));
    assertEquals(InteractionEventKind.TOOL_ACTIVITY, e.kind());
    assertEquals("call-9", e.attributes().get("callId"));
    assertEquals("core_search", e.attributes().get("toolName"));
    // §12.3.B — the compact tool row needs the verb+target on the record (reload); arguments survive.
    assertEquals("{\"query\":\"x\"}", e.attributes().get("arguments"));
    assertEquals("proposed", e.attributes().get("status"));
  }

  @Test
  @DisplayName("tool_exec_completed -> completed TOOL_ACTIVITY keyed by callId")
  void completedTool() {
    InteractionEvent e =
        mapped(
            "tool_exec_completed", Map.of("callId", "call-7", "success", true, "output", "12 results"));
    assertEquals(InteractionEventKind.TOOL_ACTIVITY, e.kind());
    assertEquals("call-7:completed", e.id());
    assertEquals("completed", e.attributes().get("status"));
    assertEquals(Boolean.TRUE, e.attributes().get("success"));
    assertEquals("12 results", e.attributes().get("output"));
  }

  @Test
  @DisplayName("tool_call_pending -> pending TOOL_ACTIVITY carrying risk")
  void pendingTool() {
    InteractionEvent e =
        mapped(
            "tool_call_pending",
            Map.of("callId", "call-9", "toolName", "core_file_operations", "risk", "high"));
    assertEquals("call-9:pending", e.id());
    assertEquals("pending", e.attributes().get("status"));
    assertEquals("core_file_operations", e.attributes().get("toolName"));
    assertEquals("high", e.attributes().get("risk"));
  }

  @Test
  @DisplayName("rejected -> TOOL_ACTIVITY; error -> ERROR; handoff_executed -> HANDOFF")
  void rejectedErrorHandoff() {
    InteractionEvent rej =
        mapped("tool_call_rejected", Map.of("callId", "call-3", "reason", "User rejected"));
    assertEquals(InteractionEventKind.TOOL_ACTIVITY, rej.kind());
    assertEquals("call-3:rejected", rej.id());
    assertEquals("rejected", rej.attributes().get("status"));
    assertEquals("User rejected", rej.attributes().get("reason"));

    InteractionEvent err = mapped("error", Map.of("error", "boom", "errorCode", "LLM_ERROR"));
    assertEquals(InteractionEventKind.ERROR, err.kind());
    assertEquals("boom", err.content());
    assertEquals("LLM_ERROR", err.attributes().get("errorCode"));

    InteractionEvent ho =
        mapped("handoff_executed", Map.of("fromAgentId", "primary", "toAgentId", "researcher"));
    assertEquals(InteractionEventKind.HANDOFF, ho.kind());
    assertEquals("primary", ho.attributes().get("fromAgentId"));
    assertEquals("researcher", ho.attributes().get("toAgentId"));
  }

  @Test
  @DisplayName("search_executed -> SEARCH carrying query/mode/matchCount/resultCount/docIds/executedAt (S4b)")
  void searchExecutedToSearch() {
    InteractionEvent e =
        mapped(
            "search_executed",
            Map.of(
                "query", "invoices",
                "mode", "hybrid",
                "matchCount", 42,
                "resultCount", 10,
                "docIds", List.of("a.pdf", "b.pdf"),
                "executedAt", "2026-07-06T00:00:00Z"));
    assertEquals(InteractionEventKind.SEARCH, e.kind());
    assertEquals("user", e.originator());
    assertEquals("", e.content());
    assertEquals("invoices", e.attributes().get("query"));
    assertEquals("hybrid", e.attributes().get("mode"));
    assertEquals(42, e.attributes().get("matchCount"));
    assertEquals(10, e.attributes().get("resultCount"));
    assertEquals(List.of("a.pdf", "b.pdf"), e.attributes().get("docIds"));
    assertEquals("2026-07-06T00:00:00Z", e.attributes().get("executedAt"));
    // The id is the shared searchEventId derivation (conversationId:search:<epochMilli>), the same one
    // AgentRunStore.appendSearchEvent hands back to the write-path caller.
    assertEquals("conv-1:search:1767225601000", e.id());
  }

  @Test
  @DisplayName("node_started/node_completed -> PROGRESS boundary events carrying the node identity (565 §26.A)")
  void nodeBoundariesProjected() {
    InteractionEvent start =
        mapped("node_started", Map.of("nodeId", "think", "kind", "llm", "index", 0));
    assertEquals(InteractionEventKind.PROGRESS, start.kind());
    assertEquals("start", start.attributes().get("nodeBoundary"));
    assertEquals("think", start.attributes().get("nodeId"));
    assertEquals("llm", start.attributes().get("nodeKind"));
    assertEquals("think", start.attributes().get("label"));

    InteractionEvent end =
        mapped("node_completed", Map.of("nodeId", "think", "kind", "llm", "index", 0, "output", "ok"));
    assertEquals(InteractionEventKind.PROGRESS, end.kind());
    assertEquals("end", end.attributes().get("nodeBoundary"));
    assertEquals("think", end.attributes().get("nodeId"));

    // §26.I — pin the temporally-sortable id format: `…:node:<5-digit index>:<role 1=start|2=output|3=end>:…`
    // so a same-millisecond tie sorts start < output < end (and node N's end < node N+1's start). A
    // backend refactor that swaps the index/role order or the role digits must fail HERE, not only in the
    // FE projection tests.
    assertTrue(start.id().startsWith("conv-1:node:00000:1:think:"), start.id());
    assertTrue(end.id().startsWith("conv-1:node:00000:3:think:"), end.id());
  }

  @Test
  @DisplayName("node_output -> the node's durable ASSISTANT_MESSAGE; workflow done (nodesExecuted) is skipped (565 §26.I)")
  void nodeOutputAndWorkflowDone() {
    InteractionEvent out =
        mapped(
            "node_output",
            Map.of("nodeId", "draft", "kind", "llm", "index", 1, "output", "a two-sentence brief"));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, out.kind());
    assertEquals("a two-sentence brief", out.content());
    assertEquals("agent", out.originator());
    // §26.I — role 2 (output) sorts between role 1 (start) and role 3 (end) of the same node index.
    assertTrue(out.id().startsWith("conv-1:node:00001:2:draft:"), out.id());

    // A WORKFLOW terminal done carries `nodesExecuted` and must NOT also emit an assistant (the per-node
    // node_outputs are the content) — else the last node renders twice on reload.
    assertTrue(
        AgentInteractionMapper.fromRunEvent(
                rec("done", Map.of("finalResponse", "a two-sentence brief", "nodesExecuted", 2)), CONV)
            .isEmpty(),
        "workflow done is a pure terminal, not an answer bubble");

    // An AGENT done (no nodesExecuted) is still the answer.
    InteractionEvent agentDone = mapped("done", Map.of("finalResponse", "the agent answer"));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, agentDone.kind());
    assertEquals("the agent answer", agentDone.content());
  }

  @Test
  @DisplayName("transient/streaming events are not durable thread content (empty)")
  void transientSkipped() {
    assertTrue(
        AgentInteractionMapper.fromRunEvent(rec("chunk", Map.of("text", "partial")), CONV).isEmpty());
    assertTrue(
        AgentInteractionMapper.fromRunEvent(rec("session_started", Map.of("sessionId", "s")), CONV)
            .isEmpty());
    assertTrue(
        AgentInteractionMapper.fromRunEvent(rec("tool_call_approved", Map.of("callId", "c")), CONV)
            .isEmpty());
  }

  // ============ Tempdoc 859 §D (F6 follow-up) — progress durability by classification ============

  @Test
  @DisplayName("859 §D F6: a LIVENESS progress phase stays ephemeral — including an unknown one")
  void livenessProgressStaysEphemeral() {
    // The rule is an ALLOW-list, so the interesting assertion is the default arm: a phase nobody
    // classified is ephemeral, and a phase added to the emitters later cannot start writing itself
    // into every reloaded conversation without someone deciding it should.
    for (String phase :
        List.of(
            "llm_call",
            "init",
            "finalizing",
            "budget_gate_held",
            "context_gate_held",
            "retry_after_tool_failure",
            "run_unobserved_parked",
            "workflow:node_started",
            "a_phase_nobody_has_written_yet")) {
      assertTrue(
          AgentInteractionMapper.fromRunEvent(
                  rec("progress", Map.of("phase", phase, "message", "m")), CONV)
              .isEmpty(),
          phase + " narrates what the run is doing, so it does not outlive the run");
    }
    // A record with no phase at all (a malformed/legacy row) is ephemeral rather than a blank note.
    assertTrue(AgentInteractionMapper.fromRunEvent(rec("progress", Map.of()), CONV).isEmpty());
  }

  @Test
  @DisplayName("859 §D F6: budget_raised -> a durable PROGRESS note carrying the amount it granted")
  void budgetRaiseNarrationIsDurable() {
    // THE gap this closes: §D's guard rail promises "every silent continue is NARRATED", and before
    // this the note lived only in the session's own SSE stream — so the accountability record for a
    // budget the reader never approved was gone the moment the conversation was reloaded.
    InteractionEvent e =
        mapped(
            "progress",
            Map.of(
                "phase", "budget_raised",
                "message", "+12,000 tokens — continuing",
                "iteration", 3,
                "maxIterations", 8,
                "severity", "info"));
    assertEquals(InteractionEventKind.PROGRESS, e.kind());
    assertEquals("agent", e.originator());
    // The AMOUNT survives, which is the whole point: "the budget was raised" without the number is
    // not an accountability record. It rides `content` because that is where both windows' note
    // renderers read the text from.
    assertEquals("+12,000 tokens — continuing", e.content());
    assertEquals("budget_raised", e.attributes().get("phase"));
    assertEquals("info", e.attributes().get("severity"));
    assertEquals(
        CONV + ":progress:00000:budget_raised:" + Instant.parse("2026-01-01T00:00:01Z").toEpochMilli(),
        e.id());
  }

  @Test
  @DisplayName("owner 2026-08-26: the reader's Stop is a durable PROGRESS note")
  void stopRequestNarrationIsDurable() {
    // The late-cancel gap: a stop that lands as the run reaches its terminal changes nothing about
    // the run, so the disposition stays COMPLETED and this note is the ONLY durable trace of the act.
    InteractionEvent e =
        mapped(
            "progress",
            Map.of(
                "phase", AgentEvent.AgentProgress.PHASE_STOP_REQUESTED,
                "message", "You stopped this run",
                "iteration", 0,
                "maxIterations", 0,
                "severity", "info"));
    assertEquals(InteractionEventKind.PROGRESS, e.kind());
    assertEquals("You stopped this run", e.content());
    assertEquals(
        AgentEvent.AgentProgress.PHASE_STOP_REQUESTED, e.attributes().get("phase"));
  }

  @Test
  @DisplayName("859 §D F6: two same-millisecond notes keep EMISSION order, not phase-name order")
  void sameMillisecondNotesSortByEmissionNotPhaseName() {
    // The F-1 defect. `context_compacted`.localeCompare(`context_gate_reapplied`) is -1, so an id
    // without an emission ordinal sorts the COMPACTION above the note explaining why it happened —
    // effect before cause. And these two are emitted back-to-back on the §2.7 second-crossing path
    // (AgentStepRunner: the re-apply note, `compactOlderTurns`, then the compaction note), so the
    // millisecond tie is the NORMAL case there, not a rare one.
    String tie = "2026-01-01T00:00:07Z";
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                at(tie, "progress",
                    Map.of("phase", "context_gate_reapplied", "message", "Compacting without asking again")),
                at(tie, "progress",
                    Map.of("phase", "context_compacted", "message", "Compacted 4 earlier turns"))),
            CONV);

    assertEquals(2, events.size());
    // The assertion that would fail without the ordinal: the FE tiebreaker on equal timestamps is
    // `id.localeCompare`, so the RENDERED order is the sorted-by-id order — compared here, not the
    // list order, which would pass either way because `fromRunEvents` emits in journal order.
    List<String> byId = events.stream().map(InteractionEvent::id).sorted().toList();
    assertEquals(
        List.of(events.get(0).id(), events.get(1).id()),
        byId,
        "lexical id order must equal emission order on a same-millisecond tie");
    assertTrue(
        byId.get(0).contains("context_gate_reapplied"),
        "the CAUSE (re-applied) must sort before the EFFECT (compacted), got: " + byId);
    // …and the ordinal is the journal index, zero-padded so 10 does not sort before 9.
    assertTrue(events.get(0).id().startsWith(CONV + ":progress:00000:"), events.get(0).id());
    assertTrue(events.get(1).id().startsWith(CONV + ":progress:00001:"), events.get(1).id());
  }

  @Test
  @DisplayName("859 §D F6: every declared PHASE_* constant is classified — durable or documented-ephemeral")
  void everyDeclaredPhaseConstantIsClassified() throws IllegalAccessException {
    // The UNGUARDED direction (review F-4): the mapper's allow-list defaults to ephemeral, so a new
    // PHASE_* constant that someone declares and emits but forgets to add to DURABLE_PROGRESS_PHASES
    // is silently non-durable — which is precisely the bug this whole change fixes, reintroduced.
    // Reflection over the constants is what makes "someone declared a new one" observable at all.
    //
    // A constant may legitimately be ephemeral; it may not be UNCLASSIFIED. Adding it to either list
    // is a deliberate act, and that is the whole ask.
    Set<String> documentedEphemeral = Set.of();
    List<String> unclassified = new ArrayList<>();
    for (java.lang.reflect.Field f : AgentEvent.AgentProgress.class.getDeclaredFields()) {
      if (!f.getName().startsWith("PHASE_")
          || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
        continue;
      }
      String phase = (String) f.get(null);
      if (!AgentInteractionMapper.DURABLE_PROGRESS_PHASES.contains(phase)
          && !documentedEphemeral.contains(phase)) {
        unclassified.add(f.getName() + " (\"" + phase + "\")");
      }
    }
    assertEquals(
        List.of(),
        unclassified,
        "a declared progress phase must be listed in AgentInteractionMapper.DURABLE_PROGRESS_PHASES"
            + " or in this test's documentedEphemeral set — an unclassified one is silently ephemeral");
    // The guard is only meaningful if it is actually looking at constants: assert it FOUND them.
    // 859 D live-defect D4 added the fifth (PHASE_CONTEXT_COMPACTED_TO_FIT) — and this line going
    // red is exactly the guard working: the constant could not be added without classifying it.
    // The owner's 2026-08-26 late-cancel decision added the sixth (PHASE_STOP_REQUESTED).
    assertEquals(6, AgentInteractionMapper.DURABLE_PROGRESS_PHASES.size());
  }

  @Test
  @DisplayName("859 §D F6: the other accountability phases are durable too; severity is optional")
  void contextAccountabilityNotesAreDurable() {
    InteractionEvent unanswered =
        mapped(
            "progress",
            Map.of("phase", "context_gate_unanswered", "message", "Context gate unanswered — continuing"));
    assertEquals(InteractionEventKind.PROGRESS, unanswered.kind());
    assertEquals("Context gate unanswered — continuing", unanswered.content());

    InteractionEvent reapplied =
        mapped(
            "progress",
            Map.of(
                "phase", "context_gate_reapplied",
                "message", "Context filling up again — compacting without asking again"));
    assertEquals(InteractionEventKind.PROGRESS, reapplied.kind());
    assertEquals(
        "Context filling up again — compacting without asking again", reapplied.content());
    // A record persisted before 577 Ext II carries no severity; `attrs` drops the null rather than
    // writing a "null" string the renderer would have to defend against.
    assertNull(reapplied.attributes().get("severity"));

    InteractionEvent compacted =
        mapped(
            "progress",
            Map.of(
                "phase", "context_compacted",
                "message", "Compacted 4 earlier turns to stay within the model's memory",
                "severity", "warn"));
    assertEquals(InteractionEventKind.PROGRESS, compacted.kind());
    assertEquals("warn", compacted.attributes().get("severity"));

    // 859 D live-defect D4 — the run compacted DESPITE the reader's CONTINUE. It is emitted two
    // lines above `context_compacted` in the same block, so an ephemeral classification would leave
    // a reloaded run showing the compaction with the reason it happened silently removed: the
    // reader would see the loop shorten their history and no record of why.
    InteractionEvent toFit =
        mapped(
            "progress",
            Map.of(
                "phase", "context_compacted_to_fit",
                "message",
                    "Compacted to fit before continuing — the next prompt did not fit the model's"
                        + " memory",
                "severity", "info"));
    assertEquals(InteractionEventKind.PROGRESS, toFit.kind());
    assertEquals("agent", toFit.originator());
    assertEquals(
        "Compacted to fit before continuing — the next prompt did not fit the model's memory",
        toFit.content(),
        "the JUSTIFICATION is the payload — a durable note that lost its reason is not a record");
    assertEquals("context_compacted_to_fit", toFit.attributes().get("phase"));
  }

  @Test
  @DisplayName("859 D4: the to-fit note and the compaction it explains survive together, in order")
  void compactedToFitSurvivesBesideTheCompactionItExplains() {
    // The pair is emitted back-to-back on the unservable-CONTINUE path (AgentStepRunner: the to-fit
    // note, then ContextCompacted, then the count-bearing compaction note), so the same-millisecond
    // tie the F-1 defect exposed is the NORMAL case here too — and here the ordering carries the
    // cause: the reason must sort above the compaction it justifies, never below it.
    String tie = "2026-01-01T00:00:09Z";
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                at(tie, "progress",
                    Map.of(
                        "phase", "context_compacted_to_fit",
                        "message", "Compacted to fit before continuing")),
                at(tie, "progress",
                    Map.of("phase", "context_compacted", "message", "Compacted 2 earlier turns"))),
            CONV);

    assertEquals(2, events.size(), "BOTH notes are durable — neither may be dropped on reload");
    List<String> byId = events.stream().map(InteractionEvent::id).sorted().toList();
    assertTrue(
        byId.get(0).contains("context_compacted_to_fit"),
        "the REASON must sort before the compaction it explains, got: " + byId);
    assertEquals(
        List.of(events.get(0).id(), events.get(1).id()),
        byId,
        "lexical id order must equal emission order on a same-millisecond tie");
  }

  @Test
  @DisplayName("859 §D F6: a durable progress note is a PROJECTING flush carrier, as it is live")
  void durableProgressCarriesTheReasoningBlockItCut() {
    // The A-slice interaction. `progress` has always CUT the region (it is a genuine step boundary);
    // before this it could not CARRY, so the block was held for the next projecting event — which on
    // this journal is the terminal answer, three steps later. Now the cut and the carry are the same
    // event, which is what the live side has always done (`onProgress` appends an entry, so the open
    // region is committed and the note follows it — C-7b). The record's carrier set is now the live
    // one restricted to the durable phases; M-2 below still pins the held path for the rest.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "this will need more room"),
                at("2026-01-01T00:00:02Z", "budget_update", Map.of("phase", "llm_response")),
                at("2026-01-01T00:00:03Z", "progress",
                    Map.of("phase", "budget_raised", "message", "+12,000 tokens — continuing")),
                reasoningAt("2026-01-01T00:00:04Z", "now answer"),
                at("2026-01-01T00:00:05Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    assertEquals(2, events.size(), "the note and the answer — nothing else projects here");
    InteractionEvent note = events.get(0);
    assertEquals(InteractionEventKind.PROGRESS, note.kind());
    // Chronology: the model thought, ran out of room, the system granted more. The block therefore
    // renders immediately ABOVE the note, which is where it was produced.
    assertEquals(List.of("this will need more room"), blockTexts(note));
    // …and the block that followed the raise is NOT swept onto it.
    assertEquals(List.of("now answer"), blockTexts(events.get(1)));
  }

  // ==================== Tempdoc 848 §2.4 — the reasoning fold ====================

  private static Map<String, Object> at(String isoTime, String eventType, Map<String, Object> payload) {
    return Map.of("timestamp", isoTime, "eventType", eventType, "payload", payload);
  }

  private static Map<String, Object> reasoningAt(String isoTime, String text) {
    return at(isoTime, "reasoning_chunk", Map.of("text", text));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> blocksOf(InteractionEvent event) {
    Object raw = event.attributes().get("reasoning");
    assertTrue(raw instanceof List<?>, "expected folded reasoning blocks on " + event.kind());
    return (List<Map<String, Object>>) raw;
  }

  @Test
  @DisplayName("848 §2.4: a bare reasoning_chunk is still no thread event of its own (445 chunks, 0 events)")
  void reasoningChunkAloneProjectsNothing() {
    assertTrue(
        AgentInteractionMapper.fromRunEvent(reasoningAt("2026-01-01T00:00:01Z", "hm"), CONV)
            .isEmpty(),
        "reasoning is folded onto a turn, never emitted per chunk");
  }

  private static InteractionEvent onlyOfKind(
      List<InteractionEvent> events, InteractionEventKind kind) {
    return events.stream().filter(e -> e.kind() == kind).findFirst().orElseThrow();
  }

  private static InteractionEvent byId(List<InteractionEvent> events, String id) {
    return events.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }

  private static List<String> blockTexts(InteractionEvent event) {
    return blocksOf(event).stream().map(b -> String.valueOf(b.get("text"))).toList();
  }

  @Test
  @DisplayName(
      "859 §A M-1: a budget_update between the reasoning and the tool cannot swallow the block —"
          + " it flushes onto the TOOL, not the answer")
  void blockFlushesOntoTheNextEventThatProjects() {
    // THE case the superseded rule could not express (D-1). `budget_update` is emitted the instant
    // each LLM stream ends — i.e. between the reasoning and the tool call it produced — and this
    // projection DROPS it (`fromRunEvent` has no case for it). So "attach the block to the event that
    // cut it" names a carrier that does not exist downstream, and the rule this replaces
    // ("re-target onto the next ASSISTANT_MESSAGE") piled every block onto the terminal answer.
    //
    // This test replaces `reasoningFoldsIntoPerStepBlocks` (848 §2.4): SAME fold, SAME durations,
    // new carrier. Its old assertion — both blocks on the answer — is the defect, not the contract.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "first "),
                reasoningAt("2026-01-01T00:00:02Z", "thought"),
                at("2026-01-01T00:00:04Z", "budget_update", Map.of("phase", "llm_response")),
                at("2026-01-01T00:00:05Z", "tool_call_proposed",
                    Map.of("callId", "c1", "toolName", "core_search")),
                at("2026-01-01T00:00:06Z", "tool_exec_completed",
                    Map.of("callId", "c1", "success", true)),
                reasoningAt("2026-01-01T00:00:07Z", "second thought"),
                at("2026-01-01T00:00:08Z", "budget_update", Map.of("phase", "llm_response")),
                at("2026-01-01T00:00:09Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    InteractionEvent tool = byId(events, "c1:proposed");
    assertEquals(List.of("first thought"), blockTexts(tool), "block 1 rides the step it produced");
    InteractionEvent answer = onlyOfKind(events, InteractionEventKind.ASSISTANT_MESSAGE);
    assertEquals(
        List.of("second thought"),
        blockTexts(answer),
        "and ONLY the block that preceded the answer is on the answer");
    // §2.1's ONE duration semantic: first reasoning token → first NON-reasoning output that follows.
    // Measured to the budget_update (3000ms), NOT to the block's own last chunk (1000ms) and NOT to
    // the carrier it ends up on.
    assertEquals(3000L, ((Number) blocksOf(tool).get(0).get("durationMs")).longValue());
    assertEquals(1000L, ((Number) blocksOf(answer).get(0).get("durationMs")).longValue());
    // A-3: the attributes were reconstructed, not mutated — `InteractionEvent` copies them
    // immutably, so a `put` on the delegate's map would have thrown before reaching this line.
    assertEquals("the answer", answer.content());
  }

  @Test
  @DisplayName("859 §A M-2: a LIVENESS progress event cuts the block and cannot carry it — it is held")
  void blockCutByANonProjectingProgressEventIsNotDropped() {
    // The second shape with no carrier: `llm_call` cuts (it is a genuine step boundary) and projects
    // nothing on the agent plane. Under the superseded rule the block had nowhere to go at the cut;
    // if an implementation attached it there instead of holding it, this run would render no
    // thinking at all.
    //
    // Tempdoc 859 §D F6 kept this shape reachable rather than deleting it: only the ACCOUNTABILITY
    // phases became carriers, so the held path is what every remaining phase still takes — which is
    // why the phase below is named `llm_call` and not left generic.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "half a plan"),
                at("2026-01-01T00:00:02Z", "progress", Map.of("phase", "llm_call")),
                at("2026-01-01T00:00:03Z", "chunk", Map.of("text", "the ")),
                at("2026-01-01T00:00:04Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    assertEquals(1, events.size(), "progress and chunk are both transient");
    assertEquals(List.of("half a plan"), blockTexts(events.get(0)));
  }

  @Test
  @DisplayName("859 §A M-3: chunk transparency survives a non-projecting cut — exactly ONE block")
  void chunkTransparencyAcrossANonProjectingCut() {
    // 848's measured five-region shape, with the real journal's `budget_update` in it. `== 1`, not
    // `>= 1`: a fold that shattered the step into two would also pass a `>=` assertion.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "part one "),
                at("2026-01-01T00:00:02Z", "chunk", Map.of("text", "VISIBLE ANSWER TEXT")),
                reasoningAt("2026-01-01T00:00:03Z", "part two"),
                at("2026-01-01T00:00:04Z", "budget_update", Map.of("phase", "llm_response")),
                at("2026-01-01T00:00:05Z", "tool_call_proposed",
                    Map.of("callId", "c1", "toolName", "grep"))),
            CONV);

    InteractionEvent tool = byId(events, "c1:proposed");
    assertEquals(1, blocksOf(tool).size(), "one LLM step, one block");
    assertEquals("part one part two", blocksOf(tool).get(0).get("text"), "visible text excluded");
  }

  @Test
  @DisplayName("859 §A M-5: a journal whose LAST record is a reasoning_chunk still keeps the block")
  void truncatedJournalKeepsItsTrailingBlock() {
    // The only shape that reaches the trailing rule now — every other run flushes on the way past.
    // A terminal-ERROR run no longer proves anything here, because the ordinary flush carries it.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                at("2026-01-01T00:00:01Z", "tool_call_proposed",
                    Map.of("callId", "c1", "toolName", "grep")),
                reasoningAt("2026-01-01T00:00:03Z", "the process died here")),
            CONV);

    assertEquals(1, events.size());
    assertEquals(List.of("the process died here"), blockTexts(events.get(0)));
  }

  @Test
  @DisplayName("859 §A M-5b: a trailing block APPENDS to a carrier that already carries one")
  void trailingBlockAppendsRatherThanReplaces() {
    // Reachable because the flush rule writes carriers far more often than the retarget rule did:
    // the tool takes block 1 on the way past, and the trailing rule then targets the same (last)
    // event with block 2. A `put` would silently drop the first.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "before the tool"),
                at("2026-01-01T00:00:02Z", "tool_call_proposed",
                    Map.of("callId", "c1", "toolName", "grep")),
                reasoningAt("2026-01-01T00:00:03Z", "after the tool")),
            CONV);

    assertEquals(1, events.size());
    assertEquals(List.of("before the tool", "after the tool"), blockTexts(events.get(0)));
  }

  @Test
  @DisplayName("859 §A M-6: three tools, three regions — three separate carriers, never accumulated")
  void everyStepCarriesItsOwnThinking() {
    List<Map<String, Object>> records = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      records.add(reasoningAt("2026-01-01T00:00:0" + (i * 2 - 1) + "Z", "thought " + i));
      records.add(at("2026-01-01T00:00:0" + (i * 2) + "Z", "budget_update",
          Map.of("phase", "llm_response")));
      records.add(at("2026-01-01T00:00:0" + (i * 2) + "Z", "tool_call_proposed",
          Map.of("callId", "c" + i, "toolName", "grep")));
    }
    records.add(at("2026-01-01T00:00:09Z", "done", Map.of("finalResponse", "the answer")));

    List<InteractionEvent> events = AgentInteractionMapper.fromRunEvents(records, CONV);
    for (int i = 1; i <= 3; i++) {
      assertEquals(List.of("thought " + i), blockTexts(byId(events, "c" + i + ":proposed")));
    }
    assertTrue(
        !onlyOfKind(events, InteractionEventKind.ASSISTANT_MESSAGE)
            .attributes()
            .containsKey("reasoning"),
        "nothing accumulated onto the terminal answer");
  }

  @Test
  @DisplayName("859 §A: handoff_proposed cuts on the PRODUCING agent's side of the boundary")
  void handoffCutsOnTheProducingSide() {
    // `handoff_proposed` projects nothing; `handoff_executed` does. So the outgoing agent's last
    // thought lands on the HANDOFF line, which is the honest reading: the reader sees what the agent
    // that was working thought, above the line saying the task moved.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "this needs the researcher"),
                at("2026-01-01T00:00:02Z", "handoff_proposed",
                    Map.of("fromAgentId", "primary", "toAgentId", "researcher", "reason", "scope")),
                at("2026-01-01T00:00:03Z", "handoff_executed",
                    Map.of("fromAgentId", "primary", "toAgentId", "researcher"))),
            CONV);

    InteractionEvent handoff = onlyOfKind(events, InteractionEventKind.HANDOFF);
    assertEquals(List.of("this needs the researcher"), blockTexts(handoff));
  }

  @Test
  @DisplayName("859 §A: a TRUNCATED run puts a block on two lifecycle events of ONE call (the FE unions them)")
  void twoLifecycleEventsOfOneCallEachCarryABlock() {
    // The producer half of M-7, in the ONE shape that actually reaches it.
    //
    // Reasoning cannot be emitted BETWEEN two lifecycle events of a call: `ReasoningChunk` is
    // emitted from inside the LLM stream (`AgentLlmCaller.java:212`) and the tool calls parsed out
    // of that stream are not dispatched until it closes (`AgentStepRunner`), so the two phases never
    // interleave. What DOES reach it is a run that is cut off mid-thought: block 1 flushes onto
    // `c1:proposed` on the way past, and the region still open when the journal ends is attached by
    // the trailing rule to the run's last event — `c1:completed`, the same call.
    //
    // So the FE's array-union of `reasoning` is LOAD-BEARING, not defensive: the merge is
    // later-wins, and without it block 1 is dropped outright.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "I should search"),
                at("2026-01-01T00:00:02Z", "budget_update", Map.of("phase", "llm_response")),
                at("2026-01-01T00:00:03Z", "tool_call_proposed",
                    Map.of("callId", "c1", "toolName", "core_search")),
                at("2026-01-01T00:00:04Z", "tool_exec_completed",
                    Map.of("callId", "c1", "success", true)),
                // The process died here, mid-thought.
                reasoningAt("2026-01-01T00:00:05Z", "the results are thin, I should widen")),
            CONV);

    assertEquals(List.of("I should search"), blockTexts(byId(events, "c1:proposed")));
    assertEquals(
        List.of("the results are thin, I should widen"),
        blockTexts(byId(events, "c1:completed")));
  }

  @Test
  @DisplayName("848 §2.4 (A-4): text chunks are TRANSPARENT — one LLM step stays one block")
  void textChunksDoNotShatterABlock() {
    // Reasoning and text share one stream, and a think-tag-leaking build reroutes inline <think>
    // markup into the reasoning sink mid-stream. Cutting on bare contiguity would yield a different
    // block count per build family for identical model behaviour.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "part one "),
                at("2026-01-01T00:00:02Z", "chunk", Map.of("text", "VISIBLE ANSWER TEXT")),
                reasoningAt("2026-01-01T00:00:03Z", "part two"),
                at("2026-01-01T00:00:04Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    List<Map<String, Object>> blocks =
        blocksOf(
            events.stream()
                .filter(e -> e.kind() == InteractionEventKind.ASSISTANT_MESSAGE)
                .findFirst()
                .orElseThrow());
    assertEquals(1, blocks.size(), "one step, one block");
    assertEquals("part one part two", blocks.get(0).get("text"), "the visible text is excluded");
  }

  @Test
  @DisplayName("859 §A M-4: durationMs still ends at the first output of ANY kind, not at the cut")
  void durationEndsAtTheFirstOutputNotAtTheCut() {
    // The 848 semantic is unchanged by the new carry rule, and it is worth pinning precisely because
    // the block now travels: a fold that measured to the CARRIER would report 8s of thinking for a
    // step that thought for 1s and then spent 7s streaming prose.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "a thought"),
                at("2026-01-01T00:00:02Z", "chunk", Map.of("text", "prose")),
                at("2026-01-01T00:00:09Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    assertEquals(
        1000L,
        ((Number) blocksOf(events.get(0)).get(0).get("durationMs")).longValue(),
        "measured to the first chunk, not to the terminal that carries the block");
  }

  @Test
  @DisplayName("848 §2.4 (D-7): a run that ERRORED keeps the thinking it produced")
  void erroredRunKeepsItsReasoning() {
    // The ask path already records blocks at all four terminals, halt included, because what the
    // model thought before the reader stopped it was really produced. The two planes must agree.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "half a thought"),
                at("2026-01-01T00:00:03Z", "error", Map.of("error", "blew up", "errorCode", "X"))),
            CONV);

    InteractionEvent error =
        events.stream()
            .filter(e -> e.kind() == InteractionEventKind.ERROR)
            .findFirst()
            .orElseThrow();
    List<Map<String, Object>> blocks = blocksOf(error);
    assertEquals(1, blocks.size());
    assertEquals("half a thought", blocks.get(0).get("text"));
    assertEquals(2000L, ((Number) blocks.get(0).get("durationMs")).longValue());
  }

  @Test
  @DisplayName("848 F4: an unparseable timestamp cannot produce a 56-year 'Thought for' duration")
  void implausibleDurationIsReportedAsNone() {
    // `parseTs` falls back to Instant.EPOCH, so ONE bad timestamp would otherwise measure the block
    // from 1970. The TEXT is real and stays; the fabricated number does not.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                Map.of("timestamp", "not-a-timestamp", "eventType", "reasoning_chunk",
                    "payload", Map.of("text", "a real thought")),
                at("2026-01-01T00:00:03Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    List<Map<String, Object>> blocks = blocksOf(events.get(0));
    assertEquals("a real thought", blocks.get(0).get("text"));
    assertEquals(
        0L,
        ((Number) blocks.get(0).get("durationMs")).longValue(),
        "no measurement beats a fabricated one");
  }

  @Test
  @DisplayName("848 §2.4: a run that never reasoned carries no reasoning attribute at all")
  void runWithoutReasoningCarriesNoAttribute() {
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                at("2026-01-01T00:00:01Z", "chunk", Map.of("text", "streamed")),
                at("2026-01-01T00:00:02Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    assertEquals(1, events.size(), "the chunk is still transient");
    assertTrue(
        !events.get(0).attributes().containsKey("reasoning"),
        "no key — absence is the honest reading, not an empty list");
  }

  @Test
  @DisplayName("848 §2.4: the fold preserves every event the per-record projection produced")
  void foldIsAProjectionNotAFilter() {
    List<Map<String, Object>> records =
        List.of(
            at("2026-01-01T00:00:01Z", "tool_call_proposed", Map.of("callId", "c1", "toolName", "grep")),
            reasoningAt("2026-01-01T00:00:02Z", "consider"),
            at("2026-01-01T00:00:03Z", "tool_exec_completed", Map.of("callId", "c1", "success", true)),
            at("2026-01-01T00:00:04Z", "done", Map.of("finalResponse", "the answer")));

    List<InteractionEvent> folded = AgentInteractionMapper.fromRunEvents(records, CONV);
    List<InteractionEvent> perRecord =
        records.stream()
            .map(r -> AgentInteractionMapper.fromRunEvent(r, CONV))
            .filter(java.util.Optional::isPresent)
            .map(java.util.Optional::get)
            .toList();

    assertEquals(
        perRecord.stream().map(InteractionEvent::id).toList(),
        folded.stream().map(InteractionEvent::id).toList(),
        "same events, same order — the fold only decorates the turn with its thinking");
  }

  /**
   * Tempdoc 865 §7.5 — THE ROUND TRIP. The inclusion state is only worth minting if it survives the
   * journal: an honesty fact that expires on reload is worse than one never made, because the reader
   * has already learned to trust it (the same argument 859 §D §2.6 made for {@code disposition}).
   *
   * <p>It rides the SOURCE MAP rather than a sibling key, which is what makes the record path need
   * no change at all: the {@code done} case copies {@code payload.sources} verbatim, so the two keys
   * travel with the source they describe and the reloaded panel reads them through the same {@code
   * AnswerEvidenceSource} the live panel does.
   */
  @Test
  @DisplayName("865 §7.5: a dropped source's inclusion survives the wire → record round trip")
  void inclusionSurvivesTheRecordPath() {
    var source =
        new AgentEvent.AgentSource("d1", 0, "/docs/d1.md", "D1", "ex", 1, 5, "")
            .withInclusion("dropped", 0);
    var done =
        new AgentEvent.AgentDone(
            "the answer", 1, 1, 10, List.of(source), List.of(), "CROSS_ENCODER");

    Map<String, Object> payload = io.justsearch.agent.api.AgentEventPayloads.base(done);
    Map<?, ?> wireSource = (Map<?, ?>) ((List<?>) payload.get("sources")).get(0);
    assertEquals("dropped", wireSource.get("contextInclusion"), "on the wire, RAG's own key");
    assertEquals(0, wireSource.get("contextIncludedChars"));

    InteractionEvent reloaded = mapped("done", payload);
    Map<?, ?> recordSource =
        (Map<?, ?>) ((List<?>) reloaded.attributes().get("sources")).get(0);
    assertEquals(
        "dropped",
        recordSource.get("contextInclusion"),
        "and on the record — a reloaded run still says the passage never reached the model");
    assertEquals(0, recordSource.get("contextIncludedChars"));
  }

  @Test
  @DisplayName("865 §7.5: an ABSENT inclusion emits NO key — a reader told nothing says nothing")
  void absentInclusionEmitsNoKey() {
    var done =
        new AgentEvent.AgentDone(
            "the answer",
            1,
            1,
            10,
            List.of(new AgentEvent.AgentSource("d1", 0, "/docs/d1.md", "D1", "ex", 1, 5, "")),
            List.of(),
            "CROSS_ENCODER");

    Map<String, Object> payload = io.justsearch.agent.api.AgentEventPayloads.base(done);
    Map<?, ?> wireSource = (Map<?, ?>) ((List<?>) payload.get("sources")).get(0);
    assertTrue(
        !wireSource.containsKey("contextInclusion"),
        "absence is an omitted key, never a fourth string the consumer would have to interpret —"
            + " and it is what keeps every source persisted before 865 readable as silent");
    assertTrue(!wireSource.containsKey("contextIncludedChars"));
  }

  /**
   * Tempdoc 865 §7.7 / §8.3 item 6 — the ZERO-BEHAVIOUR-CHANGE guard on the durability teardown.
   *
   * <p>The thirteen {@code AgentRunShape} kinds that used to reach {@code default} are now written as
   * explicit non-projecting cases. That is a legibility change and nothing else, so it needs a test
   * that would fail if any of them started projecting — the risk of writing a name down is that
   * someone later gives it a body, and "no existing kind's durability changes" is the whole contract
   * this hunk was allowed under. ({@code directive_acknowledged} left the list deliberately in
   * WP6.2: a direction's outcome is now on the record.)
   */
  @Test
  @DisplayName("865 §7.7: every explicitly non-projecting kind still projects nothing")
  void declaredNonProjectingKindsStayNonProjecting() {
    List<String> declaredNonProjecting =
        List.of(
            "session_started",
            "chunk",
            "reasoning_chunk",
            "tool_batch_proposed",
            "tool_call_approved",
            "tool_call_virtual",
            "handoff_proposed",
            "budget_update",
            "budget_gate",
            "context_gate",
            "context_compacted",
            "state_snapshot",
            "intent.resolution");
    for (String kind : declaredNonProjecting) {
      assertTrue(
          AgentInteractionMapper.fromRunEvent(rec(kind, Map.of("text", "x", "phase", "x")), CONV)
              .isEmpty(),
          kind + " is declared non-projecting and must project nothing");
    }
  }
}

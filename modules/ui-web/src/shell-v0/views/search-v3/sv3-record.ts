// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-record — the canonical thread RECORD, projected into this window's turns (tempdoc 822 Phase
 * F6; inventory D1, tempdoc 561 P-A/P-A2).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The window is NOT the authority on what happened in a conversation. `GET /api/thread/{id}` is, and
 * the product already owns both halves of reading it: `views/unifiedThreadClient.ts`
 * (`fetchUnifiedThread` — schema, forward-tolerant per-event parsing, the EMPTY-on-failure contract)
 * and `views/unifiedThreadProjection.ts` (`projectUnifiedThread` — authoritative `occurredAt` order,
 * tool-lifecycle merge by `callId`, run segmentation). Both are consumed by the shipped window as
 * well as by this one, which is what makes them shared authorities rather than one window's
 * extraction; this module imports the pair and adds ONLY the last mile — grouping the ordered items
 * into the turns this window renders.
 *
 * It authors no fetch, no schema and no ordering of its own, and it is pure: same items in, same
 * turns out. The record's interleaving is preserved exactly (561 P-A: chat turns and agent activity
 * come from ONE record and must not be re-sorted into two lists), and it is expressed in the SAME
 * `Sv3RunFeedItem` shapes the live controller feed produces — so a settled run and a running one go
 * through one renderer in `Sv3Main`, not two.
 */
import { projectUnifiedThread, type ThreadEvent, type UnifiedTurnItem } from '../unifiedThreadProjection.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';
import {
  reasoningBlocksFromRecord,
  type ReasoningBlock,
} from '../../controllers/ReasoningController.js';
import type { RetrievalCitation } from '../../components/chat/citationTypes.js';
// The SHARED authorities this projection reads the record's evidence through (tempdoc 847 S1/S3):
// the `claimMatches` envelope reader carrying the producer gate, and the ONE claim→mark resolver the
// live path already uses. Nothing about the evidence is derived here.
import { claimsFromRecord, matchesFromRecord } from '../../components/chat/recordEvidence.js';
import { claimsToCitations } from '../../components/chat/citationResolve.js';
// Tempdoc 859 §5a — the SAME delegate-plane projection the live terminal writes through, so a
// delegate turn the reader watched and one they came back to hold one value from one function.
import { agentAnswerEvidence } from '../../components/chat/agentEvidence.js';
import type {
  AgentSentenceCite,
  AgentSource,
} from '../../../api/generated/shape-handlers/shared.js';
import type { Sv3RunFeedItem } from './sv3-run.js';
import { isSv3StoreMessageId, type Sv3Turn, type Sv3TurnEvidence } from './sv3-sessions.js';

/**
 * The label a non-prose record item carries. Deliberately the same closed vocabulary
 * `sv3-run.ts`'s `RUN_NOTE_LABEL` gives the LIVE feed, so the same happening is not named two
 * different things depending on whether the reader watched it or came back to it.
 */
const RECORD_NOTE_LABEL: Partial<Record<string, string>> = {
  error: 'Error',
  progress: 'Progress',
  handoff: 'Handoff',
};

/**
 * A record tool row → the shared `ToolCall` the ONE tool-call card renders. The attribute mapping is
 * mined from the shipped window's own record path (`views/UnifiedChatView.ts:5322-5340`): the
 * projection has already merged a call's lifecycle events, so `attributes` carry identity
 * (toolName / arguments / risk, from `proposed`) alongside outcome (output / structuredData from
 * `completed`, reason from `rejected`). Risk persists lowercase and the card expects the live
 * uppercase `ToolRisk`.
 */
export function recordToolCall(item: UnifiedTurnItem): ToolCall {
  const a = item.attributes;
  return {
    callId: typeof a.callId === 'string' ? a.callId : item.id,
    toolName: typeof a.toolName === 'string' ? a.toolName : 'tool',
    arguments: typeof a.arguments === 'string' ? a.arguments : '',
    risk: (typeof a.risk === 'string' ? a.risk.toUpperCase() : 'LOW') as ToolCall['risk'],
    status: (typeof a.status === 'string' ? a.status : 'completed') as ToolCall['status'],
    output: typeof a.output === 'string' ? a.output : undefined,
    success: typeof a.success === 'boolean' ? a.success : undefined,
    rejectReason: typeof a.reason === 'string' ? a.reason : undefined,
    structuredData:
      a.structuredData !== null && typeof a.structuredData === 'object'
        ? (a.structuredData as Record<string, unknown>)
        : undefined,
    gateBehavior:
      typeof a.gateBehavior === 'string' ? (a.gateBehavior as ToolCall['gateBehavior']) : undefined,
  };
}

/**
 * Tempdoc 847 §2.4 — a persisted assistant message's evidence, projected into the window's ONE
 * evidence record.
 *
 * `GET /api/thread/{id}` copies `citations` and `claimMatches` verbatim onto the assistant event's
 * attributes (`InteractionThreadController.java:267-280`) and the shared projection passes
 * `attributes` straight through (`unifiedThreadProjection.ts:327`). The window used to discard both
 * and render a restored answer with no sources at all — the evidence was on the wire and thrown
 * away.
 *
 * `null` when the record carries NEITHER attribute: "never told" is not "told zero", and
 * `sv3-honesty.ts` depends on the difference (an absent evidence record gets no frame line, an
 * empty one would claim a verified nothing).
 *
 * The marks come from the SAME `claimsToCitations` the live path resolves through, over claims from
 * the SAME producer-gated envelope reader, so a reloaded answer and the live one cannot mark
 * differently (561 P-A). `retrievalMode` is `''` because the record does not carry it — the panel
 * reads that as "not told", which is what it is.
 *
 * <p>Tempdoc 859 §5a / Reach 1 — the PLANE is discriminated on `attributes.sources`, the key
 * present on exactly one of them, as the legacy reader already does
 * (`UnifiedChatView.ts:3543-3544`). `attributes.citations` carries two incompatible shapes:
 * retrieval SOURCES on the answer plane (`InteractionThreadController.chatTurn`) and per-sentence
 * CITES on the action plane (`AgentInteractionMapper`), whose sources live under a separate
 * `sources` key. Reading `citations` and casting it to `RetrievalCitation[]` — which this function
 * used to do — is a silent reinterpretation, and it yielded a confident wrong number rather than an
 * error: a delegate turn's 12 sentence-cites were reported to the reader as 12 retrieved sources,
 * through the disclosure's accessible name and the panel. Never cast at a read site; key on a
 * discriminator.
 */
function recordEvidenceOf(item: UnifiedTurnItem): Sv3TurnEvidence | null {
  const a = item.attributes;
  // The ACTION plane: an agent run's persisted assistant message. Its `sources` are `AgentSource`s
  // and its `citations` are `AgentSentenceCite`s, projected through the one shared module the live
  // terminal also writes through.
  if (Array.isArray(a.sources)) {
    const scorer = typeof a.citationScorer === 'string' ? a.citationScorer : null;
    const evidence = agentAnswerEvidence(
      a.sources as AgentSource[],
      Array.isArray(a.citations) ? (a.citations as AgentSentenceCite[]) : [],
      scorer,
    );
    return { ...evidence, retrievalMode: '' };
  }
  // The ANSWER plane, unchanged.
  const hasSources = Array.isArray(a.citations);
  const claimMatches = a.claimMatches;
  const hasMatches =
    claimMatches !== null &&
    typeof claimMatches === 'object' &&
    Array.isArray((claimMatches as { matches?: unknown }).matches);
  if (!hasSources && !hasMatches) return null;
  const sources = hasSources ? (a.citations as RetrievalCitation[]) : [];
  return {
    sources,
    matches: matchesFromRecord(claimMatches),
    marks: claimsToCitations(claimsFromRecord(claimMatches), sources),
    retrievalMode: '',
  };
}

/** The turn under construction — the same fields as {@link Sv3Turn}, mutable while it accumulates. */
interface Building {
  id: string;
  question: string;
  askedAt: number;
  answers: string[];
  activity: Sv3RunFeedItem[];
  tools: number;
  errored: boolean;
  /** Tempdoc 848 §2.7 — the turn's persisted thinking, accumulated across all its assistant items. */
  reasoning: ReasoningBlock[];
  /**
   * The evidence of the LAST assistant message in the turn that carried any — last-wins, because a
   * turn's terminal assistant message is the answer, and an earlier interim message's retrieval is
   * not what the reader is looking at.
   *
   * HONEST LIMIT: the turn's `answer` concatenates EVERY assistant message, so on a multi-message
   * turn the rendered text is wider than the evidence beside it. That asymmetry is the live path's
   * too (a run's evidence record is likewise rewritten whole by the last citation event it sees),
   * so restoring matches what was on screen; it is not an invariant that one message's evidence
   * covers the whole answer.
   */
  evidence: Sv3TurnEvidence | null;
  /**
   * The id of the last assistant message in the turn THAT THE CONVERSATION STORE MINTED — last-wins
   * for the same reason {@link evidence} is, and kept even though the ask turn's activity list is
   * not (tempdoc 852 §2.3a): the rendering rule that drops the list says nothing about the identity,
   * and edit-retry, branch and floor-setting all address an assistant message by it.
   *
   * The store test is applied HERE, not only at the accessor, because the two planes of the thread
   * interleave: an agent run's assistant messages are projected read-time from `AgentRunStore` and
   * exist as messages nowhere (`AgentInteractionMapper.java:69`), so a plain last-wins would let a
   * run event's id displace the real message's on a turn that has both.
   */
  assistantId: string | null;
  /** The record opened this turn on a `user` item, rather than on whatever arrived first. */
  openedByUser: boolean;
}

const open = (
  id: string,
  question: string,
  askedAt: number,
  openedByUser: boolean,
): Building => ({
  id,
  question,
  askedAt,
  openedByUser,
  answers: [],
  activity: [],
  tools: 0,
  errored: false,
  reasoning: [],
  evidence: null,
  assistantId: null,
});

/**
 * The record's ordered items → this window's turns.
 *
 * A `user` item OPENS a turn and everything after it belongs to that turn, which is the record's own
 * `user → … → (next user | end)` bracketing (the same segmentation `terminalAssistantIds` uses). Items
 * that arrive BEFORE any user item — a run started from somewhere that recorded no prompt — open a
 * turn with an empty question rather than being dropped: the window renders no ask bubble for it, but
 * a tool call the agent really made is not something to lose because its prompt is missing.
 *
 * The turn's KIND is derived, not declared: a turn that recorded tool calls or notes was an agent
 * run, and a turn that recorded only prose was an ask. Nothing in the record says which tier this
 * window dispatched it from, so deriving it from what happened is the only honest answer available.
 *
 * `evidence` is PROJECTED from the record (tempdoc 847 §2.4, correcting Phase F6): the persisted
 * assistant message carries its sources and its per-sentence matches, and this module hands both to
 * the same shared resolver the live stream's claims go through, so a restored answer stands on the
 * evidence it really stood on. A turn whose record carries neither attribute keeps `null` — an
 * honest "never told", not a claimed zero. {@link ../sv3-sessions.applySv3Record} still prefers what
 * a LIVE turn observed, because that turn watched the stream and the record cannot know more than it.
 */
export function projectSv3RecordTurns(events: readonly ThreadEvent[]): readonly Sv3Turn[] {
  // The shared projector runs HERE, in this window's one registered render site
  // (`governance/run-renderers.v1.json` runProjection) — never at the view. The run is ONE ordered
  // projection (565 §12.3.A), and a view that assembled it itself would be the second structure.
  const items = projectUnifiedThread(events);
  const built: Building[] = [];
  let current: Building | null = null;
  const ensure = (item: UnifiedTurnItem): Building => {
    if (current !== null) return current;
    const created = open(item.id, '', item.ts, false);
    current = created;
    built.push(created);
    return created;
  };

  for (const item of items) {
    if (item.kind === 'user') {
      current = open(item.id, item.content, item.ts, true);
      built.push(current);
      continue;
    }
    const turn = ensure(item);
    // Tempdoc 848 §2.7 — reasoning is read off EVERY item kind, not just the assistant one. A turn
    // can record several assistant items (an iterating shape, a multi-step run), so blocks accumulate
    // in record order rather than the last one winning; and a run that was HALTED or ERRORED carries
    // its trailing thinking on the terminal ERROR event (the agent fold's D-7 rule), which would be
    // silently dropped if only the assistant arm looked. (Contrast `evidence`, deliberately
    // last-wins: the terminal message's retrieval is what the reader is looking at, whereas every
    // step's thinking really happened.)
    turn.reasoning.push(...reasoningBlocksFromRecord(item.attributes.reasoning));
    if (item.kind === 'assistant') {
      turn.answers.push(item.content);
      turn.activity.push({ kind: 'text', id: item.id, text: item.content });
      if (isSv3StoreMessageId(item.id)) turn.assistantId = item.id;
      const evidence = recordEvidenceOf(item);
      if (evidence !== null) turn.evidence = evidence;
      continue;
    }
    if (item.kind === 'tool-activity') {
      const call = recordToolCall(item);
      turn.tools++;
      turn.activity.push({ kind: 'tool', id: call.callId, call });
      continue;
    }
    if (item.kind === 'error') turn.errored = true;
    turn.activity.push({
      kind: 'note',
      id: item.id,
      label: RECORD_NOTE_LABEL[item.kind] ?? 'Step',
      text: item.content,
    });
  }

  return built.map((turn) => {
    // A turn is "an agent run" exactly when the record shows it doing something other than talking.
    const agent = turn.activity.some((entry) => entry.kind !== 'text');
    return {
      id: turn.id,
      // Tempdoc 847 §2.4.3 — the RECORD's own id for this turn, carried as its own field. It is the
      // identity `applySv3Record` reconciles on; the local `id` a live turn was minted with is never
      // overwritten by it, because UI state (expanded sources, the copied-turn flag, the live run's
      // `turnId`) is keyed on that id and a swap silently invalidates it.
      recordId: turn.id,
      // Tempdoc 852 §2.3a — the OTHER half of the exchange's identity. An ask turn drops its
      // activity list below, and used to drop every assistant id with it; the id is what edit-retry,
      // branch and floor-setting address, and none of them can be built on a turn that cannot name
      // the message they act on. Carried WITH its provenance, because the thread interleaves store
      // rows and read-time run projections and only the former is addressable.
      assistantRecordId: turn.assistantId,
      recordOpenedByUser: turn.openedByUser,
      kind: agent ? 'agent' : 'ask',
      question: turn.question,
      answer: turn.answers.join('\n\n'),
      status: turn.errored ? 'failed' : 'complete',
      evidence: turn.evidence,
      detail: '',
      toolCalls: turn.tools,
      // An ASK turn's whole response is its answer text, which the transcript already renders through
      // the shared markdown block — handing it a one-item activity list would mean two ways to draw
      // the same paragraph. Only a turn with real activity carries the interleaved sequence.
      activity: agent ? turn.activity : [],
      askedAt: turn.askedAt,
      // Tempdoc 848 §2.7 — the record now carries the turn's THINKING (persisted on the assistant
      // message by `ConversationEngine`, lifted onto the thread event by `InteractionThreadController`
      // / folded from the run journal by `AgentInteractionMapper`), so a cold-loaded turn shows the
      // blocks the run really produced. Of tempdoc 822 Phase F7's four, that leaves the rewrite note
      // and the receipt absent from the record (847 closed evidence). Those stay seeded EMPTY rather
      // than guessed, and {@link ../sv3-sessions.applySv3Record} keeps whatever the live turn
      // observed — so a cold-loaded turn shows no frame line instead of one built from invented
      // numbers.
      standaloneQuestion: '',
      reasoning: turn.reasoning,
      durationMs: null,
      modelLabel: null,
    } satisfies Sv3Turn;
  });
}

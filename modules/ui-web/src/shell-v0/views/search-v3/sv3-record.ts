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
import {
  directionNote,
  projectUnifiedThread,
  type ThreadEvent,
  type UnifiedTurnItem,
} from '../unifiedThreadProjection.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';
import {
  reasoningBlocksFromRecord,
  type ReasoningBlock,
} from '../../controllers/ReasoningController.js';
import type { RetrievalCitation } from '../../components/chat/citationTypes.js';
// The SHARED authorities this projection reads the record's evidence through (tempdoc 847 S1/S3):
// the `claimMatches` envelope reader carrying the producer gate, and the ONE claim→mark resolver the
// live path already uses. Nothing about the evidence is derived here.
import { claimsFromRecord, matchesFromRecord, coverageFactsFromRecord } from '../../components/chat/recordEvidence.js';
import { claimsToCitations } from '../../components/chat/citationResolve.js';
// Tempdoc 859 §5a — the SAME delegate-plane projection the live terminal writes through, so a
// delegate turn the reader watched and one they came back to hold one value from one function.
import { agentAnswerEvidence, agentDeltaEvidence } from '../../components/chat/agentEvidence.js';
import type {
  AgentSentenceCite,
  AgentSource,
} from '../../../api/generated/shape-handlers/shared.js';
import type { Sv3RunFeedItem } from './sv3-run.js';
import { sv3RecordItemStopsRun, sv3WasHalted } from './sv3-honesty.js';
import {
  isSv3StoreMessageId,
  type Sv3AnswerMessage,
  type Sv3Turn,
  type Sv3TurnEvidence,
} from './sv3-sessions.js';

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
    // Tempdoc 878 §D.4 — the model-visibility label, carried onto the record by
    // `AgentInteractionMapper`'s `tool_exec_completed` case. Absent stays absent: a completion
    // nobody measured must read as silent on reload, not as "the model got everything". Reading it
    // here is what keeps ONE card saying the same thing live and reloaded.
    outputCharsToModel: typeof a.outputCharsToModel === 'number' ? a.outputCharsToModel : undefined,
    truncatedForModel: typeof a.truncatedForModel === 'boolean' ? a.truncatedForModel : undefined,
  };
}

/**
 * Tempdoc 859 §A §1.8 — a record reasoning item's J/K landmark id, derived from the carrier's STABLE
 * identity: `callId` for a tool row, the item's own id for everything else. That is `recordToolCall`'s
 * own idiom (`a.callId ?? item.id`), and reusing it is the point — a tool item's `id` is the id of the
 * LATEST lifecycle event the projection merged, so it changes as `proposed → started → completed`
 * arrive. `concludeRun` refreshes the record at every run terminal, so an id derived from the merged
 * item would move the active landmark out from under J/K exactly when a run ends. `callId` does not
 * move.
 *
 * The `:think:` suffix is load-bearing for the same reason `:q` / `:a` / `:hold` are: the block rides
 * on another event's identity and `jumpTo` resolves by FIRST match, so a duplicate id makes one of the
 * two unreachable.
 */
function recordReasoningItemId(item: UnifiedTurnItem, index: number): string {
  const callId = typeof item.attributes.callId === 'string' ? item.attributes.callId : item.id;
  return `${callId}:think:${index}`;
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
/**
 * Tempdoc 863 §4.A.5 (F1) — the delegate tier's shape id, mirrored from the Java
 * `AgentRunShape.ID`. Also in {@link ../../plugin-api/coreInteractionShapes.CORE_INTERACTION_SHAPES},
 * which is the list the interaction-surface gate keeps in sync with the Java catalog.
 */
const AGENT_RUN_SHAPE_ID = 'core.agent-run';

/** The shape a record item declares it was dispatched by, or `null` when it does not say. */
function declaredShapeOf(item: UnifiedTurnItem): string | null {
  const declared = item.attributes.shapeId;
  return typeof declared === 'string' && declared !== '' ? declared : null;
}

/**
 * Tempdoc 865 §7.1 — the grounding delta a recorded tool row carried, or nothing.
 *
 * <p>An ABSENT key means the call established nothing; the producer omits it rather than writing an
 * empty list, so there is no empty-vs-absent question here.
 */
function groundingDeltaOf(structuredData: Record<string, unknown> | undefined): AgentSource[] {
  const delta = structuredData?.grounding;
  return Array.isArray(delta) ? (delta as AgentSource[]) : [];
}

function recordEvidenceOf(item: UnifiedTurnItem): Sv3TurnEvidence | null {
  const a = item.attributes;
  const retrieval = recordRetrievalMetadata(a);
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
    return { ...evidence, retrievalMode: '', ...retrieval };
  }
  // The ANSWER plane, unchanged.
  const hasSources = Array.isArray(a.citations);
  const claimMatches = a.claimMatches;
  const hasMatches =
    claimMatches !== null &&
    typeof claimMatches === 'object' &&
    Array.isArray((claimMatches as { matches?: unknown }).matches);
  if (!hasSources && !hasMatches && Object.keys(retrieval).length === 0) return null;
  const sources = hasSources ? (a.citations as RetrievalCitation[]) : [];
  return {
    sources,
    matches: matchesFromRecord(claimMatches),
    marks: claimsToCitations(claimsFromRecord(claimMatches), sources),
    ...coverageFactsFromRecord(claimMatches),
    retrievalMode: '',
    ...retrieval,
  };
}

function recordRetrievalMetadata(attributes: Readonly<Record<string, unknown>>):
    Partial<Pick<Sv3TurnEvidence, 'retrievalMode' | 'retrievalModeReason' | 'retrievalModeCause'>> {
  const metadata: Partial<Pick<Sv3TurnEvidence, 'retrievalMode' | 'retrievalModeReason' | 'retrievalModeCause'>> = {};
  for (const key of ['retrievalMode', 'retrievalModeReason', 'retrievalModeCause'] as const) {
    const value = attributes[key];
    if (typeof value === 'string' && value !== '') Object.assign(metadata, { [key]: value });
  }
  return metadata;
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
  errorDetail: string;
  /** Tempdoc 848 §2.7 — the turn's persisted thinking, accumulated across all its assistant items. */
  reasoning: ReasoningBlock[];
  /**
   * Tempdoc 863 §4.A.5 (F1) — the shape the RECORD says dispatched this turn, when it says. Absent
   * (`null`) for a turn whose items all predate 863's per-message `shapeId`, and for a turn made
   * only of run-plane events, which are not store rows and carry no shape.
   */
  declaredShapeId: string | null;
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
   *
   * <p>Tempdoc 865 §7.3 — `groundingIncomplete` is LAST-WINS with the rest of the record, stated
   * rather than inherited. It rides INSIDE this object, so the whole-object replacement below is its
   * accumulation rule, and that is the right one: the flag qualifies THIS message's match list, and
   * an earlier message's completed pass says nothing about the terminal message's. Accumulating it
   * (an OR across messages) would let one interim timeout suppress a later message's real verdict;
   * accumulating it as an AND would do the reverse. (Incremental, per-tool-call minting is 865 PR-1's
   * question, not this one's — nothing here mints evidence before the terminal message.)
   */
  evidence: Sv3TurnEvidence | null;
  /**
   * Tempdoc 865 §7.1 — the per-call grounding deltas this turn's tool rows carried, in record order.
   *
   * <p>Held separately from {@link evidence} and only consulted at the end, because it is the
   * FALLBACK plane, not a second contributor: see the plane-authority rule where it is read.
   */
  groundingDeltas: AgentSource[];
  /**
   * Tempdoc 867 — the raw `AgentSource[]` off the LAST assistant item that carried `attributes.sources`
   * (the action-plane terminal, last-wins like {@link evidence} and for the same reason: the terminal
   * message's sources are what the reader is looking at). Held separately from {@link evidence}
   * because {@link Sv3TurnEvidence.sources} drops `path` (`agentEvidence.toAnswerEvidenceSource`
   * projects onto `AnswerEvidenceSource`, which has no `path` field) — this keeps the raw field the
   * tool cards' evidence-path join needs.
   */
  terminalSources: AgentSource[];
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
  /**
   * Tempdoc 859 §D §2.6 — the terminal DISPOSITION persisted on the assistant message
   * (`AgentInteractionMapper`), last-wins like {@link evidence} and for the same reason.
   *
   * The record leg is not optional. A cut-short badge that shows while the run is on screen and
   * vanishes after a reload is worse than one that was never made: the reader has already learned
   * to trust it, so its absence then reads as "this one was fine".
   */
  disposition: string | null;
  /**
   * Owner decisions 2026-08-26 — the record's own statement that THE READER STOPPED this run, from
   * either fact that can carry it ({@link ../sv3-honesty.sv3RecordItemStopsRun}).
   *
   * <p>Held beside {@link disposition} rather than folded into it, because the two are different
   * claims and only one of them is the run's own account of how it ended. A late cancel leaves a
   * genuinely `COMPLETED` disposition — the run really did finish — and overwriting it here would
   * lose that, while reading the stop off the disposition alone loses every cancel that never
   * reached a terminal at all.
   */
  stopRequested: boolean;
  /** The record opened this turn on a `user` item, rather than on whatever arrived first. */
  openedByUser: boolean;
  /** Every assistant message of the turn, in record order, with its id — the answer as saved. */
  answerMessages: Sv3AnswerMessage[];
  /**
   * The follow-up's rewritten question (the "Interpreted as" note), off the turn's outcome row —
   * `''` when the record carries none (not rewritten, or a record written before it was persisted).
   */
  standaloneQuestion: string;
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
  errorDetail: '',
  reasoning: [],
  declaredShapeId: null,
  evidence: null,
  groundingDeltas: [],
  terminalSources: [],
  disposition: null,
  stopRequested: false,
  assistantId: null,
  answerMessages: [],
  standaloneQuestion: '',
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
      current.declaredShapeId = declaredShapeOf(item);
      built.push(current);
      continue;
    }
    const turn = ensure(item);
    // Tempdoc 863 §4.A.5 (F1) — first declaration wins for the turn. Every store row of one turn is
    // written by the one shape that dispatched it, so the first is the answer; a run-plane item
    // declares nothing and cannot overwrite it.
    turn.declaredShapeId = turn.declaredShapeId ?? declaredShapeOf(item);
    // Owner decisions 2026-08-26 — read off EVERY item kind, before the per-kind arms, because the
    // two facts that can carry it ride on two different kinds and neither arm owns the question.
    // ONCE TRUE, ALWAYS TRUE for the turn: a stop is an act, not a state, so a later event saying
    // nothing about it cannot un-say it.
    const itemStopsRun = sv3RecordItemStopsRun(item.kind, item.attributes);
    if (itemStopsRun) turn.stopRequested = true;
    // Tempdoc 848 §2.7 — reasoning is read off EVERY item kind, not just the assistant one. A turn
    // can record several assistant items (an iterating shape, a multi-step run), so blocks accumulate
    // in record order rather than the last one winning; and a run that was HALTED or ERRORED carries
    // its trailing thinking on the terminal ERROR event (the agent fold's D-7 rule), which would be
    // silently dropped if only the assistant arm looked. (Contrast `evidence`, deliberately
    // last-wins: the terminal message's retrieval is what the reader is looking at, whereas every
    // step's thinking really happened.)
    //
    // Tempdoc 859 §A §1.3 — the SAME blocks also become ordered items, emitted HERE, before the item
    // that carries them: the fold attaches a block to the next event that projects, so the block was
    // produced before that event and belongs above it. Both collections are filled on every item and
    // exactly one of them is kept at the end (below) — an agent turn renders the interleaved items,
    // an ask turn the stack it has always rendered.
    // The "Interpreted as" note, persisted on the turn's OUTCOME row — its answer, or its error when
    // the rewrite was followed by a failure or a Stop — so it is read off either arm, last-wins.
    const standalone = item.attributes.standaloneQuestion;
    if (
      (item.kind === 'assistant' || item.kind === 'error') &&
      typeof standalone === 'string' &&
      standalone.trim() !== ''
    ) {
      turn.standaloneQuestion = standalone;
    }
    const blocks = reasoningBlocksFromRecord(item.attributes.reasoning);
    turn.reasoning.push(...blocks);
    blocks.forEach((block, index) => {
      turn.activity.push({
        kind: 'reasoning',
        id: recordReasoningItemId(item, index),
        text: block.text,
        durationMs: block.durationMs,
        streaming: false,
      });
    });
    if (item.kind === 'assistant') {
      turn.answers.push(item.content);
      turn.answerMessages.push({ id: item.id, content: item.content });
      turn.activity.push({ kind: 'text', id: item.id, text: item.content });
      if (isSv3StoreMessageId(item.id)) turn.assistantId = item.id;
      const evidence = recordEvidenceOf(item);
      if (evidence !== null) turn.evidence = evidence;
      // Tempdoc 867 — the same terminal, kept in its RAW (path-carrying) shape alongside the
      // projected `evidence` above, last-wins for the same reason.
      if (Array.isArray(item.attributes.sources)) {
        turn.terminalSources = item.attributes.sources as AgentSource[];
      }
      // Tempdoc 859 §D §2.6 — the terminal disposition rides the same assistant message the answer
      // does, so a reloaded turn discloses the truncation it disclosed live.
      const disposition = item.attributes.disposition;
      if (typeof disposition === 'string' && disposition !== '') turn.disposition = disposition;
      continue;
    }
    if (item.kind === 'tool-activity') {
      const call = recordToolCall(item);
      turn.tools++;
      turn.activity.push({ kind: 'tool', id: call.callId, call });
      // Tempdoc 865 §7.1 — the grounding delta this call stamped, carried onto the record by
      // `AgentInteractionMapper`'s `tool_exec_completed` case (which copies `structuredData`
      // verbatim, for exactly this reason). Appended in record order and never deduped: the backend
      // already applied the run-wide dedup before splitting the set into deltas, so re-applying it
      // here would be a second mint free to disagree about ORDER.
      turn.groundingDeltas.push(...groundingDeltaOf(call.structuredData));
      continue;
    }
    if (item.kind === 'error') {
      turn.errored = true;
      turn.errorDetail = item.content;
      const evidence = recordEvidenceOf(item);
      if (evidence !== null) turn.evidence = evidence;
      // Owner decision 2026-08-26, applied to the TIMELINE as well as the receipt. The cancel path
      // logs an error entry on its way down, and drawing it as a note labelled "Error" words the
      // reader's own act as a malfunction — the same thing "failed" did in the receipt, one row
      // lower. The act itself is already on the record as the `stop_requested` note
      // (`AgentLoopService.cancelSession`), so this drops a second row, not the only one.
      //
      // NO CARRIER CONSEQUENCE (tempdoc 533 / 859 §A §1.3): only this NOTE is dropped. The item
      // still projects — `errored` and `stopRequested` are read off it above, and its folded
      // thinking is already emitted as `reasoning` activity items before the per-kind arms — and
      // nothing changes about which events project on the Java side, so the fold's cut/flush
      // targeting (whose trailing rule names the run's ERROR event) is untouched.
      if (itemStopsRun) continue;
    }
    if (item.attributes.steer === true) {
      // WP6.2 — a reloaded direction, named as the live feed named it.
      turn.activity.push({
        kind: 'note',
        id: item.id,
        ...directionNote(item.content, item.attributes.directiveOutcome, item.attributes.directiveReason),
      });
      continue;
    }
    turn.activity.push({
      kind: 'note',
      id: item.id,
      label: RECORD_NOTE_LABEL[item.kind] ?? 'Step',
      text: item.content,
    });
  }

  return built.map((turn) => {
    // Tempdoc 863 §4.A.5 (F1) — when the RECORD declares which shape dispatched the turn, that is
    // the answer, and no inference runs. The declaration only exists since 863 made delegate turns
    // store rows; before it, the record genuinely could not say, which is why an inference existed.
    //
    // The inference below is the fallback for the turns that still cannot say: a run-plane-only turn
    // (a pre-stamp delegate run, a background or standalone run joined to the conversation) and any
    // store row written before this field. It is 859 §A §3.5's ALLOW-list, not "not text" — reasoning
    // is an activity item and an ordinary grounded ask turn records plenty of it, so `!== 'text'`
    // would flip every thinking ask turn to `agent`, dropping its activity list, swapping its render
    // arm and hiding its sources (the class 847 §2.4.4 fixed once for `panelSpeaks`).
    //
    // Its known limit is exactly what F1 caught: a delegate run that called NO tool records no
    // activity, so the inference reads it as an ask. That is now unreachable for a stamped run, and
    // survives only for the pre-863 records the inference is kept for.
    const agent =
      turn.declaredShapeId !== null
        ? turn.declaredShapeId === AGENT_RUN_SHAPE_ID
        : turn.activity.some((entry) => entry.kind === 'tool' || entry.kind === 'note');
    // Tempdoc 865 §7.1 / §7.9 A9 — PLANE AUTHORITY. The terminal is authoritative when it arrived;
    // the per-call deltas fill the gap when it did not. A run that ends without a grounded terminal
    // — cancelled, errored (878 §D.1 removed MAX_ITERATIONS from that list: the step ceiling now
    // finalizes through the grounded seam and carries its evidence) — establishes real evidence and
    // then has nothing to report it, which is exactly why the mint moved onto the tool events. The
    // deltas are never
    // ADDED to a terminal that spoke: they are the same set, so accumulating both would double every
    // source and break the positional index the inline marks resolve through.
    //
    // On a STAMPED conversation (863) the terminal arrives on the STORE plane — `recordEvidenceOf`
    // reads it off the persisted assistant row, last-wins — and 863's suppression filters the
    // run-plane `done` that would otherwise say the same thing twice. For an answerless run 863
    // writes no assistant row at all, so there is no terminal on either plane and the deltas are the
    // only record.
    const evidence =
      turn.evidence ??
      (turn.groundingDeltas.length > 0
        ? { ...agentDeltaEvidence(turn.groundingDeltas), retrievalMode: '' }
        : null);
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
      shapeId: turn.declaredShapeId ?? undefined,
      question: turn.question,
      answer: turn.answers.join('\n\n'),
      // The same messages, kept apart: what a follow-up's context is built from (S2.0).
      answerMessages: turn.answerMessages,
      // Live audit 2026-08-25 (D3) — ONE vocabulary for one run. A cancelled run logs an error entry
      // on its way down, so deriving the status from `errored` alone made the record call it `failed`
      // while the live tail called it `stopped by you` — the same run telling the reader two stories,
      // and the record's one describing their own decision as a malfunction. The precedence is
      // `sv3RunOutcome`'s, restated on this plane rather than re-decided: HALT WINS OVER AN ERROR
      // ENTRY. `applySv3Record` already carried a live `halted` across a refresh (`sv3-sessions.ts`
      // `status: prior.status === 'halted' ? …`), which is exactly why the divergence only showed on
      // a COLD load, where there is no prior turn to carry.
      //
      // Owner decisions 2026-08-26 extend that precedence by one case each, and both are the SAME
      // rule read from a different fact. D3 keyed on `disposition`, which a cancelled run does not
      // always have: a run stopped before it produced an answer writes no assistant row at all, so
      // the derivation fell through to `errored` and the record called the reader's Stop a failure
      // — the exact divergence D3 fixed, surviving in the case D3's fact could not reach. And a stop
      // that RACES the terminal leaves a truthful `COMPLETED` disposition, so the halt has to win
      // over a completion as well as over an error. `stopRequested` carries both, and it is checked
      // FIRST for the same reason halt led the ternary before: the reader's own act outranks every
      // account the run gives of itself.
      status: turn.stopRequested || sv3WasHalted(turn.disposition)
        ? 'halted'
        : turn.errored
          ? 'failed'
          : 'complete',
      evidence,
      detail: turn.errorDetail,
      toolCalls: turn.tools,
      // An ASK turn's whole response is its answer text, which the transcript already renders through
      // the shared markdown block — handing it a one-item activity list would mean two ways to draw
      // the same paragraph. Only a turn with real activity carries the interleaved sequence.
      activity: agent ? turn.activity : [],
      askedAt: turn.askedAt,
      // Tempdoc 848 §2.7 — the record now carries the turn's THINKING (persisted on the assistant
      // message by `ConversationEngine`, lifted onto the thread event by `InteractionThreadController`
      // / folded from the run journal by `AgentInteractionMapper`), so a cold-loaded turn shows the
      // blocks the run really produced. The rewrite note is on the record too now (the engine
      // persists the standalone question on the turn's outcome row); an older record without it
      // reads as '' — no note — and {@link ../sv3-sessions.applySv3Record} still keeps whatever the
      // live turn observed. The receipt stays absent from the record and seeded empty.
      standaloneQuestion: turn.standaloneQuestion,
      // Tempdoc 859 §A §1.9 — ROUTED, not both. An agent turn's thinking is in `activity`, in the
      // position it was produced; rendering the stack as well would put every block on screen twice
      // and put the wall back above the feed that replaced it. An ask turn has one thought and one
      // answer — no chronology to lose — so it keeps the stack it has always had.
      reasoning: agent ? [] : turn.reasoning,
      durationMs: null,
      modelLabel: null,
      // Tempdoc 859 §D §2.6 — carried, not seeded empty. Unlike the rewrite note and the receipt
      // (which the record genuinely does not hold), this one IS persisted, so leaving it null would
      // be discarding a fact the record has.
      disposition: turn.disposition,
      // Tempdoc 867 — the SAME plane-authority rule `evidence` above follows (865 §7.1): the
      // per-call deltas fill the gap when the terminal never spoke, and are never ADDED to a
      // terminal that did (they are the same set — a Set dedups the overlap rather than double it).
      evidencePaths: Array.from(
        new Set([
          ...turn.groundingDeltas.map((s) => s.path),
          ...turn.terminalSources.map((s) => s.path),
        ]),
      ),
    } satisfies Sv3Turn;
  });
}

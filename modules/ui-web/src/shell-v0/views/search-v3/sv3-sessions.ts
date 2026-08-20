// SPDX-License-Identifier: Apache-2.0
/**
 * The Search v3 window's session list (tempdoc 822 Phase A2; sessions became CONVERSATIONS in F1).
 *
 * A session holds an ordered list of turns — the product shape the 822 course correction
 * adopted: the composer talks to the local model, and a session is that conversation. The search
 * axis A2 built this module for is still wired, but it no longer writes here.
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * **THE PERSISTENCE BOUNDARY, AS OF PHASE F6 — partially resolved.** Phase A2 deferred the whole
 * authority question to Phase D because no canonical "search session" store existed. F6 answers the
 * half that did have an authority: a session IS a conversation, and the product's app-wide
 * conversation authority is `state/conversationListStore.ts`. So:
 *
 *  - **MOVED OUT (the store's now, not ours).** Conversation IDENTITY (`createConversationId`, minted
 *    by the store and handed in — this module mints nothing), EXISTENCE (the store's list is what a
 *    reload projects sessions back from, via {@link mergeStoreConversations}), and TITLE (a rename
 *    writes through to `setConversationTitle`; the store persists it). The TRANSCRIPT moved further
 *    still: turns are a projection of the canonical `/api/thread/{id}` record ({@link applySv3Record},
 *    fed by the shared `fetchUnifiedThread` + `projectUnifiedThread`), so this module holds the
 *    in-flight turn and nothing else is authored here.
 *  - **STAYED WINDOW-LOCAL (deliberately, and still Phase-D's question).** `pinned` and the unread
 *    bit (`completedAt`/`lastVisitedAt`) are reader PREFERENCES about a row in THIS window, and the
 *    conversation store has no field for either. Persisting them here would mint the second authority
 *    A2 refused; they should move to whatever preference store Phase D settles on, alongside the
 *    sidebar's width/collapsed pair. The SHELF projection stays here for the same reason — it is a
 *    presentation of the two window-local prefs plus the live run.
 *
 * Everything here is still PURE: ids, records, and store rows arrive as arguments, so the semantics
 * can be tested without a DOM and this module still performs no IO of its own.
 *
 * Two laws from the design spec shape the API:
 *
 *  - **Activity never reorders the list** (adopted as charter law): a session is inserted
 *    once, at the top of its group, and never moves again — not when it is re-run, not when it
 *    finishes. A row cannot slide out from under the pointer while it is being read.
 *  - **The 3-colour status budget**: colour means act-now / in-motion / broken. A settled
 *    session spends none of it and carries a coarse relative timestamp instead.
 */
import type { Sv3RowStatus } from './fixtures.js';
import type { AnswerEvidenceSource, CitationMatch } from '../../components/chat/citationTypes.js';
import type { Citation } from '../../components/chat/MarkdownBlock.js';
// Type-only, and deliberately the LIVE feed's own item type: a turn's record-projected activity and
// a running turn's live feed are the same three shapes, so the content surface has ONE renderer for
// both and a settled run cannot be drawn by a second one (tempdoc 822 Phase F6 / inventory D1).
import type { Sv3RunFeedItem } from './sv3-run.js';
import type { ReasoningBlock } from '../../controllers/ReasoningController.js';

/**
 * What one answer stood on, as ONE record (tempdoc 822 Phase F4). Registered in
 * `governance/execution-surfaces.v1.json` (`sv3-turn-evidence`) as an opaque carrier: the turn keeps
 * the backend's evidence verbatim and projects no field of it. Every number the window says about an
 * answer's sources is read off this record — there is no second count to disagree with it.
 */
export interface Sv3TurnEvidence {
  /**
   * The source set the answer stood on — `rag.citations` on the ask tier, the `done` event's
   * `sources` on the delegate tier (tempdoc 859 §5b). The SUPERTYPE, because the two planes report
   * different facts and casting one to the other is what made this window state a fabricated count
   * for a delegate turn.
   */
  readonly sources: readonly AnswerEvidenceSource[];
  /** The per-sentence grounding matches (`rag.citation_matches`), for the shared citations panel. */
  readonly matches: readonly CitationMatch[];
  /** The inline `[n]` marks, resolved by the SHARED `claimsToCitations` — never authored here. */
  readonly marks: readonly Citation[];
  /** The retrieval mode the panel needs to know whether it may grade the sources at all. */
  readonly retrievalMode: string;
}

/**
 * How a turn's response ended, or that it has not ended yet. Four TERMINALS, all distinct, because
 * they are four different things to have happened: `halted` is the reader's own Stop and must never
 * be worded as a failure, and `refused` is the session lock declining the send — neither of them is
 * an answer that went wrong (tempdoc 822 Phase F1; the terminals `sv3-ask.ts` reports).
 */
export type Sv3TurnStatus = 'streaming' | 'complete' | 'halted' | 'refused' | 'failed';

/**
 * Which tier produced the turn (tempdoc 822 Phase F2). An `ask` turn is a grounded answer streamed
 * from `sv3-ask.ts`; an `agent` turn is a delegated RUN hosted by the shared `AgentSessionController`,
 * which renders as a live feed while it runs and as a receipt once it ends. One turn type with a
 * discriminator, not two lists: a conversation interleaves both and the transcript must keep their
 * order.
 */
export type Sv3TurnKind = 'ask' | 'agent';

/**
 * One block of the model's thinking, as the SHARED `ReasoningController` finalized it (tempdoc 822
 * Phase F7; inventory C9). Tempdoc 848 §2.5 — an ALIAS of the controller's own `ReasoningBlock`, not
 * a second declaration: this module's comment already admitted its shape was a copy, and the copy had
 * already drifted (it declared both fields `readonly` where the controller's did not — the alias
 * closes that by making the controller's fields `readonly` too, so the one shape is immutable at both
 * ends). A type-only import keeps the original decoupling intent (no runtime edge to a controller
 * module). Now that the same shape is also what the RECORD carries, one drifting copy would be one
 * too many.
 */
export type Sv3TurnReasoning = ReasoningBlock;

/** One exchange: what was asked, and what came back. */
export interface Sv3Turn {
  /**
   * The turn's STABLE handle, minted once when the turn is opened and never rewritten. Every piece
   * of UI state about a turn is keyed on it — `Sv3Main`'s `expandedSources` and `copiedTurnId`, the
   * live run's `turnId`, the transcript's aria ids — so a merge that swapped it would leave that
   * state pointing at a turn that no longer exists, and the write would silently no-op rather than
   * fail (tempdoc 847 §1.6b).
   */
  readonly id: string;
  /**
   * Which turn of the canonical record this one IS, once the record has spoken for it (tempdoc 847
   * §2.4.3). `null` until then — a turn dispatched locally exists before the record knows of it.
   * This is the key {@link applySv3Record} reconciles on: matching by array position alone
   * mis-attributed one turn's evidence, status and duration to another on any length skew.
   */
  readonly recordId: string | null;
  /**
   * The `ConversationStore` id of the turn's TERMINAL assistant message (tempdoc 852 S1), or `null`
   * while the record has not spoken for this turn. {@link recordId} names the turn's USER message —
   * the same id `?fromMsgId=`, `{floorMessageId}` and `…/messages/{id}/exclude` key on — and this
   * names the other half of the exchange, which those endpoints address just as often.
   *
   * LAST-WINS on a multi-message turn, the same rule {@link evidence} follows and for the same
   * reason: the terminal assistant message is the answer, and an interim one is not what a floor or
   * an exclusion is being set against.
   */
  readonly assistantRecordId: string | null;
  /**
   * The record opened this turn on a USER message (tempdoc 852 S1). `false` until the record has
   * spoken for the turn, and `false` for a turn the record opened on something else — an agent run
   * whose prompt was never recorded starts on a tool call or a search event, and that item's id is
   * {@link recordId} without being anybody's message. It is the provenance half of
   * {@link sv3TurnMessageIds}: the id alone cannot say which plane of the thread it came from.
   */
  readonly recordOpenedByUser: boolean;
  readonly kind: Sv3TurnKind;
  readonly question: string;
  /** Accumulated answer text. Whatever streamed before a halt is KEPT — it was really received. */
  readonly answer: string;
  readonly status: Sv3TurnStatus;
  /**
   * The evidence the backend minted for this answer — `null` until it reports any, which is not the
   * same as an empty set. A turn that was never told cannot claim a number.
   */
  readonly evidence: Sv3TurnEvidence | null;
  /** The failure's own words, from the stream. Empty for every other status. */
  readonly detail: string;
  /**
   * An `agent` turn's receipt count — how many tool calls the run made (tempdoc 822 Phase F2). It is
   * written ONCE, at the run's terminal, from the same feed projection the cards were rendered from
   * (`sv3-run.ts` {@link Sv3RunFeed.toolCallCount}), so the receipt cannot describe a different set
   * than the feed it summarises. Always 0 on an `ask` turn, which makes no tool calls.
   */
  readonly toolCalls: number;
  /**
   * What HAPPENED in this turn, in the canonical record's own order (tempdoc 822 Phase F6, inventory
   * D1 / 561 P-A): the agent's prose, its tool calls and its notes INTERLEAVED, never re-sorted into
   * two lists. Empty on a turn the record has not spoken for yet — an in-flight run renders from the
   * live controller feed instead, and yields to this the moment the record catches up. Empty forever
   * on an `ask` turn, whose whole response is {@link answer}.
   */
  readonly activity: readonly Sv3RunFeedItem[];
  readonly askedAt: number;
  /**
   * The decontextualized standalone question retrieval actually ran on (tempdoc 603 C2; inventory
   * C8), or `''` when the backend did not rewrite this one. Pinned onto the turn rather than held for
   * the live stream, exactly as the shipped window pins it (`views/UnifiedChatView.ts:5849`): a
   * transparency note that vanished when the stream ended would only ever be seen by someone
   * watching.
   */
  readonly standaloneQuestion: string;
  /**
   * The model's thinking for this turn, finalized (inventory C9). Empty when the shape's
   * `reasoning_chunk` channel said nothing — which is most turns, and is not a state to announce.
   */
  readonly reasoning: readonly Sv3TurnReasoning[];
  /**
   * How long the answer took, MEASURED by this window from the turn's own `askedAt` to its terminal
   * (inventory C1). `null` until it settles. Measured rather than reported: `core.rag-ask`'s `done`
   * payload carries `promptTokens`/`contextBreakdown` and no duration, and the shipped window derives
   * it the same way (from the activity indicator's `startedAtMs`, `views/UnifiedChatView.ts:5839`).
   */
  readonly durationMs: number | null;
  /**
   * Which model produced the answer, stamped AT THE TERMINAL. The shipped window reads the label at
   * render time instead (`views/UnifiedChatView.ts:5434`), which re-labels an old answer with
   * whatever is loaded now; recording it once is the same fact without the drift.
   */
  readonly modelLabel: string | null;
}

/**
 * What `GET /api/chat/conversations/{id}/history` knows about a conversation that
 * `GET /api/thread/{id}` does not (tempdoc 852 §2.3c). The shipped window reads BOTH records at
 * adjacent lines (`views/UnifiedChatView.ts:2048-2049`); this window read only the thread, so the
 * branch lineage, the effective-context floor and the two exclusion ledgers were simply not on the
 * wire it listened to. Every field here is one the thread endpoint structurally cannot carry —
 * `InteractionThreadController` answers `{conversationId, events[], lifecycles[]}` and nothing else.
 *
 * A STRUCTURAL type, the same construction {@link Sv3StoreConversation} uses for the conversation
 * store's rows: this module stays pure and free of the store, and a test can hand it a history
 * without standing one up. The fields mirror `state/conversationListStore.ts`'s
 * `ResumedConversation` (`:469-499`), which is the FE authority for the shape.
 *
 * `messages` is DELIBERATELY absent. `/history` carries a transcript too, and taking it would give
 * this window a second answer to "what happened in this conversation" — the canonical record
 * ({@link applySv3Record}) is the one authority for that, and a companion load is not a licence to
 * fork it.
 */
export interface Sv3SessionHistory {
  /** This conversation is a BRANCH of that one (slice 513), or undefined when it is a root. */
  readonly parentSessionId?: string;
  /** The last message inherited from the parent — everything up to and including it was prepended. */
  readonly branchPointMessageId?: string;
  /** The parent's opening question, so a branch banner can name it without a second roundtrip. */
  readonly parentFirstUserMessage?: string;
  /** The effective-context floor's message id (tempdoc 610 Phase C); undefined = no floor. */
  readonly contextFloor?: string;
  /** The compaction summary attached to that floor (Phase D); undefined for a plain rewind. */
  readonly contextFloorSummary?: string;
  /** Message ids the reader dropped from the next prompt (§E.3) — still displayed, not sent. */
  readonly excludedMessageIds?: readonly string[];
  /** Retrieved-source ids the reader hid from retrieval (§J.3); the Worker drops those chunks. */
  readonly excludedSourceIds?: readonly string[];
  /**
   * The conversation store answered 423 — encrypted and LOCKED (tempdoc 629). The window already
   * derives its own lock state from observed status; this is the load's own report of the same
   * condition, which is why it is carried rather than folded into that flag here.
   */
  readonly locked?: boolean;
}

/**
 * How full the model's context window was on this conversation's LAST completed turn (tempdoc 610
 * §E.4 / §I.2, ported in 852 S2). Reported by the dispatch's own `done` payload and kept per
 * CONVERSATION, because that is what it describes: a meter carried on the window would follow the
 * reader into a conversation whose prompt it never measured.
 *
 * `breakdown` is the per-phase attribution and is an ESTIMATE by the backend's own account
 * (`promptTokens` is the authoritative total); `null` when the turn reported none.
 */
export interface Sv3ContextUsage {
  /** The real occupancy of the last prompt, in tokens. */
  readonly promptTokens: number;
  /** The estimated split, or null when the terminal carried none. */
  readonly breakdown: {
    readonly system: number;
    readonly conversation: number;
    readonly retrieved: number;
  } | null;
}

/** One conversation in this window: what it was opened with, and every turn it has taken. */
export interface Sv3Session {
  readonly id: string;
  /**
   * The reader parked this conversation on the Pinned shelf (tempdoc 822 Phase F3). A flag on the
   * session rather than a separate list, because a pin is a PROPERTY of the conversation: a second
   * list would be a second ordering to keep in step with this one.
   */
  readonly pinned: boolean;
  /**
   * When something in this session last reached a terminal, or null if nothing has. Half of the
   * unread bit (820 W2's `completedAt` vs `lastVisitedAt`): a run or an answer that finished is
   * something to read, and the session says so until the reader has been back.
   */
  readonly completedAt: number | null;
  /**
   * When the reader last had this conversation on screen. `0` means never — an adopted run
   * ({@link adoptRunSession}) is on screen nowhere until it is claimed.
   */
  readonly lastVisitedAt: number;
  /**
   * The OPENING question, fixed at creation. Phase F1 replaced A2's "latest query" title: a
   * conversation's turns are a thread, so a row label that re-wrote itself on every turn would
   * change identity under the reader — the same objection as the never-reorder law, applied
   * to the label instead of the position. No auto-titling: the opening question IS the title (the
   * row's single-line ellipsis handles length).
   */
  readonly title: string;
  /**
   * The reader named this conversation themselves (tempdoc 822 Phase F7; inventory A11). It is the
   * PRECEDENCE flag auto-titling has to lose to: a model-generated name is a convenience, and one
   * that could overwrite a name the reader chose would be the window disagreeing with them about
   * what their own conversation is called.
   */
  readonly renamed: boolean;
  /** Oldest FIRST — the transcript's render order. */
  readonly turns: readonly Sv3Turn[];
  /**
   * What the `/history` companion load reported for this conversation (tempdoc 852 S1), or `null`
   * while it has not been asked. `null` is "not told", not "no floor and no branch": a conversation
   * whose history has never been loaded knows nothing about its own lineage, and a reader of this
   * field must be able to tell that from a conversation that really is a root with no floor.
   */
  readonly history: Sv3SessionHistory | null;
  /**
   * What this conversation's last completed turn reported about its own prompt occupancy, or `null`
   * while it has reported none (tempdoc 852 S2). `null` is "not measured", not "empty": the meter is
   * omitted rather than shown at 0%, which is the same rule {@link Sv3SessionHistory} follows.
   */
  readonly contextUsage: Sv3ContextUsage | null;
  readonly createdAt: number;
  /** When the session last submitted; the resting row's timestamp. */
  readonly updatedAt: number;
}

/** Addresses one turn inside one session, so a stream can only ever write to the turn it opened. */
export interface Sv3TurnRef {
  readonly sessionId: string;
  readonly turnId: string;
}

export interface Sv3SessionList {
  /** Newest FIRST, and fixed: creation order is render order forever. */
  readonly sessions: readonly Sv3Session[];
  /** The session a submit belongs to; null means the next submit opens a new one. */
  readonly activeId: string | null;
}

export const SV3_SESSIONS_EMPTY: Sv3SessionList = { sessions: [], activeId: null };

/** A conversation the store lists but this window has no title for — never a nameless row. */
export const SV3_UNTITLED_CONVERSATION = 'Untitled conversation';

export const sessionById = (list: Sv3SessionList, id: string): Sv3Session | null =>
  list.sessions.find((session) => session.id === id) ?? null;

/** Deterministic and position-based, so the caller can address a turn without the list handing back a tuple. */
const turnIdFor = (sessionId: string, index: number): string => `${sessionId}#t${index + 1}`;

const openTurn = (
  sessionId: string,
  index: number,
  question: string,
  now: number,
  kind: Sv3TurnKind,
): Sv3Turn => ({
  id: turnIdFor(sessionId, index),
  recordId: null,
  assistantRecordId: null,
  recordOpenedByUser: false,
  kind,
  question,
  answer: '',
  status: 'streaming',
  evidence: null,
  detail: '',
  toolCalls: 0,
  activity: [],
  askedAt: now,
  standaloneQuestion: '',
  reasoning: [],
  durationMs: null,
  modelLabel: null,
});

/**
 * The ONE construction of a session, shared by a submit and by an adopted run, so a session can
 * never exist in two shapes. `visitedAt` is the caller's: a submit is made by a reader who is
 * looking at the window, an adopted run is not.
 *
 * `id` is HANDED IN (tempdoc 822 Phase F6): the app-wide conversation store mints conversation ids
 * (`state/conversationListStore.ts:195` `createConversationId`) and a session is a conversation, so
 * a local counter here would be a second identity authority — the fork the header refuses.
 */
function openSession(
  id: string,
  title: string,
  now: number,
  kind: Sv3TurnKind,
  visitedAt: number,
): Sv3Session {
  return {
    id,
    title,
    renamed: false,
    turns: [openTurn(id, 0, title, now, kind)],
    history: null,
    contextUsage: null,
    createdAt: now,
    updatedAt: now,
    pinned: false,
    completedAt: null,
    lastVisitedAt: visitedAt,
  };
}

/**
 * A submitted question: it CREATES a session when none is active, and APPENDS a turn to the active
 * one otherwise. The session keeps its position — a new turn is not a reason to move a row.
 *
 * Phase F1 replaced A2's update-in-place semantics (a re-query overwrote the session's single
 * query). A conversation accumulates: overwriting would destroy the transcript the window now
 * renders. The SEARCH axis, which A2's semantics were written for, no longer routes through the
 * session list at all — it is a palette-only dev affordance (`SearchV3View.runSearch`).
 */
export function submitInSession(
  list: Sv3SessionList,
  question: string,
  now: number,
  kind: Sv3TurnKind,
  /** The store-minted conversation id, used only when this submit OPENS a conversation. */
  newId: string,
): Sv3SessionList {
  const text = question.trim();
  if (text === '') return list;
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  if (active === null) {
    const created = openSession(newId, text, now, kind, now);
    return {
      sessions: [created, ...list.sessions],
      activeId: created.id,
    };
  }
  return {
    ...list,
    sessions: list.sessions.map((session) =>
      session.id === active.id
        ? {
            ...session,
            turns: [...session.turns, openTurn(session.id, session.turns.length, text, now, kind)],
            updatedAt: now,
            // Asking in a conversation IS visiting it, so a follow-up cannot leave the session
            // holding an unread bit against a reader who is sitting in it.
            lastVisitedAt: now,
          }
        : session,
    ),
  };
}

/** The turn a just-returned {@link submitInSession} opened, or null if nothing is active. */
export function latestTurnRef(list: Sv3SessionList): Sv3TurnRef | null {
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  const turn = active?.turns.at(-1);
  if (active === undefined || active === null || turn === undefined) return null;
  return { sessionId: active.id, turnId: turn.id };
}

/** The active session's transcript. An empty list is the window's "nothing asked here yet". */
export function activeTurns(list: Sv3SessionList): readonly Sv3Turn[] {
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  return active?.turns ?? [];
}

/**
 * The one way a turn changes. Addressed by REF rather than by "the active session", so a stream that
 * started in one session cannot write into another one the reader has since claimed.
 */
function mapTurn(
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  change: (turn: Sv3Turn) => Sv3Turn,
): Sv3SessionList {
  const session = sessionById(list, ref.sessionId);
  if (session === null || !session.turns.some((t) => t.id === ref.turnId)) return list;
  return {
    ...list,
    sessions: list.sessions.map((s) =>
      s.id === ref.sessionId
        ? { ...s, turns: s.turns.map((t) => (t.id === ref.turnId ? change(t) : t)) }
        : s,
    ),
  };
}

/** Streaming text lands here, delta by delta; a settled turn ignores late deltas rather than reopening. */
export const appendTurnDelta = (list: Sv3SessionList, ref: Sv3TurnRef, delta: string): Sv3SessionList =>
  mapTurn(list, ref, (turn) =>
    turn.status === 'streaming' ? { ...turn, answer: turn.answer + delta } : turn,
  );

export const setTurnEvidence = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  evidence: Sv3TurnEvidence,
): Sv3SessionList => mapTurn(list, ref, (turn) => ({ ...turn, evidence }));

/**
 * The decontextualized question retrieval really ran on (tempdoc 603 C2; inventory C8). An empty
 * standalone is DROPPED rather than stored: "interpreted as: (nothing)" would be a transparency note
 * that hides what it claims to show.
 */
export const setTurnRewrite = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  standaloneQuestion: string,
): Sv3SessionList =>
  standaloneQuestion.trim() === ''
    ? list
    : mapTurn(list, ref, (turn) => ({ ...turn, standaloneQuestion }));

/** The finalized thinking blocks, written as ONE snapshot at the terminal (inventory C9). */
export const setTurnReasoning = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  reasoning: readonly Sv3TurnReasoning[],
): Sv3SessionList =>
  reasoning.length === 0 ? list : mapTurn(list, ref, (turn) => ({ ...turn, reasoning }));

/**
 * How many sources the answer stood on, DERIVED from the one evidence record — `null` when the
 * backend never reported any, which is not "0 sources". Derived rather than stored so a count and
 * the panel beside it cannot describe different sets (tempdoc 822 Phase F4).
 */
export const sv3TurnSourceCount = (turn: Sv3Turn): number | null =>
  turn.evidence === null ? null : turn.evidence.sources.length;

/**
 * A terminal is BOTH a turn write and a session write, done in one pass so they cannot arrive
 * separately: the turn takes its outcome, and the session records that something finished in it.
 *
 * The unread bit is decided HERE, by whether the session was the claimed one at the moment it
 * finished — a reader who was looking at the conversation has already seen the answer, so its
 * `lastVisitedAt` moves with the completion and the bit never rises (820 W2's unread-completion
 * rule). A turn that already settled is left alone: one terminal per turn.
 *
 * A HALT records no completion at all: the reader stopped it themselves (or left the conversation,
 * which stops it for them), so nothing arrived in their absence and a row that woke up over it would
 * be calling their own decision news.
 */
function settleWith(
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  now: number,
  status: Exclude<Sv3TurnStatus, 'streaming'>,
  change: (turn: Sv3Turn) => Sv3Turn,
): Sv3SessionList {
  const session = sessionById(list, ref.sessionId);
  const turn = session?.turns.find((t) => t.id === ref.turnId);
  if (session === null || turn === undefined || turn.status !== 'streaming') return list;
  const claimed = session.id === list.activeId;
  const completed = status !== 'halted';
  return {
    ...list,
    sessions: list.sessions.map((s) =>
      s.id === ref.sessionId
        ? {
            ...s,
            turns: s.turns.map((t) => (t.id === ref.turnId ? change(t) : t)),
            completedAt: completed ? now : s.completedAt,
            lastVisitedAt: completed && claimed ? now : s.lastVisitedAt,
          }
        : s,
    ),
  };
}

/**
 * The turn reaches its ONE terminal. A turn that already settled stays settled: the stream reports
 * exactly one terminal, and a second write could only be a bug re-wording the first.
 *
 * The RECEIPT is stamped here and only here (tempdoc 822 Phase F7; inventory C1): the duration is
 * measured from the turn's own `askedAt` to this terminal, and `modelLabel` is whatever produced the
 * answer at the moment it ended. Both are inputs to the frame line and neither is re-derived at
 * render — a receipt recomputed while the transcript is read would drift with the clock and with
 * whichever model happens to be loaded later.
 */
export const settleTurn = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  status: Exclude<Sv3TurnStatus, 'streaming'>,
  now: number,
  detail = '',
  modelLabel: string | null = null,
): Sv3SessionList =>
  settleWith(list, ref, now, status, (turn) => ({
    ...turn,
    status,
    detail,
    durationMs: Math.max(0, now - turn.askedAt),
    modelLabel,
  }));

/**
 * The agent run's terminal (tempdoc 822 Phase F2): the turn settles AND becomes its own receipt in
 * ONE write, because the count and the outcome describe the same ended run and must never be able to
 * arrive separately. `toolCalls` is the feed projection's own count — the same list the cards were
 * rendered from — so the caller has no second counter it could pass instead.
 */
export const settleAgentTurn = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  status: Exclude<Sv3TurnStatus, 'streaming'>,
  toolCalls: number,
  now: number,
  detail = '',
): Sv3SessionList =>
  settleWith(list, ref, now, status, (turn) => ({ ...turn, status, detail, toolCalls }));

/**
 * A run this window did NOT dispatch, given a session so it can be seen (tempdoc 822 Phase F3).
 *
 * The named F2 finding: window-local in-memory sessions orphan a live run on reload — a fresh window
 * showed nothing while the run went on holding server-side. The shared controller, not this window's
 * memory, is the authority on what is running, so a window that finds a live run with no session
 * SYNTHESISES one rather than rendering an empty sidebar next to a working agent.
 *
 * It does NOT claim the session: an adopted run is news, not a navigation, and yanking the reader
 * out of the conversation they are in would be the window deciding where they should be looking.
 * `lastVisitedAt: 0` follows from that — nobody has seen it, so its completion raises the unread bit.
 */
export interface Sv3Adoption {
  readonly list: Sv3SessionList;
  readonly ref: Sv3TurnRef;
}

export function adoptRunSession(
  list: Sv3SessionList,
  title: string,
  now: number,
  /**
   * The conversation the run belongs to. When the run already names one this window is listing —
   * the store put it there, or the reader was in it — the run is ADOPTED INTO that conversation
   * rather than beside it: two rows for one conversation would be the identity fork Phase F6 closed.
   */
  conversationId: string,
): Sv3Adoption {
  const existing = sessionById(list, conversationId);
  if (existing !== null) {
    const turnId = turnIdFor(existing.id, existing.turns.length);
    return {
      list: {
        ...list,
        sessions: list.sessions.map((s) =>
          s.id === existing.id
            ? {
                ...s,
                turns: [...s.turns, openTurn(s.id, s.turns.length, title.trim(), now, 'agent')],
                updatedAt: now,
              }
            : s,
        ),
      },
      ref: { sessionId: existing.id, turnId },
    };
  }
  const session = openSession(conversationId, title.trim(), now, 'agent', 0);
  return {
    list: { ...list, sessions: [session, ...list.sessions] },
    ref: { sessionId: session.id, turnId: turnIdFor(session.id, 0) },
  };
}

/* ── The app-wide conversation store, projected in (tempdoc 822 Phase F6 / inventory A1) ─────── */

/**
 * One row of `state/conversationListStore.ts`'s list, as this module consumes it. A structural type
 * rather than the store's `Conversation` class-of-record, so this module stays pure and a test can
 * hand it rows without standing the store up — the same construction `sv3-run.ts`'s `Sv3RunSource`
 * uses for the run controller.
 */
export interface Sv3StoreConversation {
  readonly id: string;
  readonly title: string | null;
  /**
   * Who named it (tempdoc 838), or null when nobody has. Optional because a row this window
   * SYNTHESISES for a conversation it has just opened has no provenance to report yet — only a row
   * that came off the wire does.
   */
  readonly titleSource?: 'user' | 'auto' | null;
  /** Lock-safe (tempdoc 562): the store returns "" while the conversation store is encrypted. */
  readonly firstUserMessage: string;
  readonly createdAt: number;
  readonly lastActiveAt: number;
}

/**
 * The store's conversations, folded into this window's list — the half of A1 that makes a session
 * SURVIVE THE PROCESS. On a cold mount the local list is empty and this IS the session list; on a
 * warm one it adds whatever the product gained elsewhere and re-titles what was renamed elsewhere.
 *
 * Two rules, both the never-reorder law applied to a merge:
 *
 *  - **A known conversation is never re-created and never moved.** It is matched by id, and only its
 *    TITLE is taken from the store (the authority a rename writes through to). Its turns, pin and
 *    unread bit — the parts the store has no field for — are left exactly as they were.
 *  - **A new conversation is APPENDED, not prepended.** A conversation this window did not open is
 *    not its news; putting it at the top would move every row the reader was looking at. On a cold
 *    mount that appends into an empty list, so the store's own newest-first order is the render order.
 *
 * A store row arrives with no turns: the TRANSCRIPT is the canonical record's ({@link applySv3Record}),
 * fetched when the conversation is claimed, not carried on the list row.
 */
export function mergeStoreConversations(
  list: Sv3SessionList,
  conversations: readonly Sv3StoreConversation[],
): Sv3SessionList {
  if (conversations.length === 0) return list;
  const byId = new Map(conversations.map((c) => [c.id, c] as const));
  let changed = false;
  const sessions = list.sessions.map((session) => {
    const row = byId.get(session.id);
    if (row === undefined) return session;
    const title = titleFor(row, session.title);
    if (title === session.title) return session;
    changed = true;
    return { ...session, title };
  });
  const known = new Set(list.sessions.map((s) => s.id));
  const added = conversations
    .filter((c) => !known.has(c.id))
    .map<Sv3Session>((c) => ({
      id: c.id,
      title: titleFor(c, ''),
      // Tempdoc 838 — the provenance IS on the wire now, and seeding from it is what makes "a
      // reader's name is never overwritten" true rather than true-until-reload. A conversation
      // merged back from the store after a reload used to start unflagged, so the next ask in it
      // auto-titled over the name the reader had chosen.
      renamed: c.titleSource === 'user',
      turns: [],
      history: null,
      contextUsage: null,
      createdAt: c.createdAt,
      updatedAt: c.lastActiveAt,
      pinned: false,
      completedAt: null,
      lastVisitedAt: 0,
    }));
  if (!changed && added.length === 0) return list;
  return { ...list, sessions: [...sessions, ...added] };
}

/**
 * The store's title wins when it HAS one (that is where a rename was written through to); otherwise
 * the conversation keeps the name it already had, and a nameless one falls back to its opening
 * message — which the store blanks while the conversation store is locked, so the last resort is a
 * placeholder rather than an unclickable empty row.
 */
function titleFor(row: Sv3StoreConversation, current: string): string {
  if (row.title !== null && row.title !== '') return row.title;
  // The PLACEHOLDER is not a name. A conversation restored from the per-tab pointer exists before
  // the list that can name it arrives, so treating its stand-in as an established title would leave
  // the row reading "Untitled conversation" forever — the merge must still be allowed to name it.
  // A conversation the reader really named this is unaffected: the store carries that title above.
  if (current !== '' && current !== SV3_UNTITLED_CONVERSATION) return current;
  return row.firstUserMessage !== '' ? row.firstUserMessage : SV3_UNTITLED_CONVERSATION;
}

/**
 * Reconcile ONE turn's live evidence with the record's (tempdoc 847 F-12).
 *
 * The rule this replaces was `prior.evidence ?? recorded.evidence`, and its intent was rule 2 below:
 * a refresh must never blank a panel the live turn filled. But `??` only asks whether the live turn
 * has an evidence OBJECT, and the live arm publishes one the moment `rag.meta` names a retrieval
 * mode — before a single source or mark exists. So an object holding empty arrays shadowed the
 * record's complete evidence, and it did so PERMANENTLY: the post-`done` refresh is the repair, and
 * it was thrown away every time it arrived, for the session's whole lifetime.
 *
 * The fix keeps the intent and drops the proxy. Each field asks the question the `??` was standing
 * in for — *did the live turn actually observe this?* — so what the turn watched still wins, and
 * what it never saw is taken from the record instead of being asserted as empty. The fields are
 * reconciled INDEPENDENTLY because they are independently observable: a turn whose stream carried
 * sources but whose citation-matches never arrived keeps its sources and gains the record's marks.
 *
 * The two ends stay exactly as they were: a record with no evidence cannot overwrite a live turn's
 * (rule 2), and a turn that observed nothing takes the record's whole record (the cold-load path).
 */
function reconcileEvidence(
  prior: Sv3TurnEvidence | null,
  recorded: Sv3TurnEvidence | null,
): Sv3TurnEvidence | null {
  if (recorded === null) return prior;
  if (prior === null) return recorded;
  return {
    sources: prior.sources.length > 0 ? prior.sources : recorded.sources,
    matches: prior.matches.length > 0 ? prior.matches : recorded.matches,
    marks: prior.marks.length > 0 ? prior.marks : recorded.marks,
    retrievalMode: prior.retrievalMode !== '' ? prior.retrievalMode : recorded.retrievalMode,
  };
}

/**
 * The canonical thread record, projected onto a conversation's turns (tempdoc 822 Phase F6;
 * inventory D1 / tempdoc 561 P-A: *the window is not the authority*).
 *
 * `recordTurns` comes from {@link file://./sv3-record.ts}, which is a pure projection of the SHARED
 * `fetchUnifiedThread` + `projectUnifiedThread` pair — so this window renders the same record the
 * shipped window does, and its history outlives the controller that produced it.
 *
 * THREE THINGS THE RECORD IS NOT ALLOWED TO DO, each because it cannot know the thing it would
 * overwrite:
 *
 *  1. **Touch a STREAMING turn.** The live feed is the authority for the in-flight run and for
 *     nothing else (F2's activeTurnId discipline); a turn still streaming keeps its local id, so the
 *     run's `turnId` stays valid across a refresh, and yields to the record once it settles.
 *  2. **Blank the evidence.** The record DOES carry the answer's sources and matches now (tempdoc
 *     847 §2.4, projected by `sv3-record.ts`), but a live turn watched the stream and can hold what
 *     the record has not caught up to, so what the turn already stood on wins over what the record
 *     reports — and a refresh never empties the panel beside it. What the turn did NOT observe is
 *     taken from the record rather than asserted as empty: see {@link reconcileEvidence}, which is
 *     where the difference between "the live turn holds this" and "the live turn holds an evidence
 *     object" is made (847 F-12).
 *  3. **Re-word a HALT.** "The reader pressed Stop" is not in the record — to the backend a halted
 *     answer just ended. Overwriting it with `complete` would call the reader's own decision a
 *     success (the four-terminal rule {@link Sv3TurnStatus} exists to keep them distinct).
 *  4. **Re-id a turn.** (Tempdoc 847 §1.6b.) The record's id is stamped onto {@link Sv3Turn.recordId}
 *     and the turn's own `id` is left alone, because every piece of UI state about a turn is keyed
 *     on that id.
 *
 * MATCHING IS BY IDENTITY, not by position (847 §2.4.3): a record turn reconciles with the local
 * turn already bearing its `recordId`, and only a turn NOT yet reconciled to anything falls back to
 * ordered position — stamping `recordId` as it does, so the fallback is used at most once per turn
 * and a later refresh is pure identity. A position fallback never lands on a local turn that already
 * carries a DIFFERENT `recordId`: that turn is another record turn's, and any length skew (a turn
 * the record has not caught up to, an interleaved agent turn) would otherwise attribute one turn's
 * evidence, status and duration to another.
 *
 * An EMPTY record leaves the list untouched: `fetchUnifiedThread` returns empty on failure by
 * contract (tempdoc 727 F-8), so "the record said nothing" must never be read as "there is nothing".
 */
export function applySv3Record(
  list: Sv3SessionList,
  sessionId: string,
  recordTurns: readonly Sv3Turn[],
): Sv3SessionList {
  const session = sessionById(list, sessionId);
  if (session === null || recordTurns.length === 0) return list;
  const local = session.turns;
  const localByRecordId = new Map<string, number>();
  local.forEach((turn, index) => {
    if (turn.recordId !== null && !localByRecordId.has(turn.recordId)) {
      localByRecordId.set(turn.recordId, index);
    }
  });
  const claimed = new Set<number>();
  const pairing = new Map<number, number>();
  recordTurns.forEach((recorded, recordIndex) => {
    const recordId = recorded.recordId ?? recorded.id;
    const localIndex = localByRecordId.get(recordId);
    if (localIndex !== undefined && !claimed.has(localIndex)) {
      pairing.set(recordIndex, localIndex);
      claimed.add(localIndex);
    }
  });
  let cursor = 0;
  recordTurns.forEach((_, recordIndex) => {
    if (pairing.has(recordIndex)) return;
    while (
      cursor < local.length &&
      (claimed.has(cursor) || local[cursor]?.recordId != null)
    ) {
      cursor++;
    }
    if (cursor >= local.length) return;
    pairing.set(recordIndex, cursor);
    claimed.add(cursor);
    cursor++;
  });
  const merged: Sv3Turn[] = [];
  // A turn the record has not been told about yet — the in-flight one, or one dispatched while the
  // fetch was in the air — is KEPT, in its own place relative to the turns around it. The record is
  // authoritative for what it holds, never for what has not reached it, and never for the order of
  // what it does not hold.
  let emitted = 0;
  const keepLocalsBefore = (limit: number): void => {
    for (; emitted < limit; emitted++) {
      const trailing = local[emitted];
      if (trailing !== undefined && !claimed.has(emitted)) merged.push(trailing);
    }
  };
  const reconcile = (recorded: Sv3Turn, prior: Sv3Turn | undefined): Sv3Turn => {
    if (prior === undefined) return recorded;
    if (prior.status === 'streaming') return prior;
    return {
      ...recorded,
      id: prior.id,
      recordId: recorded.recordId ?? recorded.id,
      evidence: reconcileEvidence(prior.evidence, recorded.evidence),
      status: prior.status === 'halted' ? 'halted' : recorded.status,
      detail: prior.status === 'halted' ? prior.detail : recorded.detail,
      toolCalls: recorded.toolCalls > 0 ? recorded.toolCalls : prior.toolCalls,
      // The same rule as `evidence`, for the same reason (tempdoc 822 Phase F7): the record carries
      // no rewrite note, no thinking blocks and no receipt, so a refresh that copied its silence over
      // them would delete facts this window actually observed. A cold-loaded turn has none of them —
      // an honest "never told", which is why `sv3-record.ts` seeds them empty rather than guessing.
      standaloneQuestion:
        prior.standaloneQuestion === '' ? recorded.standaloneQuestion : prior.standaloneQuestion,
      reasoning: prior.reasoning.length > 0 ? prior.reasoning : recorded.reasoning,
      durationMs: prior.durationMs ?? recorded.durationMs,
      modelLabel: prior.modelLabel ?? recorded.modelLabel,
    };
  };
  recordTurns.forEach((recorded, recordIndex) => {
    const localIndex = pairing.get(recordIndex);
    if (localIndex !== undefined) {
      keepLocalsBefore(localIndex);
      // MONOTONE, never assigned: when the record's order and the local order disagree — a later
      // record turn reconciling to an EARLIER local turn — rewinding the cursor would re-walk
      // locals already emitted and append an unreconciled one a second time.
      emitted = Math.max(emitted, localIndex + 1);
    }
    merged.push(reconcile(recorded, localIndex === undefined ? undefined : local[localIndex]));
  });
  keepLocalsBefore(local.length);
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === sessionId ? { ...s, turns: merged } : s)),
  };
}

/**
 * The `/history` companion load, recorded on the conversation it describes (tempdoc 852 S1). It
 * touches NOTHING else: the transcript is {@link applySv3Record}'s, the title is the store's, and
 * this reducer only parks the fields neither of them carries.
 *
 * A load for a conversation this window is not listing is dropped, the same rule every other reducer
 * here follows — a record can only ever be written onto the session it names.
 */
export function applySv3History(
  list: Sv3SessionList,
  sessionId: string,
  history: Sv3SessionHistory,
): Sv3SessionList {
  if (sessionById(list, sessionId) === null) return list;
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === sessionId ? { ...s, history } : s)),
  };
}

/**
 * What a terminal reported about the prompt it just spent (tempdoc 852 S2) — recorded on the
 * conversation that spent it, never on the window, so claiming another conversation shows ITS
 * occupancy or none at all rather than the last one the window happened to see.
 */
export function setSessionContextUsage(
  list: Sv3SessionList,
  sessionId: string,
  contextUsage: Sv3ContextUsage,
): Sv3SessionList {
  if (sessionById(list, sessionId) === null) return list;
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === sessionId ? { ...s, contextUsage } : s)),
  };
}

/**
 * The two backend message ids one turn is made of (tempdoc 852 §2.3b) — the ONE answer to "which
 * messages is this turn?", so branch, edit-retry, floor-setting and message-exclusion stop each
 * inventing their own.
 *
 * **What is guaranteed:** a reported id is one the CONVERSATION STORE itself minted, and therefore
 * one `?fromMsgId=`, `{floorMessageId}` and `POST …/messages/{id}/exclude` can address. `null` means
 * this turn has no such message — an affordance that needs one is unavailable, which is a fact about
 * the turn, not a gap in this function.
 *
 * **Why an ALLOWLIST of the store's own mints rather than a list of ids to reject.**
 * `GET /api/thread/{id}` interleaves two planes (`InteractionThreadController.java:66-73`): the chat
 * turns, which ARE store rows, and every agent run's events, projected read-time from
 * `AgentRunStore` and stored as messages nowhere. The run plane mints ids of its own for
 * user messages (`${runId}:user`, `AgentRunQueryService.java:346-350`), assistant messages
 * (`${conversationId}:assistant:${stamp}`, `AgentInteractionMapper.java:69`), workflow node outputs
 * and search events — and `chatTurn` has a fallback of its own for a row with no usable id
 * (`${conversationId}:chat:${msg.hashCode()}`, `:260-262`). Enumerating those is a list that is one
 * entry behind the next event kind someone adds. The store's mints are the closed set: a UUID from
 * `FileConversationStore.enrichMessage` (`:213-219`) or the `idx-N` back-fill `loadHistory` applies
 * on read (`:159-165`) so pre-513 messages stay branchable. Anything else is not a store row, and
 * the honest answer for it is `null`. A new store mint would fail CLOSED here — an affordance
 * unavailable, never one pointed at the wrong message.
 */
export interface Sv3TurnMessageIds {
  /** The turn's USER message id — what `?fromMsgId=` and a branch point key on. */
  readonly userMsgId: string | null;
  /** The turn's terminal ASSISTANT message id — what a floor or an exclusion usually names. */
  readonly assistantMsgId: string | null;
}

/**
 * The two ids `FileConversationStore` mints, and nothing else (see {@link Sv3TurnMessageIds}).
 * Anchored end to end: `${runId}:user` and `${conversationId}:assistant:${stamp}` both CONTAIN a
 * store-shaped substring, and an unanchored test would admit them.
 */
const STORE_MINTED_MESSAGE_ID =
  /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|idx-\d+)$/i;

/**
 * Is this an id the conversation store minted — i.e. one the message endpoints can address? Exported
 * because `sv3-record.ts` applies the same test while projecting, so a turn never even carries a
 * run-plane id in its assistant slot.
 */
export const isSv3StoreMessageId = (id: string | null | undefined): boolean =>
  typeof id === 'string' && STORE_MINTED_MESSAGE_ID.test(id);

export const sv3TurnMessageIds = (turn: Sv3Turn): Sv3TurnMessageIds => ({
  // BOTH conditions, because either alone admits a run-plane id: the record opens a turn on whatever
  // item comes first when no user message precedes it (`sv3-record.ts`), and the run plane mints user
  // messages of its own.
  userMsgId:
    turn.recordOpenedByUser && isSv3StoreMessageId(turn.recordId) ? turn.recordId : null,
  assistantMsgId: isSv3StoreMessageId(turn.assistantRecordId) ? turn.assistantRecordId : null,
});

/**
 * Which turn a backend message id belongs to — the lookup every `/history` field needs, because
 * every one of them (`contextFloor`, `branchPointMessageId`, `excludedMessageIds`) names a MESSAGE
 * while the window renders TURNS.
 *
 * BY ID, never by position, and the two orders genuinely differ: a turn holds a user message and one
 * or more assistant messages, so the nth message is not the nth turn, and `/history`'s array counts
 * rows `/api/thread` never emits — `FileConversationStore.loadHistory` emits a `role:"locked"`
 * placeholder for every sealed line (`:149-157`) and `chatTurn` returns `null` for every role that
 * is not user/assistant (`:247-259`). Resolving a floor by index would silently attach it to a
 * neighbouring turn, which looks entirely plausible on screen.
 */
export function sv3TurnByMessageId(
  turns: readonly Sv3Turn[],
  messageId: string,
): Sv3Turn | null {
  if (messageId === '') return null;
  return (
    turns.find((turn) => {
      const ids = sv3TurnMessageIds(turn);
      return ids.userMsgId === messageId || ids.assistantMsgId === messageId;
    }) ?? null
  );
}

/**
 * The reader parks a conversation on the Pinned shelf, or takes it off. Order is untouched by
 * construction: pinning moves a row between SHELVES and never within one, so a row cannot slide
 * out from under the pointer as a consequence of being pinned.
 */
export function toggleSessionPin(list: Sv3SessionList, id: string): Sv3SessionList {
  if (sessionById(list, id) === null) return list;
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === id ? { ...s, pinned: !s.pinned } : s)),
  };
}

/**
 * What the window knows about work in flight, as the two facts a session cannot derive on its own
 * (tempdoc 831). Both are the WINDOW's: the search store's process-wide busy flag, and which
 * conversation owns a delegated run that is parked on the reader. Passed in rather than read here,
 * for the same reason everything else in this module is pure.
 */
export interface Sv3RunGate {
  readonly searching: boolean;
  readonly awaitingDecisionIn: string | null;
}

/**
 * Is something in flight in this conversation (tempdoc 831)? ONE predicate, consulted by both the
 * row projection (which paints the colour and withholds the destructive action) and
 * {@link removeSession} (which refuses it) — so what the sidebar SHOWS and what the list ALLOWS
 * cannot disagree. A second copy of this expression is the drift that would let a row offer a
 * delete the store then silently declined, or refuse one the row was still offering.
 *
 * Three ways to be live, and they are three different things: a delegated run parked on the reader's
 * decision (act-now — parked is not finished), a turn still streaming, and the process-wide search
 * flag, which only the ACTIVE session may claim because the store cannot say who asked.
 */
export function sv3SessionIsLive(
  list: Sv3SessionList,
  id: string,
  { searching, awaitingDecisionIn }: Sv3RunGate,
): boolean {
  const session = sessionById(list, id);
  if (session === null) return false;
  if (awaitingDecisionIn === id) return true;
  if (session.turns.at(-1)?.status === 'streaming') return true;
  return list.activeId === id && searching;
}

/**
 * The reader discards a conversation (tempdoc 831). Destructive and therefore GATED: a conversation
 * with work in flight is never removed, because the run outlives the row — deleting the row would
 * leave a stream writing to a session nobody can see, and the reader would have been told the work
 * was gone when it was still running. The refusal is a no-op returning the same list, so a caller
 * that took a different route in cannot get a delete the row refused to offer.
 *
 * Local removal only: EXISTENCE is the conversation store's (see the persistence boundary at the top
 * of this file), so the caller writes the deletion through to it. This function decides what leaves
 * THIS window's list, and the claim goes with it — a list cannot stay pointed at a row it no longer
 * holds.
 */
export function removeSession(list: Sv3SessionList, id: string, gate: Sv3RunGate): Sv3SessionList {
  if (sessionById(list, id) === null) return list;
  if (sv3SessionIsLive(list, id, gate)) return list;
  return {
    sessions: list.sessions.filter((session) => session.id !== id),
    activeId: list.activeId === id ? null : list.activeId,
  };
}

/**
 * What an edited title should DO, decided before anything is written (tempdoc 822 Phase F5). The
 * design spec's own three-way rule, ported verbatim
 * ("trim, reject empty (the caller toasts), and skip the mutation when nothing changed") — it is one
 * function there precisely because the spec's sidebar rename and header rename must not diverge, and
 * it is one function here for the same reason.
 *
 * `reject-empty` is a REVERT, not an error state: the conversation keeps the title it had. A window
 * that let a row become nameless would have traded a label the reader can find for one they cannot.
 */
export type Sv3RenameResolution =
  | { readonly action: 'commit'; readonly title: string }
  | { readonly action: 'reject-empty' }
  | { readonly action: 'noop' };

export function resolveSv3Rename(title: string, originalTitle: string): Sv3RenameResolution {
  const trimmed = title.trim();
  if (trimmed.length === 0) return { action: 'reject-empty' };
  if (trimmed === originalTitle) return { action: 'noop' };
  return { action: 'commit', title: trimmed };
}

/**
 * The reader names a conversation. It also RAISES {@link Sv3Session.renamed}, which is what auto-
 * titling loses to (tempdoc 822 Phase F7): F5 could rely on construction — no auto-titling pass
 * existed to outrank — and F7 added one, so the precedence has to be a recorded fact rather than an
 * absence.
 *
 * An empty or unchanged title leaves the list untouched — {@link resolveSv3Rename} is the one place
 * that decides, so a caller cannot commit a blank by taking a different route in. Phase F6: the
 * caller WRITES THE OUTCOME THROUGH to `setConversationTitle`, so the name survives the process and
 * every surface listing the conversation shows the one the reader chose; the decision still happens
 * exactly here.
 */
export function renameSession(list: Sv3SessionList, id: string, title: string): Sv3SessionList {
  const session = sessionById(list, id);
  if (session === null) return list;
  const resolution = resolveSv3Rename(title, session.title);
  if (resolution.action !== 'commit') return list;
  return {
    ...list,
    sessions: list.sessions.map((s) =>
      s.id === id ? { ...s, title: resolution.title, renamed: true } : s,
    ),
  };
}

/**
 * Put a rename BACK (tempdoc 838). The store refused the write — a locked key, an unreachable Head —
 * so the row must stop showing a name nothing will remember.
 *
 * Not {@link renameSession} with the old value: that is the READER's decision, and it would both
 * refuse an empty restore (a conversation that never had a name) and leave `renamed` raised, which
 * would then outrank auto-titling on the strength of a rename that never happened. A revert restores
 * the flag it found, because the question "did the reader name this?" has the same answer it had
 * before the attempt.
 */
export function restoreSessionTitle(
  list: Sv3SessionList,
  id: string,
  title: string,
  renamed: boolean,
): Sv3SessionList {
  const session = sessionById(list, id);
  if (session === null || (session.title === title && session.renamed === renamed)) return list;
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === id ? { ...s, title, renamed } : s)),
  };
}

/**
 * May the window ask the model to name this conversation (tempdoc 822 Phase F7; inventory A11)?
 *
 * The shipped window's rule is "≥ 2 messages" (`views/UnifiedChatView.ts:2045-2051`) — one ask and
 * one answer, so there is something to summarise. Two things are added, both refusals rather than
 * conditions:
 *
 *  - **A renamed conversation is never re-titled.** The reader already answered the question the
 *    model would be answering.
 *  - **Only a COMPLETED ask counts.** A halted, refused or failed turn has no answer worth naming
 *    a conversation after, and titling one from the fragment that arrived would put the model's
 *    summary of a broken turn in the sidebar forever (the row label never re-derives).
 */
export function sv3ShouldGenerateTitle(session: Sv3Session): boolean {
  if (session.renamed) return false;
  const turn = session.turns.find((t) => t.kind === 'ask' && t.status === 'complete');
  return turn !== undefined && turn.question !== '' && turn.answer.trim() !== '';
}

/**
 * The New-search affordance: the window returns to its empty state and the NEXT submit opens a new
 * session. Nothing is dropped — the sessions so far stay in the list, they are just no longer active.
 */
export const startNewSession = (list: Sv3SessionList): Sv3SessionList => ({
  ...list,
  activeId: null,
});

/**
 * Clicking a row claims it; an unknown id changes nothing rather than clearing the claim.
 *
 * A claim IS the visit that clears the unread bit — the reader now has the conversation on screen,
 * so a bit that survived the click would be claiming they had not seen what they are looking at.
 */
export function focusSession(list: Sv3SessionList, id: string, now: number): Sv3SessionList {
  if (sessionById(list, id) === null) return list;
  return {
    ...list,
    activeId: id,
    sessions: list.sessions.map((s) => (s.id === id ? { ...s, lastVisitedAt: now } : s)),
  };
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * A coarse relative timestamp, rendered on RENDER and never ticked — a live clock in a sidebar is
 * continuous motion at rest, which the spec's duty-cycle law rules out. Coarse also means honest:
 * "2m" claims nothing a second-resolution label would have to keep re-proving.
 */
export function sv3RelativeTime(then: number, now: number): string {
  const delta = now - then;
  // A clock that went backwards (skew, DST) reads as "now" rather than as a negative age.
  if (delta < MINUTE) return 'now';
  if (delta < HOUR) return `${Math.floor(delta / MINUTE)}m`;
  if (delta < DAY) return `${Math.floor(delta / HOUR)}h`;
  return `${Math.floor(delta / DAY)}d`;
}

/** The row as the sidebar renders it — a projection of a session, never a second copy of one. */
export interface Sv3SessionRowView {
  readonly id: string;
  readonly label: string;
  readonly status: Sv3RowStatus;
  readonly meta: string;
  readonly active: boolean;
  readonly pinned: boolean;
  /** Something finished here while the reader was elsewhere, and they have not been back since. */
  readonly unread: boolean;
  /**
   * Work is in flight in this conversation ({@link sv3SessionIsLive}) — the row withholds its
   * destructive action while it is. Carried on the ROW rather than re-derived from the status
   * colour, so the affordance and the list's own refusal read the same fact.
   */
  readonly live: boolean;
}

export interface Sv3SessionGroup {
  readonly id: string;
  readonly label: string;
  readonly rows: readonly Sv3SessionRowView[];
}

/**
 * The three shelves, in render order (tempdoc 822 Phase F3). ACTIVE first because it is the shelf
 * that can be waiting on the reader; Pinned is where they parked things; Recent is the tail.
 *
 * "Recent" rather than the spec's "Settled": a settled RUN is a lifecycle word for something that
 * was working and stopped, and most rows here are conversations that simply ended — "Recent" says
 * the true thing about the shelf (it is the tail in creation order) without implying a run.
 *
 * Snooze is deliberately absent: it needs a menu and a wake timer (820 W2's "raise a hand on fresh
 * blockage"), and a shelf nothing can put a row on would be scaffolding.
 */
type Sv3Shelf = 'active' | 'pinned' | 'recent';

const SHELVES: readonly { readonly shelf: Sv3Shelf; readonly id: string; readonly label: string }[] =
  [
    { shelf: 'active', id: 'sv3-shelf-active', label: 'Active' },
    { shelf: 'pinned', id: 'sv3-shelf-pinned', label: 'Pinned' },
    { shelf: 'recent', id: 'sv3-shelf-recent', label: 'Recent' },
  ];

export interface Sv3SessionProjection {
  /** The shared store's in-flight flag: the ACTIVE session is the one that asked. */
  readonly searching: boolean;
  /**
   * The session whose delegated run is parked on a typed decision, or null (tempdoc 822 Phase F2).
   * Named by ID rather than passed as a flag for the active row, because the shared controller is
   * product-wide: only the session that OPENED the run may wear its act-now colour.
   */
  readonly awaitingDecisionIn: string | null;
  readonly now: number;
}

/**
 * Sessions → sidebar SHELVES (tempdoc 822 Phase F3; replaces A2's Today/Earlier recency buckets —
 * the spec's real grouping is state, and the sidebar-comparison finding 4 resolves toward it).
 * A shelf is rendered only when it holds rows, so a window with one running conversation shows one
 * heading rather than a column of empty ones.
 *
 * Two rules the shelves must not break:
 *
 *  - **A run that is working, or blocked on the reader, is on ACTIVE regardless of pin state.** This
 *    is 820 W2's activity-blockers-override: a run waiting on your decision cannot be tucked away on
 *    the shelf where you once parked it. Pin is the reader's intent about a resting conversation; it
 *    does not get to hide a live one.
 *  - **A shelf move is never a reorder.** Rows keep the list's fixed creation order inside every
 *    shelf, so pinning a row changes which heading it sits under and nothing else.
 */
export function projectSv3Sessions(
  list: Sv3SessionList,
  { searching, awaitingDecisionIn, now }: Sv3SessionProjection,
): readonly Sv3SessionGroup[] {
  const toRow = (session: Sv3Session): Sv3SessionRowView => {
    const active = session.id === list.activeId;
    const last = session.turns.at(-1);
    // ACT-NOW outranks in-motion: a run parked on the reader's decision is not making progress, and
    // the one colour that means "you are the blocker" must win over the one that means "it is busy".
    const awaiting = awaitingDecisionIn === session.id;
    // Two axes reach the same three colours. The CONVERSATIONAL one is the session's own: its last
    // turn is streaming, or it broke — a property of THIS session, true whichever row is claimed.
    // The SEARCH one is the process-wide store flag, which only the active session can own (the
    // store cannot say who asked), and which A2's semantics already limited that way. Both axes are
    // {@link sv3SessionIsLive}'s, read here rather than re-expressed, so the colour and the
    // destructive action's gate cannot drift apart (tempdoc 831): outside act-now, `live` IS the
    // in-motion condition, which is why one predicate can decide both.
    const live = sv3SessionIsLive(list, session.id, { searching, awaitingDecisionIn });
    const broken = last?.status === 'failed' || last?.status === 'refused';
    return {
      id: session.id,
      label: session.title,
      status: awaiting ? 'act-now' : live ? 'in-motion' : broken ? 'broken' : 'resting',
      meta: live ? '' : sv3RelativeTime(session.updatedAt, now),
      live,
      active,
      pinned: session.pinned,
      // Unread is a comparison, not a flag anything sets: something finished after the last visit.
      unread: session.completedAt !== null && session.completedAt > session.lastVisitedAt,
    };
  };
  const shelfOf = (session: Sv3Session, row: Sv3SessionRowView): Sv3Shelf => {
    // The colour budget already decided this: act-now means the run is blocked on the reader and
    // in-motion means it is working. Both are ACTIVE, and both outrank the pin (blockers-override).
    if (row.status === 'act-now' || row.status === 'in-motion') return 'active';
    return session.pinned ? 'pinned' : 'recent';
  };
  const rows = new Map<Sv3Shelf, Sv3SessionRowView[]>([
    ['active', []],
    ['pinned', []],
    ['recent', []],
  ]);
  for (const session of list.sessions) {
    const row = toRow(session);
    rows.get(shelfOf(session, row))?.push(row);
  }
  return SHELVES.filter((shelf) => (rows.get(shelf.shelf)?.length ?? 0) > 0).map((shelf) => ({
    id: shelf.id,
    label: shelf.label,
    rows: rows.get(shelf.shelf) ?? [],
  }));
}

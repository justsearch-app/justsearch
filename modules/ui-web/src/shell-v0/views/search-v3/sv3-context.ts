// SPDX-License-Identifier: Apache-2.0
/**
 * The effective-context set for Search v3 (tempdoc 610, ported by 852 S2) — the PURE half.
 *
 * Tempdoc 610 gave the shipped window five acts over what the next prompt actually contains: rewind
 * the context to a turn (the FLOOR), clear it, COMPACT everything above the floor into a summary,
 * EDIT that summary, and hide a single message from the prompt while it stays in the transcript.
 * All five are live backend endpoints behind five shared store functions
 * (`state/conversationListStore.ts`), and this window imported none of them until now.
 *
 * What lives here is everything that can be decided without a DOM or a fetch: which turn a floor
 * names, which turns are consequently out of context, which are excluded, what a turn's menu may
 * offer, and the whole-prompt projection the shared context inspector renders. The window
 * ({@link SearchV3View}) owns the writes and the reload; the regions render what this returns.
 *
 * TWO LAWS THIS MODULE ENFORCES, both inherited from 852 S1:
 *
 * 1. **Ids come from {@link sv3TurnMessageIds} and nowhere else.** A turn reports a message id only
 *    when the CONVERSATION STORE minted it, so an affordance can never be pointed at an
 *    agent-run-plane id no `{floorMessageId}` or `/messages/{id}/exclude` endpoint would accept. A
 *    turn that names no store message therefore offers NO context affordance at all — the honest
 *    null, not a control that fails when pressed.
 * 2. **Message → turn resolution is BY ID** ({@link sv3TurnByMessageId}), never by position.
 *    `/history` counts rows `/api/thread` never emits (locked placeholders, system messages), so a
 *    floor resolved by index would attach to a neighbouring turn and look entirely plausible.
 */
import type { InspectorPhase, InspectorSegment, InspectorView } from '../../components/ContextInspectorPane.js';
import {
  sv3TurnByMessageId,
  sv3TurnMessageIds,
  type Sv3ContextUsage,
  type Sv3SessionHistory,
  type Sv3Turn,
} from './sv3-sessions.js';
import {
  CONTEXT_INSPECTOR_CONVERSATION,
  CONTEXT_INSPECTOR_SUMMARY,
  CONTEXT_MENU_COMPACT,
  CONTEXT_MENU_EXCLUDE,
  CONTEXT_MENU_INCLUDE,
  CONTEXT_MENU_RESET,
  CONTEXT_INSPECTOR_DOCUMENTS,
  CONTEXT_TURN_ASSISTANT,
  CONTEXT_TURN_USER,
} from './fixtures.js';

/**
 * The reader asked for a turn's context menu. The REGION announces the ask and hands over the
 * anchor it alone knows (the trigger's own rect); the WINDOW opens the menu, because only the
 * window holds the history the menu's entries are derived from — the same split
 * `SV3_RUN_DECISION` already makes for a run prompt.
 */
export const SV3_CONTEXT_MENU = 'sv3-context-menu';

/** The reader resolved a context act. Raised by the transcript's floor divider and the context bar. */
export const SV3_CONTEXT_ACTION = 'sv3-context-action';

export interface Sv3ContextMenuRequest {
  readonly turnId: string;
  readonly x: number;
  readonly y: number;
}

/**
 * The five acts of tempdoc 610 plus the three reads around them. `floor`/`compact`/`exclude`/
 * `include` name a TURN; `restore`/`summary`/`include-all`/`inspect` are conversation-wide.
 */
export type Sv3ContextActionId =
  | 'floor'
  | 'compact'
  | 'exclude'
  | 'include'
  | 'restore'
  | 'summary'
  | 'include-all'
  | 'inspect';

export interface Sv3ContextAction {
  readonly action: Sv3ContextActionId;
  /** The turn the act names, for the four per-turn acts. */
  readonly turnId?: string;
  /** The edited summary text, for `summary`. */
  readonly text?: string;
}

/** What the effective context does with ONE turn — the whole per-turn render and menu input. */
export interface Sv3TurnContext {
  readonly turnId: string;
  /**
   * Every store-minted message this turn is made of, oldest first. EMPTY means the turn names none
   * (a live turn, or an agent turn whose ids belong to the run plane) — and an empty list is what
   * withholds every affordance below.
   */
  readonly messageIds: readonly string[];
  /**
   * Which message a floor or a compaction would name: the turn's QUESTION, so "reset to this turn"
   * keeps the turn itself in context. A turn the record opened on a stored assistant row has no
   * question to name and falls back to that row, which is still a store message the endpoint
   * accepts; a turn with neither is `null` and offers nothing.
   */
  readonly floorMessageId: string | null;
  /** This turn IS the floor — the divider renders above it. */
  readonly isFloor: boolean;
  /** Above the floor: still in the transcript, no longer in the prompt. */
  readonly outOfContext: boolean;
  /** The subset of {@link messageIds} the reader has individually hidden. */
  readonly excludedIds: readonly string[];
  /** At least one of this turn's messages is hidden from the prompt. */
  readonly hasExcluded: boolean;
  /**
   * Whether the turn's QUESTION, and its ANSWER, are in the next prompt — the floor and the
   * exclusions applied per message, the way the store applies them. A turn can be half in: its
   * answer hidden while its question stays, or (a floor on its answer) its question cut while its
   * answer stays. This is what the follow-up's `context` and the inspector are both built from.
   */
  readonly questionInContext: boolean;
  readonly answerInContext: boolean;
  /**
   * The texts of the turn's saved answer messages that are in the next prompt, oldest first — the
   * same per-message rule, applied to each message of an answer the record saved as several (S2.0).
   */
  readonly answerTextsInContext: readonly string[];
}

const EMPTY_IDS: readonly string[] = [];

/** The ids one turn can address, oldest first (the question, then the answer). */
const messageIdsOf = (turn: Sv3Turn): readonly string[] => {
  const { userMsgId, assistantMsgId } = sv3TurnMessageIds(turn);
  const ids: string[] = [];
  if (userMsgId !== null) ids.push(userMsgId);
  if (assistantMsgId !== null) ids.push(assistantMsgId);
  return ids.length === 0 ? EMPTY_IDS : ids;
};

/**
 * A turn's answer as its saved messages — the record's, or, for a live turn the store has not
 * numbered yet, its one answer under the id it has (none).
 */
const answerPartsOf = (turn: Sv3Turn): readonly { readonly id: string | null; readonly content: string }[] =>
  turn.answerMessages !== undefined && turn.answerMessages.length > 0
    ? turn.answerMessages
    : [{ id: sv3TurnMessageIds(turn).assistantMsgId, content: turn.answer }];

/** Every saved message id of a turn, in saved order: the question, then each answer message. */
const savedIdsOf = (turn: Sv3Turn): readonly string[] => {
  const { userMsgId } = sv3TurnMessageIds(turn);
  const ids = answerPartsOf(turn).flatMap((part) => (part.id === null ? [] : [part.id]));
  return userMsgId === null ? ids : [userMsgId, ...ids];
};

/**
 * Where the floor sits among the turns on screen: the turn it starts in, and the ids in that turn
 * that come BEFORE it (and so are cut with the turns above). `null` = no floor in force.
 *
 * A floor on one of a turn's saved messages — its question, its answer's handle, or an inner
 * message of an answer saved as several — is placed on that message, by id; only the first two are
 * ON SCREEN (a divider can carry them, {@link sv3TurnByMessageId}). A floor on a message no turn
 * holds is placed by the order `/history` saved the messages in, which is the order the store trims
 * by. A floor `/history` does not hold at all is no floor, exactly as
 * `FileConversationStore.loadEffectiveContext` treats a stale one (it fails safe to the whole
 * history); the bar still says it is there.
 */
function placeFloor(
  turns: readonly Sv3Turn[],
  history: Sv3SessionHistory | null,
): { readonly index: number; readonly onScreen: boolean; readonly cut: ReadonlySet<string> } | null {
  const floor = history?.contextFloor ?? '';
  if (floor === '') return null;
  const holder = turns.findIndex((turn) => savedIdsOf(turn).includes(floor));
  if (holder >= 0) {
    // Everything the turn saved before the floor message is cut with the turns above.
    const ids = savedIdsOf(turns[holder]!);
    return {
      index: holder,
      onScreen: sv3TurnByMessageId(turns, floor) !== null,
      cut: new Set(ids.slice(0, ids.indexOf(floor))),
    };
  }
  const order = history?.messageIds ?? [];
  const floorRank = order.indexOf(floor);
  if (floorRank < 0) return null;
  // A stored id the order does not list was saved after `/history` was last read, so it is newer
  // than every id the order does list, and on the kept side of any floor in it.
  const rank = (id: string): number => {
    const at = order.indexOf(id);
    return at < 0 ? Number.POSITIVE_INFINITY : at;
  };
  for (let index = 0; index < turns.length; index++) {
    const ids = savedIdsOf(turns[index]!);
    const kept = ids.filter((id) => rank(id) >= floorRank);
    if (kept.length > 0) {
      return { index, onScreen: false, cut: new Set(ids.filter((id) => !kept.includes(id))) };
    }
  }
  // Every stored message on screen is above the floor: the turns still in context are the ones the
  // store has not numbered yet (a live turn).
  const firstUnnumbered = turns.findIndex((turn) => savedIdsOf(turn).length === 0);
  return { index: firstUnnumbered < 0 ? turns.length : firstUnnumbered, onScreen: false, cut: new Set() };
}

/**
 * What the conversation's `/history` says about each turn on screen. `history === null` is "not
 * told" and yields a frame with no floor and no exclusions — deliberately the same shape as a
 * conversation that really has neither, because nothing is RENDERED for either and inventing a
 * third visual state for "we have not asked yet" would say something the window does not know.
 *
 * The floor is placed in the FULL order before any exclusion applies (S2.9): hiding the floor's own
 * message hides that message, it does not move the floor and bring the turns above it back.
 */
export function projectSv3TurnContexts(
  turns: readonly Sv3Turn[],
  history: Sv3SessionHistory | null,
): readonly Sv3TurnContext[] {
  const floor = placeFloor(turns, history);
  const excluded = new Set(history?.excludedMessageIds ?? []);
  return turns.map((turn, index) => {
    const messageIds = messageIdsOf(turn);
    const excludedIds = messageIds.filter((id) => excluded.has(id));
    const outOfContext = floor !== null && index < floor.index;
    const { userMsgId } = sv3TurnMessageIds(turn);
    const kept = (id: string | null): boolean =>
      !outOfContext &&
      (id === null || (!excluded.has(id) && !(floor !== null && index === floor.index && floor.cut.has(id))));
    const answerTextsInContext = answerPartsOf(turn)
      .filter((part) => kept(part.id))
      .map((part) => part.content);
    return {
      turnId: turn.id,
      messageIds,
      // The question first, and the answer only when there is no question to name.
      floorMessageId: messageIds[0] ?? null,
      isFloor: floor !== null && floor.onScreen && index === floor.index,
      outOfContext,
      excludedIds,
      hasExcluded: excludedIds.length > 0,
      questionInContext: kept(userMsgId),
      answerInContext: answerTextsInContext.length > 0,
      answerTextsInContext,
    };
  });
}

/**
 * The floor `/history` reports does not name any turn on screen — it sits on a message this window
 * does not draw as its own turn, or on one it no longer holds. There is then no divider to carry it,
 * so the bar says so and offers the summary and Restore itself (S2.8).
 */
export const sv3FloorUnseen = (
  turns: readonly Sv3Turn[],
  history: Sv3SessionHistory | null,
): boolean => {
  const floor = history?.contextFloor ?? '';
  return floor !== '' && sv3TurnByMessageId(turns, floor) === null;
};

export const sv3TurnContextFor = (
  contexts: readonly Sv3TurnContext[],
  turnId: string,
): Sv3TurnContext | null => contexts.find((c) => c.turnId === turnId) ?? null;

/** How many TURNS the reader has hidden something of — what the context bar counts. */
export const sv3ExcludedTurnCount = (contexts: readonly Sv3TurnContext[]): number =>
  contexts.filter((c) => c.hasExcluded).length;

/**
 * Every message id the conversation hides from the prompt, ONCE each, whether or not a turn on
 * screen names it — the bulk undo's whole target (S2.7). The record's list is what the store drops
 * from the prompt; a hidden id this window does not draw is still hidden, so undoing only the
 * visible ones would announce "everything is back" over a prompt still missing messages.
 */
export const sv3ExcludedMessageIds = (history: Sv3SessionHistory | null): readonly string[] => [
  ...new Set(history?.excludedMessageIds ?? []),
];

/** The hidden message ids no turn on screen names, once each — what the bar counts as "not shown here". */
export const sv3UnseenExcludedIds = (
  contexts: readonly Sv3TurnContext[],
  history: Sv3SessionHistory | null,
): readonly string[] => {
  const seen = new Set(contexts.flatMap((c) => c.messageIds));
  return sv3ExcludedMessageIds(history).filter((id) => !seen.has(id));
};

/** One message of the next prompt's conversation, in the `{role, content}` shape the ask sends. */
export interface Sv3ContextMessage {
  readonly role: 'system' | 'user' | 'assistant';
  readonly content: string;
}

/** The summary's framing — `FileConversationStore.loadEffectiveContext`'s own, so both paths read alike. */
const SV3_CONTEXT_SUMMARY_PREFIX = 'Summary of earlier conversation:\n';

/**
 * The conversation a FOLLOW-UP ask carries (S2.0): the compaction summary first when there is one,
 * then every turn's question and answer that are still in context, oldest first. The same per-turn
 * flags draw the transcript, the bar and the inspector, so what is shown as in context is what is
 * sent. The ask shape is ephemeral — the backend loads no history for it — so this IS the model's
 * memory of the conversation.
 *
 * An answer the record saved as several messages is sent message by message, as saved, so a
 * message hidden from inside it — or saved before a floor set inside it — is left out like any other.
 */
export function sv3FollowUpContext(
  turns: readonly Sv3Turn[],
  contexts: readonly Sv3TurnContext[],
  history: Sv3SessionHistory | null,
): readonly Sv3ContextMessage[] {
  const out: Sv3ContextMessage[] = [];
  const summary = history?.contextFloorSummary ?? '';
  if (summary.trim() !== '') {
    out.push({ role: 'system', content: `${SV3_CONTEXT_SUMMARY_PREFIX}${summary}` });
  }
  turns.forEach((turn, index) => {
    const context = contexts[index];
    if (turn.question.trim() !== '' && (context?.questionInContext ?? true)) {
      out.push({ role: 'user', content: turn.question });
    }
    const answers = context === undefined ? [turn.answer] : context.answerTextsInContext;
    for (const answer of answers) {
      if (answer.trim() !== '') out.push({ role: 'assistant', content: answer });
    }
  });
  return out;
}

/** One entry of a turn's context menu, before the shared menu primitive's own vocabulary is applied. */
export interface Sv3ContextMenuItem {
  readonly id: Sv3ContextActionId;
  readonly label: string;
  readonly enabled: boolean;
}

export interface Sv3ContextMenuInput {
  /** A compaction is already running — a second one would race the first over the same floor. */
  readonly compacting: boolean;
  /** Something in this conversation is streaming; the context of a prompt in flight is not editable. */
  readonly streaming: boolean;
  /** The conversation's floor as `/history` reports it, or null. */
  readonly contextFloor: string | null;
  /** Whether that floor carries a compaction summary — a plain re-floor still CLEARS one. */
  readonly hasSummary: boolean;
}

/**
 * What a turn's ⋯ menu may offer. An EMPTY list is the honest null and the region renders no
 * trigger at all: a turn whose messages the endpoints cannot address has nothing to offer, and a
 * greyed-out menu of four impossible acts would be four lies in a row.
 *
 * `index > 0` gates compaction for the reference window's reason: there is nothing above the first
 * turn to summarize.
 */
export function sv3ContextMenuItems(
  contexts: readonly Sv3TurnContext[],
  turnId: string,
  input: Sv3ContextMenuInput,
): readonly Sv3ContextMenuItem[] {
  const index = contexts.findIndex((c) => c.turnId === turnId);
  const context = index < 0 ? null : contexts[index];
  if (context === null || context === undefined || context.messageIds.length === 0) return [];
  if (input.streaming) return [];
  const items: Sv3ContextMenuItem[] = [];
  if (context.floorMessageId !== null) {
    items.push({
      id: 'floor',
      label: CONTEXT_MENU_RESET,
      // Already the floor AND carrying no summary ⇒ pressing it would change nothing. With a
      // summary it still does: a plain rewind drops the summary the compaction attached.
      enabled: input.contextFloor !== context.floorMessageId || input.hasSummary,
    });
    if (index > 0) {
      items.push({ id: 'compact', label: CONTEXT_MENU_COMPACT, enabled: !input.compacting });
    }
  }
  items.push(
    context.hasExcluded
      ? { id: 'include', label: CONTEXT_MENU_INCLUDE, enabled: true }
      : { id: 'exclude', label: CONTEXT_MENU_EXCLUDE, enabled: true },
  );
  return items;
}

/**
 * The `done` terminal's own report of the prompt it spent (tempdoc 610 §E.4). Read HERE rather than
 * in the ask client's handler table so the shape is validated once, next to the meter that renders
 * it; an absent or non-numeric occupancy is `null` — the meter is omitted rather than shown at 0%.
 */
export function readSv3ContextUsage(payload: unknown): Sv3ContextUsage | null {
  const p = payload as
    | {
        promptTokens?: unknown;
        contextBreakdown?: { system?: unknown; conversation?: unknown; retrieved?: unknown };
      }
    | null
    | undefined;
  if (p === null || p === undefined || typeof p.promptTokens !== 'number' || p.promptTokens <= 0) {
    return null;
  }
  const b = p.contextBreakdown;
  const breakdown =
    b !== null &&
    b !== undefined &&
    typeof b.system === 'number' &&
    typeof b.conversation === 'number' &&
    typeof b.retrieved === 'number'
      ? { system: b.system, conversation: b.conversation, retrieved: b.retrieved }
      : null;
  return { promptTokens: p.promptTokens, breakdown };
}

/**
 * §L.1 — where a segment sits in the COMBINED prompt order. The head and the tail are attended to;
 * the middle is where a model loses things, and a reader deciding what to compact deserves to know
 * which of their turns is sitting there. The reference window's own thresholds.
 */
const positionOf = (index: number, total: number): 'strong' | 'weak' => {
  if (total <= 4) return 'strong';
  const head = Math.ceil(total * 0.25);
  const tail = Math.floor(total * 0.75);
  return index < head || index >= tail ? 'strong' : 'weak';
};

/**
 * Tempdoc 610 §J/§K — the whole-prompt projection the SHARED inspector renders, computed POST-HOC
 * from what is already on screen (no re-retrieval): the standing summary, the in-context turns, and
 * the last in-context answer's retrieved sources, in the order the prompt assembles them.
 *
 * The floor and the exclusions are applied HERE, which is what makes the inspector agree with the
 * transcript: a turn drawn as out-of-context or hidden is a turn the inspector does not list.
 *
 * Per-segment tokens are deliberately `null` — only the phase totals were ever estimated, and
 * dividing an estimate across segments would manufacture precision the backend never reported.
 */
export function projectSv3ContextInspector(
  turns: readonly Sv3Turn[],
  contexts: readonly Sv3TurnContext[],
  history: Sv3SessionHistory | null,
  usage: Sv3ContextUsage | null,
  contextWindow: number | null,
): InspectorView {
  const ordered: Array<{ kind: 'turn' | 'source'; label: string; text: string }> = [];
  // The SAME projection the follow-up sends, message for message (S2.0): a hidden answer leaves its
  // question listed, exactly as it leaves it in the prompt.
  for (const message of sv3FollowUpContext(turns, contexts, history)) {
    ordered.push({
      kind: 'turn',
      label:
        message.role === 'system'
          ? CONTEXT_INSPECTOR_SUMMARY
          : message.role === 'user'
            ? CONTEXT_TURN_USER
            : CONTEXT_TURN_ASSISTANT,
      text:
        message.role === 'system'
          ? message.content.slice(SV3_CONTEXT_SUMMARY_PREFIX.length)
          : message.content,
    });
  }
  const answered = turns.filter((_turn, index) => contexts[index]?.answerInContext ?? true);
  const lastWithSources = [...answered].reverse().find((t) => (t.evidence?.sources.length ?? 0) > 0);
  for (const source of lastWithSources?.evidence?.sources ?? []) {
    ordered.push({
      kind: 'source',
      label: source.headingText === '' ? source.parentDocId : source.headingText,
      text: source.excerpt,
    });
  }
  const conversation: InspectorSegment[] = [];
  const documents: InspectorSegment[] = [];
  ordered.forEach((entry, index) => {
    const segment: InspectorSegment = {
      label: entry.label,
      text: entry.text,
      tokens: null,
      position: positionOf(index, ordered.length),
    };
    if (entry.kind === 'source') documents.push(segment);
    else conversation.push(segment);
  });
  const breakdown = usage?.breakdown ?? null;
  const phases: InspectorPhase[] = [
    {
      name: CONTEXT_INSPECTOR_CONVERSATION,
      tokens: breakdown?.conversation ?? null,
      segments: conversation,
    },
    {
      name: CONTEXT_INSPECTOR_DOCUMENTS,
      tokens: breakdown?.retrieved ?? null,
      segments: documents,
    },
  ];
  return {
    systemTokens: breakdown?.system ?? null,
    phases,
    totalTokens: usage?.promptTokens ?? null,
    windowTokens: contextWindow,
  };
}

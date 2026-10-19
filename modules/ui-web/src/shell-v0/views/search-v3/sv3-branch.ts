// SPDX-License-Identifier: Apache-2.0
/**
 * Branch, edit / retry and the version pager for Search v3 (slice 513 + tempdoc 610 Phase A/B,
 * ported by 852 S3) — the PURE half.
 *
 * The shipped window builds all three of these on ONE backend act: `POST …/branch?fromMsgId=`
 * (`state/conversationListStore.ts` `branchConversation`, `ChatController.java:612-637`). There is
 * no edit endpoint and no retry endpoint — an edit is *branch from before this question, then send
 * the new text*, and a retry is the same with the old text (`views/UnifiedChatView.ts:1471-1497`,
 * `branchAndResend`). This module ports that ARITHMETIC, and nothing else: which message id each of
 * the three acts names, and which conversations are the versions of one turn.
 *
 * FOUR LAWS, the first two inherited from 852 S1/S2, the third from the reference, the fourth from 863:
 *
 * 1. **Ids come from {@link sv3TurnMessageIds} and nowhere else.** A turn reports a message id only
 *    when the CONVERSATION STORE minted it, so `?fromMsgId=` can never be handed an agent-run-plane
 *    id the endpoint would reject. A turn that names no store message offers NO branch affordance —
 *    the honest null, not a control that fails when pressed.
 * 2. **Message → turn resolution is BY ID** ({@link sv3TurnByMessageId}), never by position.
 * 3. **An edit forks from BEFORE the question, a branch forks from AFTER the answer.** They are two
 *    different ids on the same turn and confusing them is silent: an edit forked from the turn's own
 *    answer would inherit the very exchange the reader was replacing, and the transcript would look
 *    entirely plausible. {@link Sv3TurnLineage} carries both, named for what they do.
 * 4. **A turn re-runs in its own tier.** 863 §4.A.5 A-8 withheld all three acts on an AGENT turn,
 *    because they re-dispatched through the ask tier (a silent tier conversion). WP1.2 S2.5 replaces
 *    the withholding with the right dispatch: the window re-runs an agent turn's Retry/Edit through
 *    the delegate path and leaves a Branch in Delegate, so this module now gates an agent turn
 *    exactly as an ask turn — by laws 1–3, and by ownership.
 */
import type { Conversation } from '../../state/conversationListStore.js';
import { siblingSessionsAt } from '../../state/conversationListStore.js';
import { EMPTY_PREFIX_SENTINEL } from '../unifiedChatRequest.js';
import {
  sv3TurnByMessageId,
  sv3TurnMessageIds,
  type Sv3SessionHistory,
  type Sv3Turn,
} from './sv3-sessions.js';
import { BRANCH_MENU_BRANCH, BRANCH_MENU_RETRY } from './fixtures.js';

/**
 * The reader resolved a branch act. One seam for all three, the way {@link SV3_CONTEXT_ACTION} is
 * one seam for the five context acts: the region raises intent and the window — which alone holds
 * the conversation list the fork keys are read against — decides what happens.
 */
export const SV3_BRANCH_ACTION = 'sv3-branch-action';

/** The reader paged to another version of a turn. Carries the conversation to claim, not a delta. */
export const SV3_VERSION_SELECT = 'sv3-version-select';

export type Sv3BranchActionId = 'branch' | 'retry' | 'edit';

export interface Sv3BranchAction {
  readonly action: Sv3BranchActionId;
  readonly turnId: string;
  /** The rewritten question, for `edit`. Absent on the other two, which re-send nothing new. */
  readonly text?: string;
}

export interface Sv3VersionSelect {
  readonly sessionId: string;
}

/** Which version of a turn is on screen, and how many there are. Rendered only when `total > 1`. */
export interface Sv3VersionSet {
  /** The conversations that are versions of this turn, base first — {@link siblingSessionsAt}'s order. */
  readonly sessions: readonly string[];
  /** Which of them is on screen. */
  readonly index: number;
}

/** Everything one turn can do about branching, derived once per render for the whole transcript. */
export interface Sv3TurnLineage {
  readonly turnId: string;
  /**
   * What `?fromMsgId=` gets for **Branch to new thread**: this turn's own ANSWER, so the new thread
   * inherits the exchange the reader is standing in and continues past it (slice 513's
   * `branchHere`, `views/UnifiedChatView.ts:5610-5619`).
   */
  readonly branchFromId: string | null;
  /**
   * What `?fromMsgId=` gets for **Edit** and **Retry**: the message the divergence happens AFTER —
   * the PREVIOUS turn's answer, so the re-sent question is the first divergent message
   * (`branchAndResend`, `:1471-1487`). At the head of the conversation there is no preceding
   * message and the value is the empty-prefix sentinel, which is a real id the backend understands
   * (`ConversationStore.EMPTY_PREFIX_SENTINEL`; the Java doc pins its FE producer to this act).
   *
   * `null` means the fork point cannot be named — an INHERITED turn (this conversation is a branch
   * and this turn came from the parent), or a previous turn whose answer is not a store message.
   */
  readonly forkKey: string | null;
  /** This turn's question can be rewritten and re-sent: it names a store message and has a fork key. */
  readonly canEdit: boolean;
  /** The versions of this turn, or `null` when this turn is not a divergence point with more than one. */
  readonly versions: Sv3VersionSet | null;
}

/**
 * The first turn on screen this conversation OWNS — everything before it was inherited from the
 * parent when this conversation was branched, and the reference refuses every transcript control on
 * an inherited turn (`canTurnControl`, `views/UnifiedChatView.ts:1332-1340`): those messages belong
 * to the parent, and re-forking them from the child would fork the wrong conversation.
 *
 * A root conversation owns everything, so the answer is 0.
 *
 * A branch's own answer is "the turn after the one carrying `branchPointMessageId`". Two cases end
 * at 0 rather than at a guess: an empty-prefix fork inherited NOTHING, and a branch point no turn on
 * screen carries means nothing on screen is inherited — the record this window renders is not
 * obliged to include the parent's prefix, and claiming turns as inherited because a *lookup failed*
 * would withhold every control on a conversation that is entirely its own.
 */
export function sv3FirstOwnTurnIndex(
  turns: readonly Sv3Turn[],
  history: Sv3SessionHistory | null,
): number {
  const parent = history?.parentSessionId ?? '';
  const point = history?.branchPointMessageId ?? '';
  if (parent === '' || point === '' || point === EMPTY_PREFIX_SENTINEL) return 0;
  const forkTurn = sv3TurnByMessageId(turns, point);
  if (forkTurn === null) return 0;
  return turns.indexOf(forkTurn) + 1;
}

/**
 * The whole transcript's branch arithmetic, in one pass — the same shape {@link projectSv3TurnContexts}
 * takes and for the same reason: what the window renders, what its menu offers and what it writes are
 * all derived from ONE reading of one record.
 *
 * `conversations` is the already-loaded list from the shared store. The version sets are a pure read
 * over it ({@link siblingSessionsAt}) — no endpoint, because a branch's parent pointers are already
 * on every row `listSessions` returns.
 */
export function projectSv3TurnLineage(
  turns: readonly Sv3Turn[],
  history: Sv3SessionHistory | null,
  sessionId: string,
  conversations: readonly Conversation[],
): readonly Sv3TurnLineage[] {
  const firstOwn = sv3FirstOwnTurnIndex(turns, history);
  return turns.map((turn, index) => {
    const own = index >= firstOwn;
    const { userMsgId, assistantMsgId } = sv3TurnMessageIds(turn);
    const forkKey = own ? forkKeyAt(turns, index, firstOwn, history) : null;
    // LAW 4, REVISED (WP1.2 S2.5, answering 863 §8's open question). 863 §4.A.5 A-8 withheld all
    // three acts on an AGENT turn because they re-dispatched through the ASK tier — offering them was a
    // silent tier conversion. They no longer do: the window re-runs an agent turn's Retry/Edit through
    // the DELEGATE path (`SearchV3View.runBranchAction`), whatever the composer's tier, and Branch
    // leaves the composer in Delegate. So an agent turn now gets the same acts an ask turn does, under
    // the same laws 1–3: a run-plane-only turn still names no store message and offers nothing, and an
    // inherited turn is still the parent's.
    return {
      turnId: turn.id,
      branchFromId: own ? assistantMsgId : null,
      forkKey,
      canEdit: own && userMsgId !== null && forkKey !== null,
      versions: versionsAt(turns, index, firstOwn, history, sessionId, conversations),
    };
  });
}

/**
 * The fork key of turn `index`: the message a divergence at this turn happens after.
 *
 * At the conversation's first OWN turn there is nothing of its own before it, so the key is the
 * branch point it was itself forked at — the sentinel on a root conversation, and the parent's own
 * `branchPointMessageId` on a branch. That is what makes a re-edit of a branch's first question a
 * SIBLING of that branch rather than a branch of it.
 */
function forkKeyAt(
  turns: readonly Sv3Turn[],
  index: number,
  firstOwn: number,
  history: Sv3SessionHistory | null,
): string | null {
  if (index === firstOwn) {
    const parent = history?.parentSessionId ?? '';
    const point = history?.branchPointMessageId ?? '';
    return parent === '' || point === '' ? EMPTY_PREFIX_SENTINEL : point;
  }
  const previous = turns[index - 1];
  return previous === undefined ? null : sv3TurnMessageIds(previous).assistantMsgId;
}

/**
 * The versions of turn `index`, ported from `pagerForTurn` (`views/UnifiedChatView.ts:1508-1541`).
 * Two cases, because a fork is visible from both ends:
 *
 * **A — this conversation IS a branch**, and this is its first own turn: the versions are its
 * parent's fork set, and this conversation is one of them.
 * **B — this conversation is the BASE** of branches that fork at this turn's key: it is version 1 of
 * that set, and the reader can page forward into the others.
 *
 * A first: a conversation that is both a branch and a base would otherwise report its own children's
 * fork rather than the fork it is a member of, which is the set the reader is actually paging.
 */
function versionsAt(
  turns: readonly Sv3Turn[],
  index: number,
  firstOwn: number,
  history: Sv3SessionHistory | null,
  sessionId: string,
  conversations: readonly Conversation[],
): Sv3VersionSet | null {
  if (conversations.length === 0) return null;
  const rows = [...conversations];
  const parent = history?.parentSessionId ?? '';
  const point = history?.branchPointMessageId ?? '';
  if (index === firstOwn && parent !== '' && point !== '') {
    const sessions = siblingSessionsAt(rows, parent, point);
    if (sessions.length > 1) {
      return { sessions, index: Math.max(0, sessions.indexOf(sessionId)) };
    }
  }
  const key = forkKeyAt(turns, index, firstOwn, history);
  if (key === null) return null;
  const sessions = siblingSessionsAt(rows, sessionId, key);
  return sessions.length > 1 ? { sessions, index: 0 } : null;
}

export const sv3LineageFor = (
  lineage: readonly Sv3TurnLineage[],
  turnId: string,
): Sv3TurnLineage | null => lineage.find((l) => l.turnId === turnId) ?? null;

/** One entry of the turn's ⋯ menu, in the same shape S2's context entries take. */
export interface Sv3BranchMenuItem {
  readonly id: Sv3BranchActionId;
  readonly label: string;
  readonly enabled: boolean;
}

export interface Sv3BranchMenuInput {
  /** Something in this conversation is streaming; a fork of a transcript in flight is not a fork of it. */
  readonly streaming: boolean;
}

/**
 * What a turn's ⋯ may offer BESIDE the context acts. An empty list is the honest null again: Edit is
 * not here (it renders inline on the question, the reference's §13.1 split), so this is Retry and
 * Branch, each gated on the id it actually needs rather than on "this turn looks controllable".
 */
export function sv3BranchMenuItems(
  lineage: readonly Sv3TurnLineage[],
  turnId: string,
  input: Sv3BranchMenuInput,
): readonly Sv3BranchMenuItem[] {
  if (input.streaming) return [];
  const entry = sv3LineageFor(lineage, turnId);
  if (entry === null) return [];
  const items: Sv3BranchMenuItem[] = [];
  // Retry needs the fork key AND a question to re-send, which is exactly `canEdit` — retry is an
  // edit that changed nothing.
  if (entry.canEdit) {
    items.push({ id: 'retry', label: BRANCH_MENU_RETRY, enabled: true });
  }
  if (entry.branchFromId !== null) {
    items.push({ id: 'branch', label: BRANCH_MENU_BRANCH, enabled: true });
  }
  return items;
}

/** Is this one of the three acts this module owns? The window's one demultiplex of a merged menu. */
export const isSv3BranchActionId = (id: string): id is Sv3BranchActionId =>
  id === 'branch' || id === 'retry' || id === 'edit';

// @vitest-environment happy-dom

/**
 * The branch arithmetic (slice 513 + tempdoc 610 Phase A/B, ported by 852 S3) — the pure half.
 *
 * Every case here would pass VACUOUSLY before the port (the module did not exist), so what each one
 * is really pinned on is the property named in its own comment. The four that carry the slice:
 *
 *  - **An edit forks from BEFORE the question; a branch forks from AFTER the answer.** They are two
 *    different ids on the same turn, and the wrong one produces a transcript that looks entirely
 *    plausible — an edit that inherited the very exchange it was replacing. Both are asserted, on
 *    the same turn, against each other.
 *  - **The head of a conversation forks at the empty-prefix sentinel**, which is a real id the
 *    backend understands and the only thing `?fromMsgId=` can be given when nothing precedes.
 *  - **A turn that names no STORE message offers nothing** — 852 S1's honest null, applied to the
 *    fork point: an agent turn's ids belong to the run plane, and a fork built on one would 404.
 *  - **The pager sees a fork from both ends** — from the branch (it is version N of its parent's
 *    set) and from the base (it is version 1 of the set its children fork at).
 */
import { describe, it, expect } from 'vitest';
import {
  projectSv3TurnLineage,
  sv3BranchMenuItems,
  sv3FirstOwnTurnIndex,
  sv3LineageFor,
  isSv3BranchActionId,
} from './sv3-branch.js';
import { siblingSessionsAt, type Conversation } from '../../state/conversationListStore.js';
import { EMPTY_PREFIX_SENTINEL } from '../unifiedChatRequest.js';
import { BRANCH_MENU_BRANCH, BRANCH_MENU_RETRY } from './fixtures.js';
import { sv3TurnMessageIds, type Sv3SessionHistory, type Sv3Turn } from './sv3-sessions.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';

const stored = (n: number): string => `11111111-2222-4333-8444-55555555555${n}`;

/** One WIRE thread event, so a record-derived fixture runs through the real shared projection. */
let wireClock = 0;
const wire = (
  id: string,
  kind: ThreadEvent['kind'],
  content: string,
  attributes: Record<string, unknown> = {},
): ThreadEvent => ({
  id,
  occurredAt: new Date(Date.parse('2026-08-25T10:00:00Z') + wireClock++ * 1000).toISOString(),
  kind,
  originator: kind === 'USER_MESSAGE' ? 'user' : 'agent',
  content,
  attributes,
});

const turn = (over: Partial<Sv3Turn> = {}): Sv3Turn => ({
  id: 't1',
  recordId: null,
  assistantRecordId: null,
  recordOpenedByUser: false,
  kind: 'ask',
  question: 'why did the renewal fail?',
  answer: 'The lock held.',
  status: 'complete',
  evidence: null,
  detail: '',
  toolCalls: 0,
  activity: [],
  askedAt: 1,
  standaloneQuestion: '',
  reasoning: [],
  durationMs: null,
  modelLabel: null,
  disposition: null,
  ...over,
});

/** A turn the RECORD reconciled: both halves are ids the conversation store minted. */
const recordTurn = (id: string, userN: number, assistantN: number): Sv3Turn =>
  turn({
    id,
    recordId: stored(userN),
    assistantRecordId: stored(assistantN),
    recordOpenedByUser: true,
  });

/** The two-turn conversation most cases work on: turn 0 = messages 0/1, turn 1 = messages 2/3. */
const TWO_TURNS: readonly Sv3Turn[] = [recordTurn('t0', 0, 1), recordTurn('t1', 2, 3)];

/**
 * THREE turns, and the reason there are three: at two, "the previous turn's answer" and "the FIRST
 * turn's answer" are the same message, so the arithmetic this module exists for is indistinguishable
 * from `turns[0]`. Only a third turn separates them.
 */
const THREE_TURNS: readonly Sv3Turn[] = [
  recordTurn('t0', 0, 1),
  recordTurn('t1', 2, 3),
  recordTurn('t2', 4, 5),
];

const row = (over: Partial<Conversation> & { id: string }): Conversation => ({
  title: null,
  titleSource: null,
  createdAt: 1,
  lastActiveAt: 1,
  messageCount: 2,
  firstUserMessage: '',
  shapeId: 'core.rag-ask',
  storeBacked: true,
  ...over,
});

describe('the fork point of an edit is not the fork point of a branch', () => {
  it('names the PREVIOUS answer for edit/retry and this turn’s OWN answer for branch', () => {
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', []);

    // THE CASE THIS MODULE EXISTS FOR. Both ids are store messages on the same turn and either
    // would be accepted by `?fromMsgId=`; only one of them makes the re-sent question the FIRST
    // divergent message. Forking an edit at `branchFromId` would inherit the exchange being
    // replaced, and the branch would read as the old answer followed by the new question.
    expect(lineage[1]?.forkKey).toBe(stored(1));
    expect(lineage[1]?.branchFromId).toBe(stored(3));
    expect(lineage[1]?.canEdit).toBe(true);
  });

  it('names the IMMEDIATELY preceding answer on a third turn, not the conversation’s first', () => {
    const lineage = projectSv3TurnLineage(THREE_TURNS, null, 'base', []);

    // THE CASE TWO TURNS CANNOT SEE. At two turns, `turns[index - 1]` and `turns[0]` are the same
    // message, so an implementation that always forked at the conversation's FIRST answer would look
    // correct everywhere. Here they are different messages and the fork point is the near one:
    // forking turn 3 at message 1 would silently drop turn 2 out of the branch.
    expect(lineage[2]?.forkKey).toBe(stored(3));
    expect(lineage[2]?.forkKey).not.toBe(lineage[1]?.forkKey);
    expect(lineage[1]?.forkKey).toBe(stored(1));
    // And each turn's own branch point stays its own answer, which walks with it.
    expect(lineage[2]?.branchFromId).toBe(stored(5));
  });

  it('forks the FIRST turn at the empty-prefix sentinel, which is a real id and not a null', () => {
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', []);

    // There is no message before the first question, and `ConversationStore.EMPTY_PREFIX_SENTINEL`
    // is the contract's answer for that — the Java doc pins its FE producer to exactly this act.
    // A `null` here would withhold Edit on the one turn a reader is most likely to rewrite.
    expect(lineage[0]?.forkKey).toBe(EMPTY_PREFIX_SENTINEL);
    expect(lineage[0]?.canEdit).toBe(true);
  });
});

describe('a turn that names no store message offers no fork', () => {
  it('withholds both ids from a LIVE turn, whose handle is positional', () => {
    const live = turn({ id: 'sess#t0', recordId: null, assistantRecordId: null, status: 'streaming' });
    const lineage = projectSv3TurnLineage([live], null, 'base', []);

    // 852 S1's rule: a turn dispatched locally exists before the record knows of it, and its handle
    // is `${sessionId}#t${n}` — not a message id, and nothing `?fromMsgId=` would accept.
    expect(lineage[0]?.branchFromId).toBeNull();
    expect(lineage[0]?.canEdit).toBe(false);
  });

  it('withholds the fork point of a turn whose PREVIOUS answer is a run-plane id', () => {
    const agent = turn({
      id: 'a0',
      recordId: 'run-7:user',
      assistantRecordId: 'conv-1:assistant:1731',
      recordOpenedByUser: true,
      kind: 'agent',
    });
    const lineage = projectSv3TurnLineage([agent, recordTurn('t1', 2, 3)], null, 'base', []);

    // The agent turn itself offers nothing, and — the subtler half — NEITHER DOES THE TURN AFTER IT,
    // because its fork point is the agent turn's answer and that message exists in no store. The
    // honest form of that is no Edit at all, not an Edit that 404s when pressed.
    expect(lineage[0]?.canEdit).toBe(false);
    expect(lineage[0]?.branchFromId).toBeNull();
    expect(lineage[1]?.forkKey).toBeNull();
    expect(lineage[1]?.canEdit).toBe(false);
    // Its own answer is still a real store message, so branching from it is still offered — the two
    // acts are gated on their OWN ids, never on one "is this turn controllable" verdict.
    expect(lineage[1]?.branchFromId).toBe(stored(3));
  });
});

describe('an inherited turn belongs to the parent', () => {
  const BRANCH_HISTORY: Sv3SessionHistory = {
    parentSessionId: 'base',
    branchPointMessageId: stored(1),
  };

  it('finds the first OWN turn after the branch point, and refuses every fork above it', () => {
    // The branch's record carries the inherited prefix, so turn 0 is the parent's.
    expect(sv3FirstOwnTurnIndex(TWO_TURNS, BRANCH_HISTORY)).toBe(1);

    const lineage = projectSv3TurnLineage(TWO_TURNS, BRANCH_HISTORY, 'branch-a', []);
    // Re-forking an inherited turn from the CHILD would fork the wrong conversation — the reference
    // window refuses the same thing for the same reason (`canTurnControl`).
    expect(lineage[0]?.canEdit).toBe(false);
    expect(lineage[0]?.branchFromId).toBeNull();
    expect(lineage[0]?.forkKey).toBeNull();
    // The first own turn forks at the branch point itself, which makes a re-edit of it a SIBLING of
    // this branch rather than a branch of it. Resolving that from `turns[index - 1]` would name the
    // parent's message by luck rather than by contract.
    expect(lineage[1]?.forkKey).toBe(stored(1));
    expect(lineage[1]?.canEdit).toBe(true);
  });

  it('finds a first-own index ABOVE 1 when two turns were inherited', () => {
    const deeper: Sv3SessionHistory = {
      parentSessionId: 'base',
      branchPointMessageId: stored(3),
    };
    // A `+ 1` on the resolved index and a hardcoded 1 agree at one inherited turn. At TWO they do
    // not, and the whole inherited/own boundary rides on this number: it decides which turns get no
    // controls and where the branch's own fork key comes from.
    expect(sv3FirstOwnTurnIndex(THREE_TURNS, deeper)).toBe(2);

    const lineage = projectSv3TurnLineage(THREE_TURNS, deeper, 'branch-a', []);
    expect(lineage[0]?.canEdit).toBe(false);
    expect(lineage[1]?.canEdit).toBe(false);
    expect(lineage[2]?.canEdit).toBe(true);
    // The first OWN turn forks at the branch point, not at `turns[1]`'s answer — which here happen
    // to be the same message, and the next case is where they cannot be.
    expect(lineage[2]?.forkKey).toBe(stored(3));
  });

  it('treats a branch point NO TURN CARRIES as "nothing on screen is inherited"', () => {
    const unresolvable: Sv3SessionHistory = {
      parentSessionId: 'base',
      branchPointMessageId: stored(9),
    };
    // The record this window renders is not obliged to include the parent's prefix. Reading a failed
    // LOOKUP as "everything is inherited" would withhold every control on a conversation that is
    // entirely its own — the wrong way to fail, and invisible.
    expect(sv3FirstOwnTurnIndex(TWO_TURNS, unresolvable)).toBe(0);
  });

  it('treats an EMPTY-PREFIX branch as owning everything', () => {
    const empty: Sv3SessionHistory = {
      parentSessionId: 'base',
      branchPointMessageId: EMPTY_PREFIX_SENTINEL,
    };
    // An empty-prefix fork inherited nothing, so its first turn is its own — and forks at the
    // sentinel again, which is what makes two edits of the same opening question siblings.
    expect(sv3FirstOwnTurnIndex(TWO_TURNS, empty)).toBe(0);
    const lineage = projectSv3TurnLineage(TWO_TURNS, empty, 'branch-a', []);
    expect(lineage[0]?.forkKey).toBe(EMPTY_PREFIX_SENTINEL);
  });
});

describe('the version pager sees a fork from both ends', () => {
  const BRANCHES: Conversation[] = [
    row({ id: 'base' }),
    row({ id: 'branch-a', parentSessionId: 'base', branchPointMessageId: stored(1), createdAt: 2 }),
    row({ id: 'branch-b', parentSessionId: 'base', branchPointMessageId: stored(1), createdAt: 3 }),
  ];

  it('reports the BASE as version 1 of the set its children fork at', () => {
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', BRANCHES);

    // Turn 1's fork key is message 1, which is exactly what both branches forked at — so from the
    // base, turn 1 is the first of three versions.
    expect(lineage[1]?.versions).toEqual({
      sessions: ['base', 'branch-a', 'branch-b'],
      index: 0,
    });
    // And turn 0 is not a divergence point at all: nothing forked at the sentinel.
    expect(lineage[0]?.versions).toBeNull();
  });

  it('reports a BRANCH as its own position in its parent’s set', () => {
    const history: Sv3SessionHistory = {
      parentSessionId: 'base',
      branchPointMessageId: stored(1),
    };
    const lineage = projectSv3TurnLineage(TWO_TURNS, history, 'branch-b', BRANCHES);

    // Case A: the pager sits on the branch's FIRST OWN turn and says which version this is. Ordered
    // by creation time by `siblingSessionsAt`, so paging is stable across reloads.
    expect(lineage[1]?.versions).toEqual({
      sessions: ['base', 'branch-a', 'branch-b'],
      index: 2,
    });
  });

  it('reports a conversation that is BOTH a branch and a base as its own position, not its children’s', () => {
    // The grandchild shape, and the only one where the two cases collide. `branch-a` is a branch of
    // `base`, and `grandchild` was forked from `branch-a` AT THE SAME POINT — which is what a re-edit
    // of a branch's first question produces, so this is the ordinary shape, not a contrived one.
    const lineage3: Conversation[] = [
      row({ id: 'base' }),
      row({ id: 'branch-a', parentSessionId: 'base', branchPointMessageId: stored(1), createdAt: 2 }),
      row({ id: 'branch-b', parentSessionId: 'base', branchPointMessageId: stored(1), createdAt: 3 }),
      // Forked FROM branch-a, at branch-a's own branch point.
      row({
        id: 'grandchild',
        parentSessionId: 'branch-a',
        branchPointMessageId: stored(1),
        createdAt: 4,
      }),
    ];
    const history: Sv3SessionHistory = {
      parentSessionId: 'base',
      branchPointMessageId: stored(1),
    };
    const lineage = projectSv3TurnLineage(TWO_TURNS, history, 'branch-a', lineage3);

    // CASE A WINS. Both cases match this turn: A says "you are version 2 of base's fork", B says
    // "you are version 1 of your own children's fork". The set the reader is standing IN is A's —
    // B's answer would page them among their own descendants and, worse, report index 0, so the
    // pager would claim this is the first version of something it is not a member of.
    expect(lineage[1]?.versions).toEqual({
      sessions: ['base', 'branch-a', 'branch-b'],
      index: 1,
    });
    // Both candidate sets really are non-trivial here, which is what makes the precedence load-bearing
    // rather than incidental: B's set exists and has two members.
    expect(siblingSessionsAt(lineage3, 'branch-a', stored(1))).toEqual(['branch-a', 'grandchild']);
  });

  it('renders no pager on a root conversation that nothing forked from', () => {
    // THE ROOT EDGE. A conversation with no parent and no children is one version of itself, and a
    // "1 / 1" would be a control that says nothing and moves nowhere.
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', [row({ id: 'base' })]);
    expect(lineage.every((l) => l.versions === null)).toBe(true);
  });

  it('does not mistake ANOTHER turn’s fork for this one’s', () => {
    const atFirstTurn: Conversation[] = [
      row({ id: 'base' }),
      row({
        id: 'branch-a',
        parentSessionId: 'base',
        branchPointMessageId: EMPTY_PREFIX_SENTINEL,
        createdAt: 2,
      }),
    ];
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', atFirstTurn);
    // The fork is at the head, so the pager belongs on turn 0 and nowhere else. A pager keyed on
    // "this conversation has branches" rather than on the fork's own message would put it on both.
    expect(lineage[0]?.versions?.sessions).toEqual(['base', 'branch-a']);
    expect(lineage[1]?.versions).toBeNull();
  });
});

describe('the ⋯ entries a turn may offer', () => {
  it('offers Retry and Branch on a settled record turn', () => {
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', []);
    expect(sv3BranchMenuItems(lineage, 't1', { streaming: false })).toEqual([
      { id: 'retry', label: BRANCH_MENU_RETRY, enabled: true },
      { id: 'branch', label: BRANCH_MENU_BRANCH, enabled: true },
    ]);
  });

  it('offers NOTHING while the window is streaming', () => {
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', []);
    // A fork of a transcript with a prompt in flight is not a fork of it — the same window-wide gate
    // S2's context entries take (its F2 finding), applied to the acts that copy the transcript.
    expect(sv3BranchMenuItems(lineage, 't1', { streaming: true })).toEqual([]);
  });

  it('offers only Branch on a turn whose fork point cannot be named', () => {
    const agent = turn({ id: 'a0', recordId: 'run-7:user', kind: 'agent', recordOpenedByUser: true });
    const lineage = projectSv3TurnLineage([agent, recordTurn('t1', 2, 3)], null, 'base', []);
    // Turn 1 can still be branched FROM (its own answer is a store message) but cannot be retried
    // (the message before it is not). Two entries gated on two ids, not one verdict on the turn.
    expect(sv3BranchMenuItems(lineage, 't1', { streaming: false })).toEqual([
      { id: 'branch', label: BRANCH_MENU_BRANCH, enabled: true },
    ]);
  });

  it('offers nothing for a turn the projection does not know', () => {
    expect(sv3BranchMenuItems([], 'nope', { streaming: false })).toEqual([]);
    expect(sv3LineageFor([], 'nope')).toBeNull();
  });
});

describe('the menu demultiplex', () => {
  it('claims the three branch ids and no context id', () => {
    // The window merges two menus into one primitive and routes the answer by this predicate; a
    // context id leaking into the branch handler would fork the conversation on "Compact up to here".
    expect(['branch', 'retry', 'edit'].every(isSv3BranchActionId)).toBe(true);
    expect(
      ['floor', 'compact', 'exclude', 'include', 'restore', 'summary', 'include-all', 'inspect'].some(
        isSv3BranchActionId,
      ),
    ).toBe(false);
  });
});

describe('the KIND a re-run reads comes from the RECORD, not from what the turn happened to do', () => {
  /**
   * Tempdoc 863 §4.A.5 F1 — the case every hand-built fixture in this file dodges by construction.
   *
   * A delegate run that calls NO tool records no tool calls and no notes, and since slice A
   * suppresses its run-plane events it contributes none of those either. The kind derivation used to
   * be `activity.some(tool|note)`, so this turn — a real agent run — projected as `kind: 'ask'` and
   * law 4 never fired: Edit, Retry and Branch appeared on it, pointed at real store ids, and
   * re-dispatched it through the ask tier. That is the silent tier conversion A-8 exists to refuse,
   * arrived at through the one shape of delegate run the inference cannot see.
   *
   * The fix is the record declaring the shape that dispatched the turn, so this fixture carries NO
   * activity at all and the kind is still `agent` — which since WP1.2 S2.5 is what makes the window
   * re-run it as a Delegate (the acts are offered; their tier is the turn's). Built from WIRE events through the real projector,
   * because a fixture that set `kind` by hand would assert nothing about the derivation.
   */
  const AGENT_SHAPE = { shapeId: 'core.agent-run' };
  const ASK_SHAPE = { shapeId: 'core.rag-ask' };
  const toolLessDelegateThenAsk = (): readonly Sv3Turn[] =>
    projectSv3RecordTurns([
      wire(stored(0), 'USER_MESSAGE', 'delegate this', AGENT_SHAPE),
      wire(stored(1), 'ASSISTANT_MESSAGE', 'I answered without calling a tool.', AGENT_SHAPE),
      wire(stored(2), 'USER_MESSAGE', 'and the second one?', ASK_SHAPE),
      wire(stored(3), 'ASSISTANT_MESSAGE', 'The same lock.', ASK_SHAPE),
    ]);

  it('reads a TOOL-LESS delegate turn as an agent turn, and offers it the acts on its store ids', () => {
    const turns = toolLessDelegateThenAsk();

    // The precondition that made this invisible: nothing in the turn's activity says "agent".
    expect(turns[0]?.activity.filter((e) => e.kind === 'tool' || e.kind === 'note')).toEqual([]);
    // And the record says it anyway — which is now what makes its Retry/Edit run the AGENT again
    // (WP1.2 S2.5, `SearchV3View.runBranchAction`), where it used to be what withheld them.
    expect(turns[0]?.kind).toBe('agent');

    const lineage = projectSv3TurnLineage(turns, null, 'uc-1', []);
    expect(lineage[0]?.canEdit).toBe(true);
    expect(lineage[0]?.branchFromId).toBe(stored(1));
    expect(lineage[0]?.forkKey).toBe(EMPTY_PREFIX_SENTINEL);
  });

  it('reads the ask turn after it as an ask turn, which keeps its own affordances', () => {
    const turns = toolLessDelegateThenAsk();
    expect(turns[1]?.kind).toBe('ask');

    const lineage = projectSv3TurnLineage(turns, null, 'uc-1', []);
    // A-8.2: the fork point is the delegate turn's own answer, which is now a real store message.
    expect(lineage[1]?.canEdit).toBe(true);
    expect(lineage[1]?.forkKey).toBe(stored(1));
  });

  it('falls back to the activity inference only for a record that cannot say', () => {
    // A pre-863 record (or a run-plane-only turn): no `shapeId` anywhere, so the inference runs and
    // reads the tool call as the agent signal it has always read it as. Keeping this green is what
    // makes the change forward-only rather than a re-derivation of every old turn.
    const turns = projectSv3RecordTurns([
      wire('run-7:user', 'USER_MESSAGE', 'delegate this'),
      wire('c1:completed', 'TOOL_ACTIVITY', '', {
        callId: 'c1',
        toolName: 'core_search_index',
        status: 'completed',
      }),
      wire('uc-1:assistant:1731', 'ASSISTANT_MESSAGE', 'done'),
    ]);
    expect(turns[0]?.kind).toBe('agent');
  });
});

describe('an agent turn is gated like an ask turn — by its ids, not its KIND (WP1.2 S2.5)', () => {
  /**
   * Turn 0 is a turn 863 slice A makes possible: an agent-tier run the CONVERSATION STORE
   * reconciled, so both its halves are real store ids. 863 §4.A.5 A-8 withheld all three acts on it
   * by kind, because they re-dispatched through the ask tier; the window now re-runs it as a
   * Delegate, so laws 1–3 alone decide. Turn 1 is an ordinary ask turn, also fully store-backed.
   */
  const AGENT_THEN_ASK: readonly Sv3Turn[] = [
    { ...recordTurn('t0', 0, 1), kind: 'agent' },
    recordTurn('t1', 2, 3),
  ];

  it('an agent turn with fully valid STORE ids offers what the same ask turn offers', () => {
    const lineage = projectSv3TurnLineage(AGENT_THEN_ASK, null, 'base', []);

    const ids = sv3TurnMessageIds(AGENT_THEN_ASK[0] as Sv3Turn);
    expect(ids.userMsgId).not.toBeNull();
    expect(ids.assistantMsgId).not.toBeNull();

    expect(lineage[0]?.canEdit).toBe(true);
    expect(lineage[0]?.branchFromId).toBe(stored(1));
    expect(sv3BranchMenuItems(lineage, 't0', { streaming: false })).toEqual([
      { id: 'retry', label: BRANCH_MENU_RETRY, enabled: true },
      { id: 'branch', label: BRANCH_MENU_BRANCH, enabled: true },
    ]);
  });

  it('the IDENTICAL turn with kind "ask" offers both — the control proving case 1 is about kind', () => {
    // TWO_TURNS is exactly AGENT_THEN_ASK's fixture with turn 0's kind left at its default ('ask').
    const lineage = projectSv3TurnLineage(TWO_TURNS, null, 'base', []);

    expect(lineage[0]?.canEdit).toBe(true);
    expect(lineage[0]?.branchFromId).toBe(stored(1));
    expect(sv3BranchMenuItems(lineage, 't0', { streaming: false })).toEqual([
      { id: 'retry', label: BRANCH_MENU_RETRY, enabled: true },
      { id: 'branch', label: BRANCH_MENU_BRANCH, enabled: true },
    ]);
  });

  it('the ask turn AFTER a delegate turn gains Edit (863 §4.A.5 A-8.2)', () => {
    const lineage = projectSv3TurnLineage(AGENT_THEN_ASK, null, 'base', []);

    // Before 863, the predecessor's answer was a run-plane id and `forkKeyAt` (unchanged by this
    // slice) named it, so law 1 withheld Edit here too. Now the delegate turn's answer is a real
    // store message, so the fork point resolves and this ordinary ask turn legitimately gains Edit —
    // the deliberate asymmetry: withholding is about what re-dispatching THIS turn would do, not
    // about the messages around it.
    expect(lineage[1]?.canEdit).toBe(true);
    expect(lineage[1]?.forkKey).toBe(stored(1));
  });
});

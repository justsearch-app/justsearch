// @vitest-environment happy-dom

/**
 * The effective-context derivation (tempdoc 610, ported by 852 S2) — the pure half.
 *
 * Every case here would pass VACUOUSLY before the port (the module did not exist), so what each one
 * is really pinned on is the property named in its own comment. The three that carry the slice:
 *
 *  - **A turn that names no STORE message offers nothing.** The honest null 852 S1 built: an agent
 *    turn's ids belong to the run plane and a live turn's handle is positional, so neither can be a
 *    floor or an exclusion — and the affordance is withheld rather than offered and refused.
 *  - **The floor resolves BY ID.** The record's turns and `/history`'s messages count different
 *    things, so the index answer is constructed here alongside the id answer and asserted against.
 *  - **The inspector agrees with the transcript.** A turn drawn as out-of-context or hidden is a
 *    turn the inspector does not list — one derivation, two consumers.
 */
import { describe, it, expect } from 'vitest';
import {
  projectSv3ContextInspector,
  projectSv3TurnContexts,
  readSv3ContextUsage,
  sv3ContextMenuItems,
  sv3ExcludedMessageIds,
  sv3ExcludedTurnCount,
  sv3FollowUpContext,
} from './sv3-context.js';
import type { Sv3SessionHistory, Sv3Turn } from './sv3-sessions.js';

const stored = (n: number): string => `11111111-2222-4333-8444-55555555555${n}`;

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

const MENU_INPUT = {
  compacting: false,
  streaming: false,
  contextFloor: null,
  hasSummary: false,
};

describe('what the effective context does with each turn', () => {
  it('resolves the floor BY ID and dims everything above it', () => {
    const turns = [recordTurn('a', 0, 1), recordTurn('b', 3, 4), recordTurn('c', 6, 7)];
    const history: Sv3SessionHistory = { contextFloor: stored(3) };
    const contexts = projectSv3TurnContexts(turns, history);
    expect(contexts.map((c) => c.isFloor)).toEqual([false, true, false]);
    expect(contexts.map((c) => c.outOfContext)).toEqual([true, false, false]);
    // The INDEX answer, constructed so the id answer is not merely the same number by luck: the
    // floor message is at position 2 of `/history`'s message array, which is turn THREE's slot if
    // anything ever paired the two by position.
    expect(turns.indexOf(turns[1] as Sv3Turn)).toBe(1);
  });

  it('finds the floor from an ASSISTANT message id too — it names a message, not a turn', () => {
    const turns = [recordTurn('a', 0, 1), recordTurn('b', 3, 4)];
    const contexts = projectSv3TurnContexts(turns, { contextFloor: stored(4) });
    expect(contexts[1]?.isFloor).toBe(true);
    expect(contexts[0]?.outOfContext).toBe(true);
  });

  it('keeps a turn saved after the last /history read in context, below a floor not shown here', () => {
    // The floor sits on a message no turn shows, the last one the read holds; turn c was answered
    // and saved after that read, so its ids are newer than anything the read lists.
    const turns = [recordTurn('a', 0, 1), recordTurn('b', 3, 4), recordTurn('c', 6, 7)];
    const history: Sv3SessionHistory = {
      contextFloor: stored(5),
      messageIds: [stored(0), stored(1), stored(3), stored(4), stored(5)],
    };
    const contexts = projectSv3TurnContexts(turns, history);
    expect(contexts.map((c) => c.outOfContext)).toEqual([true, true, false]);
  });

  it('reports NO floor and NO exclusions for a conversation whose history was never read', () => {
    const contexts = projectSv3TurnContexts([recordTurn('a', 0, 1)], null);
    expect(contexts[0]).toMatchObject({ isFloor: false, outOfContext: false, hasExcluded: false });
  });

  it('counts a turn as hidden when EITHER of its messages is excluded', () => {
    const turns = [recordTurn('a', 0, 1), recordTurn('b', 3, 4)];
    // Only the answer is hidden — a half-excluded turn is still a turn the prompt is missing part
    // of, and reporting it as fully present would be the more comfortable lie.
    const history = { excludedMessageIds: [stored(4)] };
    const contexts = projectSv3TurnContexts(turns, history);
    expect(contexts.map((c) => c.hasExcluded)).toEqual([false, true]);
    expect(sv3ExcludedTurnCount(contexts)).toBe(1);
    expect(sv3ExcludedMessageIds(history)).toEqual([stored(4)]);
  });
});

describe('preserved follow-up message semantics', () => {
  it('sends summary then individual nonblank messages in mixed-mode order, omitting unsuccessful Structured exchanges', () => {
    const turns = [
      recordTurn('ask', 0, 1),
      turn({ id: 'model', kind: 'model', shapeId: 'core.free-chat', question: 'Model question', answer: 'Model answer' }),
      turn({ id: 'refused', shapeId: 'core.extract', question: 'Refused extraction', answer: '', status: 'failed' }),
      turn({ id: 'structured', shapeId: 'core.extract', question: 'Accepted extraction', answer: '{"saved":true}' }),
      turn({ id: 'blank', question: '  ', answer: '\n' }),
      turn({ ...recordTurn('combined', 3, 4), question: 'Combined question', answerMessages: [
        { id: stored(4), content: 'First saved answer' },
        { id: stored(5), content: ' ' },
        { id: stored(6), content: 'Last saved answer' },
      ] }),
    ];
    const history = { contextFloorSummary: 'Saved summary' };
    expect(sv3FollowUpContext(turns, projectSv3TurnContexts(turns, history), history)).toEqual([
      { role: 'system', content: 'Summary of earlier conversation:\nSaved summary' },
      { role: 'user', content: 'why did the renewal fail?' },
      { role: 'assistant', content: 'The lock held.' },
      { role: 'user', content: 'Model question' },
      { role: 'assistant', content: 'Model answer' },
      { role: 'user', content: 'Accepted extraction' },
      { role: 'assistant', content: '{"saved":true}' },
      { role: 'user', content: 'Combined question' },
      { role: 'assistant', content: 'First saved answer' },
      { role: 'assistant', content: 'Last saved answer' },
    ]);
  });

  it('fails open for an absent floor without putting a divider on a neighbouring turn', () => {
    const turns = [recordTurn('a', 0, 1), recordTurn('b', 3, 4)];
    const history = { contextFloor: stored(9), messageIds: [stored(0), stored(1), stored(3), stored(4)] };
    const contexts = projectSv3TurnContexts(turns, history);
    expect(contexts.map(c => [c.isFloor, c.outOfContext])).toEqual([[false, false], [false, false]]);
    expect(sv3FollowUpContext(turns, contexts, history)).toHaveLength(4);
  });

  it('uses the saved assistant ID as the floor target when a turn has no saved question', () => {
    const turns = [turn({ ...recordTurn('assistant-only', 0, 1), recordOpenedByUser: false })];
    expect(projectSv3TurnContexts(turns, null)[0]?.floorMessageId).toBe(stored(1));
  });
});

describe('the menu offers only what the turn can actually address', () => {
  it('offers NOTHING for a turn that names no store message (agent + live turns)', () => {
    // The run plane's own ids (`${runId}:user`, `${conversationId}:assistant:${stamp}`) and a live
    // turn's positional handle are both rejected by `sv3TurnMessageIds`, so both arrive here with an
    // empty id list — and an empty menu means the region renders no trigger at all.
    const agent = turn({ id: 'r1', recordId: 'run-7:user', assistantRecordId: 'uc-1:assistant:9', recordOpenedByUser: true, kind: 'agent' });
    const live = turn({ id: 'uc-1#t2', recordId: null, assistantRecordId: null, status: 'streaming' });
    const contexts = projectSv3TurnContexts([agent, live], null);
    expect(contexts.map((c) => c.messageIds.length)).toEqual([0, 0]);
    expect(sv3ContextMenuItems(contexts, 'r1', MENU_INPUT)).toEqual([]);
    expect(sv3ContextMenuItems(contexts, 'uc-1#t2', MENU_INPUT)).toEqual([]);
  });

  it('offers no compaction on the FIRST turn — there is nothing above it to summarize', () => {
    const contexts = projectSv3TurnContexts([recordTurn('a', 0, 1), recordTurn('b', 3, 4)], null);
    expect(sv3ContextMenuItems(contexts, 'a', MENU_INPUT).map((i) => i.id)).toEqual([
      'floor',
      'exclude',
    ]);
    expect(sv3ContextMenuItems(contexts, 'b', MENU_INPUT).map((i) => i.id)).toEqual([
      'floor',
      'compact',
      'exclude',
    ]);
  });

  it('disables re-flooring the turn that IS the floor — unless a summary would be dropped', () => {
    const turns = [recordTurn('a', 0, 1), recordTurn('b', 3, 4)];
    const contexts = projectSv3TurnContexts(turns, { contextFloor: stored(3) });
    const plain = sv3ContextMenuItems(contexts, 'b', { ...MENU_INPUT, contextFloor: stored(3) });
    expect(plain.find((i) => i.id === 'floor')?.enabled).toBe(false);
    // With a summary standing, "reset to here" still DOES something: it drops the summary.
    const compacted = sv3ContextMenuItems(contexts, 'b', {
      ...MENU_INPUT,
      contextFloor: stored(3),
      hasSummary: true,
    });
    expect(compacted.find((i) => i.id === 'floor')?.enabled).toBe(true);
  });

  it('withholds a second compaction while one is running, and everything while streaming', () => {
    const contexts = projectSv3TurnContexts([recordTurn('a', 0, 1), recordTurn('b', 3, 4)], null);
    expect(
      sv3ContextMenuItems(contexts, 'b', { ...MENU_INPUT, compacting: true }).find(
        (i) => i.id === 'compact',
      )?.enabled,
    ).toBe(false);
    expect(sv3ContextMenuItems(contexts, 'b', { ...MENU_INPUT, streaming: true })).toEqual([]);
  });

  it('flips exclude ↔ include on what the ledger says, not on what was last pressed', () => {
    const turns = [recordTurn('a', 0, 1)];
    const included = projectSv3TurnContexts(turns, null);
    const excluded = projectSv3TurnContexts(turns, { excludedMessageIds: [stored(0), stored(1)] });
    expect(sv3ContextMenuItems(included, 'a', MENU_INPUT).map((i) => i.id)).toContain('exclude');
    expect(sv3ContextMenuItems(excluded, 'a', MENU_INPUT).map((i) => i.id)).toContain('include');
  });
});

describe('the inspector renders the prompt the transcript claims', () => {
  it('drops out-of-context and hidden turns, and leads with the standing summary', () => {
    const turns = [
      recordTurn('a', 0, 1),
      { ...recordTurn('b', 3, 4), question: 'and the second?', answer: 'The same lock.' },
      { ...recordTurn('c', 6, 7), question: 'and the third?', answer: 'Also the lock.' },
    ];
    const history: Sv3SessionHistory = {
      contextFloor: stored(3),
      contextFloorSummary: 'Everything above was compacted.',
      // The whole turn, as the turn menu's Exclude writes it. (One hidden ANSWER leaves its question
      // in the prompt, and so in the inspector — SearchV3View.context.test.ts pins that case.)
      excludedMessageIds: [stored(6), stored(7)],
    };
    const contexts = projectSv3TurnContexts(turns, history);
    const view = projectSv3ContextInspector(
      turns,
      contexts,
      history,
      { promptTokens: 900, breakdown: { system: 100, conversation: 700, retrieved: 100 } },
      4096,
    );
    const conversation = view.phases.find((p) => p.name === 'Conversation');
    // Turn a is above the floor and turn c is hidden: neither is in the prompt, so neither is here.
    expect(conversation?.segments.map((s) => s.text)).toEqual([
      'Everything above was compacted.',
      'and the second?',
      'The same lock.',
    ]);
    expect(view.totalTokens).toBe(900);
    expect(view.systemTokens).toBe(100);
    expect(view.windowTokens).toBe(4096);
    // The per-segment tokens stay null: only the phase totals were ever estimated.
    expect(conversation?.segments.every((s) => s.tokens === null)).toBe(true);
  });

  it('reports what it was NOT told as null rather than as zero', () => {
    const turns = [recordTurn('a', 0, 1)];
    const view = projectSv3ContextInspector(turns, projectSv3TurnContexts(turns, null), null, null, null);
    expect(view.totalTokens).toBeNull();
    expect(view.systemTokens).toBeNull();
    expect(view.windowTokens).toBeNull();
    expect(view.phases.map((p) => p.tokens)).toEqual([null, null]);
  });
});

describe('the terminal’s own occupancy report', () => {
  it('reads promptTokens and the estimated split off the done payload', () => {
    expect(
      readSv3ContextUsage({
        promptTokens: 1200,
        contextBreakdown: { system: 200, conversation: 800, retrieved: 200 },
      }),
    ).toEqual({
      promptTokens: 1200,
      breakdown: { system: 200, conversation: 800, retrieved: 200 },
    });
  });

  it('is null when the terminal reported no occupancy, and split-less when it reported no split', () => {
    // A zero is not a measurement: the meter is omitted rather than drawn confidently at 0%.
    expect(readSv3ContextUsage({ promptTokens: 0 })).toBeNull();
    expect(readSv3ContextUsage({})).toBeNull();
    expect(readSv3ContextUsage(null)).toBeNull();
    expect(readSv3ContextUsage({ promptTokens: 90 })).toEqual({ promptTokens: 90, breakdown: null });
    // A partial split is not a split — an over-estimate missing a phase would mis-attribute it.
    expect(
      readSv3ContextUsage({ promptTokens: 90, contextBreakdown: { system: 10, conversation: 20 } }),
    ).toEqual({ promptTokens: 90, breakdown: null });
  });
});

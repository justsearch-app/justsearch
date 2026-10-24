/**
 * Tempdoc 859 §5a / Reach 1 — the record reader discriminates the PLANE, and never casts.
 *
 * `attributes.citations` carries two incompatible shapes: retrieval SOURCES on the answer plane
 * (`InteractionThreadController.chatTurn`) and per-sentence CITES on the action plane
 * (`AgentInteractionMapper`), whose sources live under a separate `sources` key. Reading the one key
 * without a discriminator is a silent reinterpretation, and a cast yields a confident wrong number
 * rather than an error — which is what a delegate turn's Sources affordance reported to the reader
 * (through its accessible name and the panel), never as a visible crash.
 *
 * No DOM: the projection is pure. The fixtures are real WIRE events, so every case runs through the
 * SHARED `projectUnifiedThread` on its way in, exactly as the sibling `sv3-record.test.ts` does.
 */
import { describe, it, expect } from 'vitest';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import {
  applySv3Record,
  latestTurnRef,
  settleAgentTurn,
  SV3_SESSIONS_EMPTY,
  submitInSession,
} from './sv3-sessions.js';
import { sourceGrounding, sourceGroundingLabel } from '../../components/chat/evidenceProjection.js';

let clock = 0;
const at = (): string => new Date(Date.parse('2026-08-13T10:00:00Z') + clock++ * 1000).toISOString();

const event = (
  id: string,
  kind: ThreadEvent['kind'],
  content: string,
  attributes: Record<string, unknown> = {},
): ThreadEvent => ({ id, occurredAt: at(), kind, originator: 'agent', content, attributes });

/** What `AgentInteractionMapper` writes onto a delegate run's persisted assistant message. */
const AGENT_SOURCES = [
  {
    parentDocId: 'docs/runbook.md',
    chunkIndex: 7,
    path: 'f:/docs/runbook.md',
    title: 'Runbook',
    excerpt: 'the first passage',
    startLine: 3,
    endLine: 9,
    headingText: 'Setup',
  },
  {
    parentDocId: 'docs/postmortem.md',
    chunkIndex: 1,
    path: 'f:/docs/postmortem.md',
    title: 'Postmortem',
    excerpt: 'the second passage',
    startLine: 40,
    endLine: 52,
    headingText: 'Cause',
  },
  {
    parentDocId: 'docs/ledger.md',
    chunkIndex: 0,
    path: 'f:/docs/ledger.md',
    title: 'Ledger',
    excerpt: 'the third passage',
    startLine: 1,
    endLine: 8,
    headingText: '',
  },
];

/** ONE sentence cite over THREE sources — the asymmetry the cast collapsed into a wrong count. */
const AGENT_CITES = [{ sentenceText: 'The retry succeeded.', sourceIndex: 1, similarity: 0.88 }];

const agentAssistant = (attributes: Record<string, unknown> = {}): ThreadEvent =>
  event('a1', 'ASSISTANT_MESSAGE', 'The retry succeeded.', {
    sources: AGENT_SOURCES,
    citations: AGENT_CITES,
    ...attributes,
  });

describe('T5 — an ACTION-plane record projects the real sources, not the cites', () => {
  it('reports 3 sources and their real parentDocIds, not 1 fabricated from the cite list', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('t1', 'TOOL_ACTIVITY', 'core_search', { callId: 'c1', toolName: 'core_search' }),
      agentAssistant({ citationScorer: 'CROSS_ENCODER' }),
    ]);
    const evidence = turn!.evidence;
    // THE assertion that fails before 859: the cast made this 1 (the cite count) and each "source"
    // a sentence-cite object with no `parentDocId` at all.
    expect(evidence?.sources).toHaveLength(3);
    expect(evidence?.sources.map((s) => s.parentDocId)).toEqual([
      'docs/runbook.md',
      'docs/postmortem.md',
      'docs/ledger.md',
    ]);
    expect(evidence?.sources.map((s) => s.startLine)).toEqual([3, 40, 1]);
  });

  it('projects the matches, so the panel says "Grounds 1 sentence" and not "Retrieved · not cited"', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([agentAssistant({ citationScorer: 'CROSS_ENCODER' })]);
    const evidence = turn!.evidence!;
    expect(evidence.matches).toHaveLength(1);
    const cited = sourceGrounding(1, evidence.matches, 'docs/postmortem.md');
    expect(sourceGroundingLabel(cited)).toBe('Grounds 1 sentence');
    // A source the matcher genuinely did NOT cite still reads honestly — the projection reports the
    // matcher, it does not flatter it.
    const uncited = sourceGrounding(0, evidence.matches, 'docs/runbook.md');
    expect(sourceGroundingLabel(uncited)).toBe('Retrieved · not cited');
    // ...and the mark exists, on the cited source's 1-based position.
    expect(evidence.marks).toHaveLength(1);
    expect(evidence.marks[0]!.label).toBe(2);
  });

  it('carries the record\u2019s producer stamp into the gate: a cosine record gets sources, no marks', () => {
    clock = 0;
    const [cosine] = projectSv3RecordTurns([agentAssistant({ citationScorer: 'EMBEDDING_COSINE' })]);
    expect(cosine!.evidence?.sources).toHaveLength(3);
    expect(cosine!.evidence?.marks).toEqual([]);
    clock = 0;
    // A PRE-STAMP record (written before 859) carries no key, and keeps its marks.
    const [preStamp] = projectSv3RecordTurns([agentAssistant()]);
    expect(preStamp!.evidence?.marks).toHaveLength(1);
  });
});

describe('T6 — the ANSWER plane is untouched by the discrimination', () => {
  const RAG_SOURCES = [
    {
      parentDocId: 'docs/contract.md',
      chunkIndex: 2,
      chunkTotal: 9,
      startChar: 120,
      endChar: 400,
      score: 0.83,
      excerpt: 'the clause',
      startLine: 10,
      endLine: 18,
      headingText: 'Renewal',
      headingLevel: 2,
    },
  ];

  it('distinguishes an unfinished Ask check from zero matches and historical missing facts on cold load', () => {
    const read = (claimMatches: unknown) => projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'Who owns Cobalt?'),
      event('a1', 'ASSISTANT_MESSAGE', 'Ada owns Cobalt.', {
        citations: RAG_SOURCES, ...(claimMatches === undefined ? {} : { claimMatches }),
      }),
    ])[0]!.evidence!;
    const incomplete = read({ matches: [], groundingIncomplete: true });
    expect(incomplete.groundingIncomplete).toBe(true);
    expect(incomplete.sentencesTotal).toBeUndefined();
    const state = sourceGrounding(0, incomplete.matches, undefined, undefined, incomplete.groundingIncomplete);
    expect(state.tier).toBeNull();
    expect(sourceGroundingLabel(state)).toBe('Retrieved · grounding check did not complete');
    expect(read({ matches: [], sentencesTotal: 1, sentencesScored: 1 }).groundingIncomplete).toBeUndefined();
    expect(read(undefined).groundingIncomplete).toBeUndefined();
  });

  it('reads `citations` + `claimMatches` exactly as before when there is no `sources` key', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'why did the renewal fail?'),
      event('a1', 'ASSISTANT_MESSAGE', 'The lock held.', {
        citations: RAG_SOURCES,
        claimMatches: {
          scorer: 'CROSS_ENCODER',
          matches: [
            {
              sentenceIndex: 0,
              sentenceText: 'The lock held.',
              sourceIndex: 0,
              similarity: 0.9,
              parentDocId: 'docs/contract.md',
            },
          ],
        },
      }),
    ]);
    const evidence = turn!.evidence!;
    expect(evidence.sources).toHaveLength(1);
    // The retrieval facts survive the supertype — this plane's producer reports them and they are
    // carried, not dropped.
    expect(evidence.sources[0]!.startChar).toBe(120);
    expect(evidence.sources[0]!.score).toBe(0.83);
    expect(evidence.matches).toHaveLength(1);
    expect(evidence.marks).toHaveLength(1);
  });

  it('still returns null when the record carried NEITHER attribute — "never told" is not "told zero"', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'anything?'),
      event('a1', 'ASSISTANT_MESSAGE', 'No idea.'),
    ]);
    expect(turn!.evidence).toBeNull();
  });
});

/** The window's own terminal: the delegate turn settles into its receipt before the record lands. */
function settled(list: ReturnType<typeof submitInSession>): ReturnType<typeof submitInSession> {
  const ref = latestTurnRef(list);
  if (ref === null) throw new Error('no turn to settle');
  return settleAgentTurn(list, ref, 'complete', 1, 2000);
}

describe('T11 — a record turn NO user item opened reconciles onto the local turn', () => {
  it('lands the record\u2019s evidence, and KEEPS the reader\u2019s question, on the open turn', () => {
    clock = 0;
    // The class is "the record recorded no prompt for this turn": a workflow or background run
    // joined to the conversation, a search-only run, or a checkpointed message array that no longer
    // opens with a `role:"user"` entry. `projectSv3RecordTurns` names that class exactly where it
    // opens such a turn via `ensure(item)` — empty question, `openedByUser: false` — rather than
    // dropping activity that really happened.
    const recordTurns = projectSv3RecordTurns([
      event('t1', 'TOOL_ACTIVITY', 'core_search', { callId: 'c1', toolName: 'core_search' }),
      agentAssistant({ citationScorer: 'CROSS_ENCODER' }),
    ]);
    expect(recordTurns).toHaveLength(1);
    expect(recordTurns[0]!.question).toBe('');

    // The window, meanwhile, DID open a turn from the reader's prompt.
    // The run then TERMINATES, as `concludeRun` settles it before refreshing the record. A turn
    // still `streaming` is deliberately never overwritten by the record (it watched the stream and
    // the record cannot know more), so settling first is what the real order does, not a shortcut.
    const withTurn = settled(
      submitInSession(SV3_SESSIONS_EMPTY, 'why did it retry?', 1000, 'agent', 'uc-1'),
    );
    const local = withTurn.sessions[0]!.turns[0]!;
    expect(local.evidence).toBeNull();

    const applied = applySv3Record(withTurn, 'uc-1', recordTurns);
    const turns = applied.sessions[0]!.turns;
    // ONE turn, not two: the record's question-less turn reconciled onto the local one by POSITION.
    expect(turns).toHaveLength(1);
    const merged = turns[0]!;
    // Tempdoc 863 §4.B — the reader's prompt SURVIVES. `reconcile` spreads the recorded turn first
    // and then overrides everything the record cannot know; `question` now sits in that list, gated
    // on the record's own `recordOpenedByUser` rather than on the emptiness that merely correlates
    // with it. A record that never held a prompt cannot erase one.
    expect(recordTurns[0]!.recordOpenedByUser).toBe(false);
    expect(merged.question).toBe('why did it retry?');
    // ...and the evidence LANDED, which is the whole point of the case.
    expect(merged.evidence?.sources).toHaveLength(3);
    expect(merged.evidence?.marks).toHaveLength(1);
    expect(merged.recordId).toBe(recordTurns[0]!.id);
  });

  it('still takes the question FROM the record when a USER item opened it, text differing', () => {
    clock = 0;
    // The companion, and the reason the case above passes for the RIGHT reason. Gate the question on
    // emptiness and both cases would still be green today, because the projector only ever mints an
    // empty question for a turn no user item opened. Gate it on the fact and the two halves are
    // separable: here the record was told, so it is authoritative even though the strings differ —
    // an edited or re-asked prompt is the record's to state, not this window's to defend.
    const recordTurns = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'why did it retry, exactly?'),
      event('t1', 'TOOL_ACTIVITY', 'core_search', { callId: 'c1', toolName: 'core_search' }),
      agentAssistant({ citationScorer: 'CROSS_ENCODER' }),
    ]);
    expect(recordTurns).toHaveLength(1);
    expect(recordTurns[0]!.recordOpenedByUser).toBe(true);

    const withTurn = settled(
      submitInSession(SV3_SESSIONS_EMPTY, 'why did it retry?', 1000, 'agent', 'uc-3'),
    );
    const turns = applySv3Record(withTurn, 'uc-3', recordTurns).sessions[0]!.turns;
    expect(turns).toHaveLength(1);
    expect(turns[0]!.question).toBe('why did it retry, exactly?');
    // ...and the merge is otherwise the same merge: the evidence still lands on the one turn.
    expect(turns[0]!.evidence?.sources).toHaveLength(3);
  });

  it('does not blank a live turn\u2019s evidence when the record\u2019s arrives (reconcileEvidence)', () => {
    clock = 0;
    const recordTurns = projectSv3RecordTurns([agentAssistant({ citationScorer: 'CROSS_ENCODER' })]);
    const withTurn = settled(
      submitInSession(SV3_SESSIONS_EMPTY, 'why did it retry?', 1000, 'agent', 'uc-2'),
    );
    const applied = applySv3Record(withTurn, 'uc-2', recordTurns);
    const before = applied.sessions[0]!.turns[0]!.evidence;
    // A second refresh over the same record is idempotent — the same three sources, not zero.
    const again = applySv3Record(applied, 'uc-2', recordTurns);
    expect(again.sessions[0]!.turns[0]!.evidence?.sources).toHaveLength(3);
    expect(again.sessions[0]!.turns[0]!.evidence?.marks).toEqual(before?.marks);
  });
});

/**
 * Tempdoc 865 §7.3 — the RECORD leg. A state that shows while the run is on screen and vanishes on
 * reload is worse than one never made: the reader has learned to trust it, so its absence then reads
 * as "this one was fine". The record carries `citationScorer` verbatim
 * (`AgentInteractionMapper.java:76-77`), so the flag is re-derived, not re-transmitted.
 */
describe('865 PR-0 — the record replays "the grounding pass did not complete"', () => {
  it('re-derives the state from the persisted stamp, and words it as the live path does', () => {
    clock = 0;
    // The timeout shape as it is PERSISTED: the full source list, no cites, `NONE`.
    const turns = projectSv3RecordTurns([
      event('a1', 'ASSISTANT_MESSAGE', 'The retry succeeded.', {
        sources: AGENT_SOURCES,
        citations: [],
        citationScorer: 'NONE',
      }),
    ]);
    const evidence = turns[0]!.evidence!;
    expect(evidence.sources).toHaveLength(3);
    expect(evidence.matches).toEqual([]);
    expect(evidence.groundingIncomplete).toBe(true);

    const g = sourceGrounding(0, evidence.matches, evidence.sources[0]!.parentDocId, null, evidence.groundingIncomplete);
    expect(sourceGroundingLabel(g)).toBe('Retrieved · grounding check did not complete');
  });

  it('a record with NO stamp keeps the pre-865 reading — absence asserts nothing', () => {
    clock = 0;
    const turns = projectSv3RecordTurns([
      event('a1', 'ASSISTANT_MESSAGE', 'The retry succeeded.', {
        sources: AGENT_SOURCES,
        citations: [],
      }),
    ]);
    expect(turns[0]!.evidence!.groundingIncomplete).toBe(false);
  });

  it('LAST-WINS across a multi-message turn, with the rest of the evidence record', () => {
    clock = 0;
    // The flag rides INSIDE the evidence object, so the whole-object replacement IS its rule: an
    // interim message whose pass timed out says nothing about the terminal message's, and vice
    // versa. An accumulated OR would let the first suppress the second's real verdict.
    const timedOutThenScored = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'why did it retry?'),
      event('a1', 'ASSISTANT_MESSAGE', 'Thinking.', {
        sources: AGENT_SOURCES,
        citations: [],
        citationScorer: 'NONE',
      }),
      agentAssistant({ citationScorer: 'CROSS_ENCODER' }),
    ]);
    expect(timedOutThenScored).toHaveLength(1);
    expect(timedOutThenScored[0]!.evidence!.groundingIncomplete).toBe(false);
    expect(timedOutThenScored[0]!.evidence!.matches).toHaveLength(1);

    clock = 0;
    const scoredThenTimedOut = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'why did it retry?'),
      agentAssistant({ citationScorer: 'CROSS_ENCODER' }),
      event('a2', 'ASSISTANT_MESSAGE', 'And finally.', {
        sources: AGENT_SOURCES,
        citations: [],
        citationScorer: 'NONE',
      }),
    ]);
    expect(scoredThenTimedOut[0]!.evidence!.groundingIncomplete).toBe(true);
    expect(scoredThenTimedOut[0]!.evidence!.matches).toEqual([]);
  });
});

/**
 * Tempdoc 865 §7.1 / §7.9 A9 — PLANE AUTHORITY on the record, and the unhappy terminals it saves.
 *
 * A delegate run that ends without an answer writes NO persisted assistant row (863 §4.A.5), so the
 * store plane has no carrier at all — and an answerless terminal carries an empty source list, so
 * the run plane has nothing either. Everything the run drew on used to die there. The
 * per-call deltas stamped onto each `tool_exec_completed` are what survives, and the mapper already
 * copies `structuredData` onto the record event verbatim.
 *
 * HONEST SCOPE (865 PR-1 review F-7): the §7.9/A9 composition with 863 is MODELLED here, not
 * exercised end to end. These fixtures are `ThreadEvent`s that run through the shared
 * `projectUnifiedThread` — real projection, hand-built inputs — so what they pin is this reader's
 * rule given a store row that is present or absent. What they do NOT pin is that 863's suppression
 * really produces those two input shapes: that a stamped conversation's run-plane `done` is filtered
 * (`AgentRunQueryService`) and that an answerless run writes no assistant row (`ConversationEngine`).
 * The end-to-end variant belongs on the Java side, over a real `AgentRunStore` + thread projection,
 * and is named here as the record suite's gap rather than implied to be covered.
 */
describe('865 §7.1 — the record reconstructs an answerless run from its per-call deltas', () => {
  /** The wire shape `OperationResult.withGrounding` stamps (AgentSource's eight fields). */
  const delta = (parentDocId: string, chunkIndex = 0) => ({
    parentDocId,
    chunkIndex,
    path: `f:/${parentDocId}`,
    title: parentDocId,
    excerpt: 'a passage',
    startLine: 1,
    endLine: 5,
    headingText: '',
  });

  const toolRow = (callId: string, grounding: unknown[]): ThreadEvent =>
    event(callId, 'TOOL_ACTIVITY', 'core_search', {
      callId,
      toolName: 'core_search',
      status: 'completed',
      success: true,
      structuredData: { searchResults: [], grounding },
    });

  it('RED BEFORE / GREEN AFTER: a cancelled run keeps what its two searches established', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'find the cause'),
      toolRow('c1', [delta('docs/a.md'), delta('docs/b.md')]),
      // No assistant row and no terminal: the run was cancelled after the second search.
      toolRow('c2', [delta('docs/c.md')]),
    ]);
    // Before 865 this was `null` — the pane rendered no sources for a run that read three documents.
    expect(turn!.evidence).not.toBeNull();
    expect(turn!.evidence!.sources.map((s) => s.parentDocId)).toEqual([
      'docs/a.md',
      'docs/b.md',
      'docs/c.md',
    ]);
    // No pass ran (there was no answer to check), so no source may be told it was found wanting.
    const g = sourceGrounding(
      0,
      turn!.evidence!.matches,
      'docs/a.md',
      null,
      turn!.evidence!.groundingIncomplete,
    );
    expect(sourceGroundingLabel(g)).toBe('Retrieved · grounding check did not complete');
  });

  it('the STORE-plane terminal is authoritative when present — deltas do not add to it', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'find the cause'),
      // The same three documents the terminal will report, delivered incrementally first.
      toolRow('c1', [delta('docs/runbook.md', 7), delta('docs/postmortem.md', 1)]),
      toolRow('c2', [delta('docs/ledger.md', 0)]),
      agentAssistant({ citationScorer: 'CROSS_ENCODER' }),
    ]);
    // THREE, not six. The deltas and the terminal describe the same set; accumulating both would
    // double every source and break the positional index the inline marks resolve through.
    expect(turn!.evidence!.sources).toHaveLength(3);
    expect(turn!.evidence!.sources.map((s) => s.startLine)).toEqual([3, 40, 1]);
    // And the terminal's verdict survives — it is the one that judged the answer.
    expect(turn!.evidence!.matches).toHaveLength(1);
    expect(turn!.evidence!.groundingIncomplete).toBe(false);
  });

  it('a turn with neither a terminal nor a delta keeps its honest "never told"', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'hello'),
      event('t1', 'TOOL_ACTIVITY', 'core_file_read', { callId: 'c1', toolName: 'core_file_read' }),
    ]);
    expect(turn!.evidence).toBeNull();
  });
});

/**
 * Tempdoc 867 — `Sv3Turn.evidencePaths`: the RAW `path`-carrying set a record-hydrated turn's tool
 * cards join their own hits against (`Sv3Main.recordedActivity`). Distinct from `evidence.sources`,
 * which drops `path` (`agentEvidence.toAnswerEvidenceSource` projects onto `AnswerEvidenceSource`) —
 * this is the field that keeps it, following the SAME plane-authority rule 865 §7.1 gives `evidence`
 * itself: the deltas fill the gap when the terminal never spoke, and are never ADDED to one that did.
 */
describe('867 — Sv3Turn.evidencePaths follows the same plane-authority rule as evidence', () => {
  const delta = (parentDocId: string, chunkIndex = 0) => ({
    parentDocId,
    chunkIndex,
    path: `f:/${parentDocId}`,
    title: parentDocId,
    excerpt: 'a passage',
    startLine: 1,
    endLine: 5,
    headingText: '',
  });

  const toolRow = (callId: string, grounding: unknown[]): ThreadEvent =>
    event(callId, 'TOOL_ACTIVITY', 'core_search', {
      callId,
      toolName: 'core_search',
      status: 'completed',
      success: true,
      structuredData: { searchResults: [], grounding },
    });

  it('an answerless run keeps the paths its calls established, from the deltas alone', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'find the cause'),
      toolRow('c1', [delta('docs/a.md'), delta('docs/b.md')]),
      toolRow('c2', [delta('docs/c.md')]),
    ]);
    expect(new Set(turn!.evidencePaths)).toEqual(new Set(['f:/docs/a.md', 'f:/docs/b.md', 'f:/docs/c.md']));
  });

  it('a grounded terminal keeps ITS paths, not doubled by the same-set deltas that preceded it', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'find the cause'),
      toolRow('c1', [delta('docs/runbook.md', 7), delta('docs/postmortem.md', 1)]),
      toolRow('c2', [delta('docs/ledger.md', 0)]),
      agentAssistant({ citationScorer: 'CROSS_ENCODER' }),
    ]);
    // The terminal's own AGENT_SOURCES cover the same three documents the deltas already established;
    // a Set dedups the overlap, so this is still 3 — never 6.
    expect(new Set(turn!.evidencePaths)).toEqual(
      new Set(['f:/docs/runbook.md', 'f:/docs/postmortem.md', 'f:/docs/ledger.md']),
    );
  });

  it('a turn with neither a terminal nor a delta has an empty path set, not a guess', () => {
    clock = 0;
    const [turn] = projectSv3RecordTurns([
      event('u1', 'USER_MESSAGE', 'hello'),
      event('t1', 'TOOL_ACTIVITY', 'core_file_read', { callId: 'c1', toolName: 'core_file_read' }),
    ]);
    expect(turn!.evidencePaths).toEqual([]);
  });
});

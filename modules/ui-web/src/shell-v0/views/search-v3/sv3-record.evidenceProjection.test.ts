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

describe('T11 — an agent conversation with NO user message reconciles onto the local turn (§9(c))', () => {
  it('lands the record\u2019s evidence on the window\u2019s open delegate turn', () => {
    clock = 0;
    // `ConversationEngine.dispatchShapeDriven` never calls `appendMessage`, so a delegate run
    // persists NO user message. The record therefore opens its turn via `ensure(item)` with an empty
    // question and `openedByUser: false` — the shape §9 named as the untested suspect between
    // `refreshRecord` and the rendered turn.
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
    // OBSERVED LIMIT, pre-existing and NOT this slice's to fix (859 §9 named the reconciliation of
    // a question-less record turn as the untested shape; PR-1's scope is the EVIDENCE): `reconcile`
    // spreads the recorded turn first, so the record's empty question replaces the reader's prompt.
    // Asserted as it behaves rather than as it ought to, so this file states a fact instead of
    // silently depending on one — and logged to the inbox.
    expect(merged.question).toBe('');
    // ...and the evidence LANDED, which is the whole point of the case.
    expect(merged.evidence?.sources).toHaveLength(3);
    expect(merged.evidence?.marks).toHaveLength(1);
    expect(merged.recordId).toBe(recordTurns[0]!.id);
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

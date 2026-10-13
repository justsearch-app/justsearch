import { expect, it } from 'vitest';
import { projectSv3RecordTurns } from './sv3-record.js';
import { applySv3Record, latestTurnRef, setTurnEvidence, settleTurn, submitInSession,
  SV3_SESSIONS_EMPTY, type Sv3TurnRef } from './sv3-sessions.js';

it('reload fills empty live retrieval explanations from the record', () => {
  const submitted = submitInSession(SV3_SESSIONS_EMPTY, 'question', 1000, 'ask', 'conv-retrieval');
  const ref = latestTurnRef(submitted) as Sv3TurnRef;
  const live = settleTurn(setTurnEvidence(submitted, ref, { sources: [], matches: [], marks: [],
    retrievalMode: '', retrievalModeReason: '', retrievalModeCause: '' }), ref, 'complete', 2000);
  const record = projectSv3RecordTurns([
    { id: 'question-1', occurredAt: new Date(1000).toISOString(), kind: 'USER_MESSAGE',
      originator: 'user', content: 'question', attributes: {} },
    { id: 'answer-1', occurredAt: new Date(2000).toISOString(), kind: 'ASSISTANT_MESSAGE',
      originator: 'agent', content: 'answer', attributes: { citations: [], retrievalMode: 'BM25',
        retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED', retrievalModeCause: 'loading' } },
  ]);
  expect(applySv3Record(live, 'conv-retrieval', record).sessions[0]?.turns[0]?.evidence)
    .toMatchObject({ retrievalMode: 'BM25', retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED',
      retrievalModeCause: 'loading' });
});

it('records without retrieval metadata retain their previous evidence', () => {
  const [turn] = projectSv3RecordTurns([
    { id: 'question-legacy', occurredAt: new Date(1000).toISOString(), kind: 'USER_MESSAGE',
      originator: 'user', content: 'question', attributes: {} },
    { id: 'answer-legacy', occurredAt: new Date(2000).toISOString(), kind: 'ASSISTANT_MESSAGE',
      originator: 'agent', content: 'answer', attributes: { citations: [] } },
  ]);
  expect(turn?.evidence).toEqual({ sources: [], matches: [], marks: [], retrievalMode: '' });
});

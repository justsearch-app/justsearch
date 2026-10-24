// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { projectSv3RecordTurns } from './search-v3/sv3-record.js';

it.each(['core.rag-ask', 'core.free-chat'])('an unanswered saved %s question stays unfinished after Engine loss (F2)', shapeId => {
  const [turn] = projectSv3RecordTurns([{ id: '00000000-0000-4000-8000-000000000001',
    occurredAt: '2026-10-19T00:00:00Z', kind: 'USER_MESSAGE', originator: 'user',
    content: 'Keep my question when the Engine is lost', attributes: { shapeId } }]);
  expect(turn?.question).toBe('Keep my question when the Engine is lost');
  expect(turn?.status).toBe('failed');
  expect(turn?.detail).toMatch(/no answer.*saved|unfinished|interrupted/i);
  expect(turn?.answer).toBe('');
});

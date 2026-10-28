// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { projectUnifiedThread, type ThreadEvent, } from './unifiedThreadProjection.js';
import { projectSv3RecordTurns } from './search-v3/sv3-record.js';

const userId = 'fadd5f39-7d64-4c8c-b872-21d9d9865f98';
const errorId = 'd91f2f04-7736-46af-aa68-253f12e8a2f4';
const events = (questionAt: string, errorAt: string): ThreadEvent[] => [
  { id: errorId, kind: 'ERROR', occurredAt: errorAt, originator: 'agent',
    content: 'No matching documents found in the index',
    attributes: { shapeId: 'core.rag-ask', errorCode: 'NO_CONTENT', retrievalScoped: false } },
  { id: userId, kind: 'USER_MESSAGE', occurredAt: questionAt, originator: 'user',
    content: 'Keep this question with its no-evidence outcome', attributes: { shapeId: 'core.rag-ask' } },
];

it.each([
  ['2026-10-23T02:04:45.355494108Z', '2026-10-23T02:04:45.355915929Z'],
  ['2026-10-23T02:04:45.000000100Z', '2026-10-23T02:04:45.000000200Z'],
  ['2026-10-23T03:04:45.000000100+01:00', '2026-10-23T02:04:45.000000200Z'],
])('keeps the authoritative question/error order below one millisecond: %s (NE1)', (questionAt, errorAt) => {
  const input = events(questionAt, errorAt);
  const original = JSON.stringify(input);
  expect(projectUnifiedThread(input).map(item => item.id)).toEqual([userId, errorId]);
  expect(JSON.stringify(input)).toBe(original);
});

it('a fast saved no-evidence outcome remains one complete record turn, not an orphan and an unfinished question (NE1, F2)', () => {
  const turns = projectSv3RecordTurns(events('2026-10-23T02:04:45.355494108Z', '2026-10-23T02:04:45.355915929Z'));
  expect(turns).toHaveLength(1);
  expect(turns[0]).toMatchObject({ recordId: userId, assistantRecordId: errorId, status: 'no-evidence' });
});

it('preserves the existing stable id tiebreak for genuinely equal instants (HN8)', () => {
  const sameTime = '2026-10-23T02:04:45.355Z';
  expect(projectUnifiedThread(events(sameTime, sameTime)).map(item => item.id)).toEqual([errorId, userId]);
});

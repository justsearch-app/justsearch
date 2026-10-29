// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { projectUnifiedThread, type ThreadEvent } from './unifiedThreadProjection.js';
import { projectSv3RecordTurns } from './search-v3/sv3-record.js';

const at = '2026-10-23T02:04:45.355494108Z';
function row(id: string, kind: ThreadEvent['kind'], content: string, attributes: Record<string, unknown> = {}, occurredAt = at): ThreadEvent {
  return { id, kind, content, occurredAt, originator: kind === 'USER_MESSAGE' ? 'user' : 'agent', attributes };
}

describe('canonical record occurrence order (WU4/WU5/WU7/WU9)', () => {
  it('keeps two reversed-ID exchanges with duplicate supplied IDs without mutating input', () => {
    const input = [
      row('f0000000-0000-4000-8000-000000000000', 'USER_MESSAGE', 'Q1', { shapeId: 'core.rag-ask' }),
      row('e0000000-0000-4000-8000-000000000000', 'ERROR', 'E1', { errorCode: 'NO_CONTENT', retrievalScoped: false }),
      row('d0000000-0000-4000-8000-000000000000', 'USER_MESSAGE', 'Q2', { shapeId: 'core.rag-ask' }),
      row('c0000000-0000-4000-8000-000000000000', 'ERROR', 'E2', { errorCode: 'RETRIEVAL_FAILED', retrievalScoped: true }),
    ];
    const original = JSON.stringify(input);
    expect(projectUnifiedThread(input).map(i => i.content)).toEqual(['Q1', 'E1', 'Q2', 'E2']);
    expect(projectSv3RecordTurns(input).map(t => [t.question, t.assistantRecordId, t.status])).toEqual([
      ['Q1', input[1]!.id, 'no-evidence'], ['Q2', input[3]!.id, 'failed'],
    ]);
    const duplicate = input.map(e => ({ ...e, id: 'idx-0' }));
    expect(projectUnifiedThread(duplicate).map(i => i.content)).toEqual(['Q1', 'E1', 'Q2', 'E2']);
    expect(JSON.stringify(input)).toBe(original);
  });

  it('keeps the retained prior tool representative at its source occurrence, with later attributes and reasoning union', () => {
    const input = [
      row('z', 'TOOL_ACTIVITY', 'selected content', { callId: 'call', status: 'completed', reasoning: [{ text: 'first' }] }, '2026-10-23T02:04:45.356Z'),
      row('a', 'ERROR', 'between', {}, '2026-10-23T02:04:45.356Z'),
      row('old', 'TOOL_ACTIVITY', 'older incoming content', { callId: 'call', status: 'late-input', reasoning: [{ text: 'second' }] }, '2026-10-23T02:04:45.355Z'),
    ];
    const original = JSON.stringify(input);
    const out = projectUnifiedThread(input);
    expect(out.map(i => i.id)).toEqual(['z', 'a']);
    expect(out[0]).toMatchObject({ content: 'selected content', ts: Date.parse(input[0]!.occurredAt),
      attributes: { status: 'late-input', reasoning: [{ text: 'first' }, { text: 'second' }] } });
    expect(JSON.stringify(input)).toBe(original);
  });

  it('keeps the incoming representative index across repeated IDs/callIds and passthrough interleaving', () => {
    const input = [
      row('duplicate', 'TOOL_ACTIVITY', 'a-old', { callId: 'a', toolName: 'lookup' }),
      row('duplicate', 'TOOL_ACTIVITY', 'b-old', { callId: 'b' }),
      row('duplicate', 'USER_MESSAGE', 'before'),
      row('duplicate', 'TOOL_ACTIVITY', 'a-new', { callId: 'a', status: 'completed' }),
      row('duplicate', 'ERROR', 'between'),
      row('duplicate', 'TOOL_ACTIVITY', 'b-new', { callId: 'b', status: 'completed' }),
      row('duplicate', 'ASSISTANT_MESSAGE', 'after'),
    ];
    const original = JSON.stringify(input);
    expect(projectUnifiedThread(input).map(i => i.content)).toEqual(['before', 'a-new', 'between', 'b-new', 'after']);
    expect(JSON.stringify(input)).toBe(original);
  });

  it('retains the millisecond base selector even when incoming lifecycle nanoseconds are earlier', () => {
    const early = '2026-10-23T02:04:45.355000100Z';
    const input = [
      row('first', 'TOOL_ACTIVITY', 'later nano', { callId: 'call', toolName: 'lookup' }, '2026-10-23T02:04:45.355000900Z'),
      row('z', 'PROGRESS', 'before incoming', {}, early),
      row('selected', 'TOOL_ACTIVITY', 'earlier nano incoming', { callId: 'call', status: 'completed' }, early),
      row('a', 'ERROR', 'after incoming', {}, early),
    ];
    const out = projectUnifiedThread(input);
    expect(out.map(i => i.content)).toEqual(['before incoming', 'earlier nano incoming', 'after incoming']);
    expect(out[1]).toMatchObject({ id: 'selected', subMillisecond: 0.0001, attributes: { toolName: 'lookup', status: 'completed' } });
  });

  it('sorts distinct nanoseconds chronologically and treats offset/fraction spellings as equal instants', () => {
    const input = [
      row('late', 'USER_MESSAGE', 'later', {}, '2026-10-23T02:04:45.355000200Z'),
      row('z', 'USER_MESSAGE', 'same first', {}, '2026-10-23T03:04:45.355000100+01:00'),
      row('a', 'USER_MESSAGE', 'same second', {}, '2026-10-23T02:04:45.3550001Z'),
    ];
    expect(projectUnifiedThread(input).map(i => i.content)).toEqual(['same first', 'same second', 'later']);
  });

});

it('SA15 retains canonical workflow grouping without the retired live/record merge', () => {
  const input = [row('z', 'USER_MESSAGE', 'outside'), row('s', 'PROGRESS', '', { nodeBoundary: 'start', nodeId: 'one' }),
    row('y', 'ASSISTANT_MESSAGE', 'inside one'), row('e', 'PROGRESS', '', { nodeBoundary: 'end' }),
    row('s2', 'PROGRESS', '', { nodeBoundary: 'start', nodeId: 'two' }), row('a', 'ERROR', 'inside two'),
    row('e2', 'PROGRESS', '', { nodeBoundary: 'end' })];
  const original = JSON.stringify(input);
  expect(projectUnifiedThread(input).map(i => [i.content, i.segment?.nodeId])).toEqual([
    ['outside', undefined], ['inside one', 'one'], ['inside two', 'two'],
  ]);
  expect(JSON.stringify(input)).toBe(original);
});

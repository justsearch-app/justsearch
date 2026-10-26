// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { compareUnifiedTurnItems, projectUnifiedThread, type ThreadEvent, type UnifiedTurnItem, type LiveConversationEntry } from './unifiedThreadProjection.js';
import './UnifiedChatView.js';

const at = '2026-10-23T02:04:45.355Z';
function row(id: string, kind: ThreadEvent['kind'], content: string, attributes: Record<string, unknown> = {}): ThreadEvent {
  return { id, kind, content, occurredAt: at, originator: kind === 'USER_MESSAGE' ? 'user' : 'agent', attributes };
}
interface Harness {
  affordance: string;
  unifiedEvents: ThreadEvent[];
  agentCtrl: {
    conversation: LiveConversationEntry[]; toolCalls: Record<string, never>;
    answerEvidenceRunId: null; sessionId: string;
  } | null;
  resumeSeamId: string | null;
  mergedTimeline(): UnifiedTurnItem[];
}
function view(events: ThreadEvent[], conversation: LiveConversationEntry[] = []): Harness {
  const v = document.createElement('jf-unified-chat-view') as unknown as Harness;
  v.affordance = conversation.length ? 'agent' : 'none';
  v.unifiedEvents = events;
  v.agentCtrl = conversation.length ? { conversation, toolCalls: {}, answerEvidenceRunId: null, sessionId: 'owned' } : null;
  return v;
}

it('restores actual record occurrences after a live directive replaces a queued record object (WU6)', () => {
  const events = [
    row('z', 'USER_MESSAGE', 'same'),
    row('direction', 'PROGRESS', 'focus', { steer: true, directiveId: 'd', directiveOutcome: 'queued' }),
    row('duplicate', 'ASSISTANT_MESSAGE', 'answer one'),
    row('duplicate', 'USER_MESSAGE', 'same'),
    row('a', 'ASSISTANT_MESSAGE', 'answer two'),
  ];
  const live: LiveConversationEntry[] = [
    { id: 'live-1', type: 'user', content: 'same', timestamp: Date.parse(at) },
    { id: 'live-2', type: 'user', content: 'same', timestamp: Date.parse(at) },
    { id: 'live-direction', type: 'steer-directive', content: 'focus', timestamp: Date.parse(at), directiveId: 'd', directiveOutcome: 'not_applied', directiveReason: 'run_finished' },
  ];
  const original = JSON.stringify({ events, live });
  const v = view(events, live);
  const first = v.mergedTimeline();
  expect(first.map(i => i.content)).toEqual(['same', 'focus', 'answer one', 'same', 'answer two']);
  expect(first[1]!.attributes).toMatchObject({ directiveOutcome: 'not_applied', directiveReason: 'run_finished' });
  expect(v.resumeSeamId).toBeNull();
  expect(v.mergedTimeline()).toEqual(first);
  expect(JSON.stringify({ events, live })).toBe(original);
});

it('consumes duplicate content occurrences and leaves a genuine live USER in its old fixed slot (WU6/WU8)', () => {
  const events = [row('z', 'USER_MESSAGE', 'same'), row('a', 'ASSISTANT_MESSAGE', 'answer')];
  const live: LiveConversationEntry[] = [
    { id: 'dedup', type: 'user', content: 'same', timestamp: Date.parse(at) },
    { id: 'm', type: 'user', content: 'same', timestamp: Date.parse(at) },
    { id: 'n', type: 'error', content: 'live error', timestamp: Date.parse(at) },
  ];
  const v = view(events, live);
  expect(v.mergedTimeline().map(i => i.id)).toEqual(['z', 'm', 'n', 'a']);
  expect(v.resumeSeamId).toBe('m');
  // The accepted mixed-plane limit: live USER interrupts the saved question/answer pair.
});

it('keeps canonical workflow grouping and record order through the legacy merge', () => {
  const events = [
    row('z', 'USER_MESSAGE', 'outside'),
    row('s', 'PROGRESS', '', { nodeBoundary: 'start', nodeId: 'one' }),
    row('y', 'ASSISTANT_MESSAGE', 'inside one'),
    row('e', 'PROGRESS', '', { nodeBoundary: 'end' }),
    row('s2', 'PROGRESS', '', { nodeBoundary: 'start', nodeId: 'two' }),
    row('a', 'ERROR', 'inside two'),
    row('e2', 'PROGRESS', '', { nodeBoundary: 'end' }),
  ];
  const expected = projectUnifiedThread(events);
  expect(expected.map(i => [i.content, i.segment?.nodeId])).toEqual([
    ['outside', undefined], ['inside one', 'one'], ['inside two', 'two'],
  ]);
  const v = view(events);
  expect(v.mergedTimeline()).toEqual(expected);
  // Standalone comparator still orders these IDs independently of the record policy.
  expect([...expected].sort(compareUnifiedTurnItems).map(i => i.id)).toEqual(['a', 'y', 'z']);
});

it('preserves inputs, directive upgrades and repeated-merge results independently of the repaired order', () => {
  const events = [
    row('z', 'USER_MESSAGE', 'same'),
    row('direction', 'PROGRESS', 'focus', { steer: true, directiveId: 'd', directiveOutcome: 'queued' }),
    row('a', 'ASSISTANT_MESSAGE', 'saved answer'),
  ];
  const live: LiveConversationEntry[] = [
    { id: 'live-user', type: 'user', content: 'same', timestamp: Date.parse(at) },
    { id: 'live-direction', type: 'steer-directive', content: 'focus', timestamp: Date.parse(at), directiveId: 'd', directiveOutcome: 'applied' },
  ];
  const original = JSON.stringify({ events, live });
  const v = view(events, live);
  const first = v.mergedTimeline();
  expect(first.find(i => i.attributes.directiveId === 'd')?.attributes.directiveOutcome).toBe('applied');
  expect(first.filter(i => i.kind === 'user')).toHaveLength(1);
  expect(v.mergedTimeline()).toEqual(first);
  expect(JSON.stringify({ events, live })).toBe(original);
});

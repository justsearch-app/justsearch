import { describe, it, expect } from 'vitest';
import {
  projectUnifiedThread,
  projectLiveAgentActivity,
  terminalAssistantIds,
  assignRunSegments,
  type ThreadEvent,
  type LiveConversationEntry,
  type LiveToolCall,
  type UnifiedTurnItem,
} from './unifiedThreadProjection.js';

function ev(partial: Partial<ThreadEvent> & Pick<ThreadEvent, 'id' | 'kind' | 'occurredAt'>): ThreadEvent {
  return {
    originator: 'user',
    content: '',
    attributes: {},
    ...partial,
  };
}

describe('projectUnifiedThread (561 P-A/P-B Slice 2)', () => {
  it('maps kinds and orders by the authoritative occurredAt, not input order', () => {
    const out = projectUnifiedThread([
      ev({ id: 'a', kind: 'ASSISTANT_MESSAGE', occurredAt: '2026-01-01T00:00:03Z', content: 'answer' }),
      ev({ id: 'u', kind: 'USER_MESSAGE', occurredAt: '2026-01-01T00:00:01Z', content: 'q' }),
    ]);
    expect(out.map((i) => i.id)).toEqual(['u', 'a']);
    expect(out[0]!.kind).toBe('user');
    expect(out[1]!.kind).toBe('assistant');
  });

  it('interleaves an answer turn and an agent tool turn under one record', () => {
    const out = projectUnifiedThread([
      ev({ id: 'u1', kind: 'USER_MESSAGE', occurredAt: '2026-01-01T00:00:01Z', content: 'find invoices' }),
      ev({ id: 't1', kind: 'TOOL_ACTIVITY', occurredAt: '2026-01-01T00:00:02Z', originator: 'agent', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } }),
      ev({ id: 'a1', kind: 'ASSISTANT_MESSAGE', occurredAt: '2026-01-01T00:00:03Z', originator: 'agent', content: 'done' }),
    ]);
    expect(out.map((i) => i.kind)).toEqual(['user', 'tool-activity', 'assistant']);
    expect(out[1]!.attributes.toolName).toBe('core_search_index');
  });

  it('collapses a tool call to its latest status (pending -> completed = one card)', () => {
    const out = projectUnifiedThread([
      ev({ id: 'p', kind: 'TOOL_ACTIVITY', occurredAt: '2026-01-01T00:00:01Z', attributes: { callId: 'c1', status: 'pending' } }),
      ev({ id: 'c', kind: 'TOOL_ACTIVITY', occurredAt: '2026-01-01T00:00:02Z', attributes: { callId: 'c1', status: 'completed', output: '12 results' } }),
    ]);
    const tools = out.filter((i) => i.kind === 'tool-activity');
    expect(tools).toHaveLength(1);
    expect(tools[0]!.attributes.status).toBe('completed');
    expect(tools[0]!.attributes.output).toBe('12 results');
  });

  it('is pure — same input yields an equal result and does not mutate the input', () => {
    const input: ThreadEvent[] = [
      ev({ id: 'u1', kind: 'USER_MESSAGE', occurredAt: '2026-01-01T00:00:01Z', content: 'hi' }),
    ];
    const frozen = JSON.stringify(input);
    const a = projectUnifiedThread(input);
    const b = projectUnifiedThread(input);
    expect(a).toEqual(b);
    expect(JSON.stringify(input)).toBe(frozen);
  });

  it('returns an empty thread for no events', () => {
    expect(projectUnifiedThread([])).toEqual([]);
  });

  // Tempdoc S4b (Search Thread) — a SEARCH event rides the existing `progress` UnifiedTurnKind (no new
  // render kind), marked with a dedicated `searchEvent` flag distinct from the UNKNOWN degrade's
  // `rawKind` marker, with its typed attributes passed through verbatim.
  it('maps a SEARCH event to the progress kind, marked searchEvent:true with attributes passed through', () => {
    const out = projectUnifiedThread([
      ev({
        id: 's1',
        kind: 'SEARCH',
        occurredAt: '2026-01-01T00:00:01Z',
        originator: 'user',
        attributes: {
          query: 'invoices',
          mode: 'hybrid',
          matchCount: 42,
          resultCount: 10,
          docIds: ['a.pdf', 'b.pdf'],
          executedAt: '2026-01-01T00:00:01Z',
        },
      }),
    ]);
    expect(out).toHaveLength(1);
    expect(out[0]!.kind).toBe('progress');
    expect(out[0]!.attributes.searchEvent).toBe(true);
    expect(out[0]!.attributes.query).toBe('invoices');
    expect(out[0]!.attributes.mode).toBe('hybrid');
    expect(out[0]!.attributes.matchCount).toBe(42);
    expect(out[0]!.attributes.resultCount).toBe(10);
    expect(out[0]!.attributes.docIds).toEqual(['a.pdf', 'b.pdf']);
  });
});

function entry(
  partial: Partial<LiveConversationEntry> &
    Pick<LiveConversationEntry, 'id' | 'type' | 'timestamp'>,
): LiveConversationEntry {
  return { content: '', ...partial };
}

describe('projectLiveAgentActivity (565 §12 Phase 2 — one ordered run projection)', () => {
  it('projects user / assistant / tool into the SAME UnifiedTurnItem contract, ts-ordered', () => {
    const tools: Record<string, LiveToolCall> = {
      c1: { callId: 'c1', toolName: 'core_search_index', arguments: '{"query":"x"}', risk: 'LOW', status: 'completed' },
    };
    const out = projectLiveAgentActivity(
      [
        entry({ id: 'a1', type: 'assistant-text', content: 'answer', timestamp: 3 }),
        entry({ id: 'u1', type: 'user', content: 'q', timestamp: 1 }),
        entry({ id: 'g1', type: 'tool-call-group', callIds: ['c1'], timestamp: 2 }),
      ],
      tools,
    );
    expect(out.map((i) => i.kind)).toEqual(['user', 'tool-activity', 'assistant']);
    // The tool row carries the call's identity in attributes (the inverse of renderToolActivity).
    expect(out[1]!.attributes.toolName).toBe('core_search_index');
    expect(out[1]!.attributes.arguments).toBe('{"query":"x"}');
    expect(out[1]!.attributes.status).toBe('completed');
  });

  it('EXPANDS a tool-call-group into one tool-activity per callId, pulling each ToolCall', () => {
    const tools: Record<string, LiveToolCall> = {
      c1: { callId: 'c1', toolName: 'core_search_index', risk: 'LOW', status: 'completed' },
      c2: { callId: 'c2', toolName: 'core_ingest_files', risk: 'MEDIUM', status: 'rejected', rejectReason: 'denied' },
    };
    const out = projectLiveAgentActivity(
      [entry({ id: 'g1', type: 'tool-call-group', callIds: ['c1', 'c2'], timestamp: 5 })],
      tools,
    );
    expect(out).toHaveLength(2);
    expect(out.map((i) => i.attributes.toolName)).toEqual(['core_search_index', 'core_ingest_files']);
    // rejectReason maps to `reason` (the key renderToolActivity reads back).
    expect(out[1]!.attributes.reason).toBe('denied');
    // Stable intra-group order via index offset on the shared group timestamp.
    expect(out[0]!.ts).toBeLessThan(out[1]!.ts);
  });

  it('drops a callId with no matching ToolCall (defensive — the group lists it but the map lacks it)', () => {
    const out = projectLiveAgentActivity(
      [entry({ id: 'g1', type: 'tool-call-group', callIds: ['missing'], timestamp: 1 })],
      {},
    );
    expect(out).toHaveLength(0);
  });

  it('rides the answer grounding on the assistant item so the unified render resolves [n] marks', () => {
    const sources = [{ parentDocId: 'd1', title: 'Doc' }];
    const citations = [{ sentenceText: 's', sourceIndex: 0, similarity: 0.9 }];
    const out = projectLiveAgentActivity(
      [entry({ id: 'a1', type: 'assistant-text', content: 'answer', timestamp: 1 })],
      {},
      { sources, citations },
    );
    expect(out[0]!.attributes.sources).toEqual(sources);
    expect(out[0]!.attributes.citations).toEqual(citations);
  });

  it('attaches the grounding to ONLY the last answer (fix B — multi-turn: the latest run owns it)', () => {
    const sources = [{ parentDocId: 'd2', title: 'Doc2' }];
    const citations = [{ sentenceText: 's2', sourceIndex: 0, similarity: 0.8 }];
    const out = projectLiveAgentActivity(
      [
        entry({ id: 'a1', type: 'assistant-text', content: 'first answer', timestamp: 1 }),
        entry({ id: 'u2', type: 'user', content: 'follow-up', timestamp: 2 }),
        entry({ id: 'a2', type: 'assistant-text', content: 'second answer', timestamp: 3 }),
      ],
      {},
      { sources, citations },
    );
    const assistants = out.filter((i) => i.kind === 'assistant');
    const first = assistants.find((i) => i.content === 'first answer')!;
    const second = assistants.find((i) => i.content === 'second answer')!;
    // The earlier answer carries NO grounding (it would otherwise show the latest run's [n] marks).
    expect(first.attributes.sources).toBeUndefined();
    expect(first.attributes.citations).toBeUndefined();
    // The latest answer owns the current grounding.
    expect(second.attributes.sources).toEqual(sources);
    expect(second.attributes.citations).toEqual(citations);
  });

  it('carries error code + progress severity (live == record after Phase 2)', () => {
    const out = projectLiveAgentActivity(
      [
        entry({ id: 'e1', type: 'error', content: 'boom', errorCode: 'E_X', timestamp: 1 }),
        entry({ id: 'p1', type: 'progress', content: 'thinking', severity: 'warn', timestamp: 2 }),
        entry({ id: 'h1', type: 'handoff', content: 'to worker', timestamp: 3 }),
      ],
      {},
    );
    expect(out[0]!.attributes.errorCode).toBe('E_X');
    expect(out[1]!.attributes.severity).toBe('warn');
    expect(out.map((i) => i.kind)).toEqual(['error', 'progress', 'handoff']);
  });

  it('is pure — does not mutate the input conversation', () => {
    const input = [entry({ id: 'u1', type: 'user', content: 'hi', timestamp: 1 })];
    const frozen = JSON.stringify(input);
    projectLiveAgentActivity(input, {});
    expect(JSON.stringify(input)).toBe(frozen);
  });

  it('declares the §12.3.C prominence facet: answer/user primary, tool secondary, progress ambient', () => {
    const out = projectLiveAgentActivity(
      [
        entry({ id: 'u1', type: 'user', content: 'q', timestamp: 1 }),
        entry({ id: 'p1', type: 'progress', content: '...', timestamp: 2 }),
        entry({ id: 'g1', type: 'tool-call-group', callIds: ['c1'], timestamp: 3 }),
        entry({ id: 'a1', type: 'assistant-text', content: 'answer', timestamp: 4 }),
      ],
      { c1: { callId: 'c1', toolName: 't', risk: 'LOW', status: 'completed' } },
    );
    const byId = Object.fromEntries(out.map((i) => [i.kind, i.prominence]));
    expect(byId.user).toBe('primary');
    expect(byId.assistant).toBe('primary');
    expect(byId['tool-activity']).toBe('secondary');
    expect(byId.progress).toBe('ambient');
  });
});

describe('terminalAssistantIds (565 §13/§19.3 — one terminal Answer per turn)', () => {
  function item(id: string, kind: UnifiedTurnItem['kind']): UnifiedTurnItem {
    return { id, ts: 0, kind, prominence: 'primary', originator: 'agent', content: '', attributes: {} };
  }

  it('marks only the LAST assistant of each user→…→user segment', () => {
    const items = [
      item('u1', 'user'),
      item('a1mid', 'assistant'),
      item('t1', 'tool-activity'),
      item('a1final', 'assistant'),
      item('u2', 'user'),
      item('a2final', 'assistant'),
    ];
    const terminal = terminalAssistantIds(items);
    expect([...terminal].sort()).toEqual(['a1final', 'a2final']);
    expect(terminal.has('a1mid')).toBe(false);
  });

  it('marks the trailing assistant when the run ends without a following user turn', () => {
    const items = [item('u1', 'user'), item('a1', 'assistant')];
    expect([...terminalAssistantIds(items)]).toEqual(['a1']);
  });

  it('returns empty when a turn has no assistant message (e.g. an in-flight or aborted run)', () => {
    const items = [item('u1', 'user'), item('t1', 'tool-activity')];
    expect(terminalAssistantIds(items).size).toBe(0);
  });

  it('handles consecutive assistants with no interleaved user (single turn, multiple emissions)', () => {
    const items = [item('u1', 'user'), item('a', 'assistant'), item('b', 'assistant'), item('c', 'assistant')];
    expect([...terminalAssistantIds(items)]).toEqual(['c']);
  });
});

describe('assignRunSegments (565 §26.A/§26.B — the run-structure authority)', () => {
  function pItem(
    id: string,
    kind: UnifiedTurnItem['kind'],
    attributes: Record<string, unknown> = {},
  ): UnifiedTurnItem {
    return { id, ts: 0, kind, prominence: 'secondary', originator: 'agent', content: '', attributes };
  }

  it('brackets the items between a node start/end into one workflow segment and drops the markers', () => {
    const out = assignRunSegments([
      pItem('ns', 'progress', { nodeBoundary: 'start', nodeId: 'think', nodeKind: 'llm', label: 'think' }),
      pItem('a1', 'assistant'),
      pItem('ne', 'progress', { nodeBoundary: 'end', nodeId: 'think' }),
    ]);
    // The two boundary markers are consumed (dropped); only the real item survives.
    expect(out.map((i) => i.id)).toEqual(['a1']);
    expect(out[0]!.segment).toEqual({
      originKind: 'workflow',
      nodeId: 'think',
      nodeKind: 'llm',
      label: 'think',
    });
  });

  it('assigns each node its own segment across a multi-node run', () => {
    const out = assignRunSegments([
      pItem('s1', 'progress', { nodeBoundary: 'start', nodeId: 'think', nodeKind: 'llm', label: 'think' }),
      pItem('a1', 'assistant'),
      pItem('e1', 'progress', { nodeBoundary: 'end', nodeId: 'think' }),
      pItem('s2', 'progress', { nodeBoundary: 'start', nodeId: 'act', nodeKind: 'tool', label: 'act' }),
      pItem('t1', 'tool-activity'),
      pItem('e2', 'progress', { nodeBoundary: 'end', nodeId: 'act' }),
    ]);
    expect(out.map((i) => i.id)).toEqual(['a1', 't1']);
    expect(out[0]!.segment?.nodeId).toBe('think');
    expect(out[1]!.segment?.nodeId).toBe('act');
  });

  it('brackets a background run into a background-origin segment (§26.D)', () => {
    const out = assignRunSegments([
      pItem('s', 'progress', { nodeBoundary: 'start', originKind: 'background', nodeId: 'run-7', label: 'Background activity' }),
      pItem('u1', 'user'),
      pItem('a1', 'assistant'),
      pItem('e', 'progress', { nodeBoundary: 'end', originKind: 'background', nodeId: 'run-7' }),
    ]);
    expect(out.map((i) => i.id)).toEqual(['u1', 'a1']);
    expect(out[0]!.segment?.originKind).toBe('background');
    expect(out[0]!.segment?.nodeId).toBe('run-7');
    expect(out[1]!.segment?.originKind).toBe('background');
  });

  it('leaves items outside any node ungrouped (the degenerate agent run = one implicit segment)', () => {
    const out = assignRunSegments([
      pItem('u1', 'user'),
      pItem('t1', 'tool-activity'),
      pItem('a1', 'assistant'),
    ]);
    expect(out.map((i) => i.id)).toEqual(['u1', 't1', 'a1']);
    expect(out.every((i) => i.segment === undefined)).toBe(true);
  });

  // §26.C — the stack makes the authority robust regardless of any future backend concurrency.
  it('nested start/end brackets give an item its INNERMOST open segment (the stack)', () => {
    const out = assignRunSegments([
      pItem('s1', 'progress', { nodeBoundary: 'start', nodeId: 'outer', label: 'outer' }),
      pItem('a1', 'assistant'),
      pItem('s2', 'progress', { nodeBoundary: 'start', nodeId: 'inner', label: 'inner' }),
      pItem('a2', 'assistant'),
      pItem('e2', 'progress', { nodeBoundary: 'end', nodeId: 'inner' }),
      pItem('a3', 'assistant'),
      pItem('e1', 'progress', { nodeBoundary: 'end', nodeId: 'outer' }),
    ]);
    expect(out.map((i) => i.id)).toEqual(['a1', 'a2', 'a3']);
    expect(out[0]!.segment?.nodeId).toBe('outer'); // before the inner opens
    expect(out[1]!.segment?.nodeId).toBe('inner'); // innermost wins
    expect(out[2]!.segment?.nodeId).toBe('outer'); // inner popped, back to outer
  });

  it('a missing `end` brackets the trailing items to EOF (no crash)', () => {
    const out = assignRunSegments([
      pItem('s', 'progress', { nodeBoundary: 'start', nodeId: 'think', label: 'think' }),
      pItem('a1', 'assistant'),
      pItem('a2', 'assistant'),
    ]);
    expect(out.map((i) => i.id)).toEqual(['a1', 'a2']);
    expect(out.every((i) => i.segment?.nodeId === 'think')).toBe(true);
  });

  it('a stray `end` with no open segment is a harmless no-op', () => {
    const out = assignRunSegments([
      pItem('e', 'progress', { nodeBoundary: 'end', nodeId: 'ghost' }),
      pItem('a1', 'assistant'),
    ]);
    expect(out.map((i) => i.id)).toEqual(['a1']);
    expect(out[0]!.segment).toBeUndefined();
  });

  // §26.I (Fix A ordering) — node_output / node_completed are emitted back-to-back and routinely share a
  // millisecond, so the projection's `ts || id.localeCompare` sort decides via the id. The mapper builds
  // node ids as `…:node:<index>:<role 1=start|2=output|3=end>:…` so LEXICAL == TEMPORAL even on a tie.
  it('brackets node_output INSIDE the node when all node events share one timestamp (id sorts temporally)', () => {
    const T = '2026-01-01T00:00:05Z';
    const out = projectUnifiedThread([
      ev({ id: 'c:node:00000:1:think:t', kind: 'PROGRESS', occurredAt: T, originator: 'agent', attributes: { nodeBoundary: 'start', nodeId: 'think', label: 'think' } }),
      ev({ id: 'c:node:00000:2:think:t', kind: 'ASSISTANT_MESSAGE', occurredAt: T, originator: 'agent', content: 'think output' }),
      ev({ id: 'c:node:00000:3:think:t', kind: 'PROGRESS', occurredAt: T, originator: 'agent', attributes: { nodeBoundary: 'end', nodeId: 'think' } }),
    ]);
    expect(out.map((i) => i.kind)).toEqual(['assistant']); // boundaries dropped, output survives
    expect(out[0]!.segment?.nodeId).toBe('think'); // bracketed INSIDE, not outside
    expect(out[0]!.content).toBe('think output');
  });

  it('keeps node N end ahead of node N+1 start on a shared timestamp (cross-node ordering)', () => {
    const T = '2026-01-01T00:00:05Z';
    const out = projectUnifiedThread([
      ev({ id: 'c:node:00000:1:think:t', kind: 'PROGRESS', occurredAt: T, originator: 'agent', attributes: { nodeBoundary: 'start', nodeId: 'think', label: 'think' } }),
      ev({ id: 'c:node:00000:2:think:t', kind: 'ASSISTANT_MESSAGE', occurredAt: T, originator: 'agent', content: 'A' }),
      ev({ id: 'c:node:00000:3:think:t', kind: 'PROGRESS', occurredAt: T, originator: 'agent', attributes: { nodeBoundary: 'end', nodeId: 'think' } }),
      ev({ id: 'c:node:00001:1:draft:t', kind: 'PROGRESS', occurredAt: T, originator: 'agent', attributes: { nodeBoundary: 'start', nodeId: 'draft', label: 'draft' } }),
      ev({ id: 'c:node:00001:2:draft:t', kind: 'ASSISTANT_MESSAGE', occurredAt: T, originator: 'agent', content: 'B' }),
      ev({ id: 'c:node:00001:3:draft:t', kind: 'PROGRESS', occurredAt: T, originator: 'agent', attributes: { nodeBoundary: 'end', nodeId: 'draft' } }),
    ]);
    expect(out.map((i) => i.content)).toEqual(['A', 'B']);
    expect(out[0]!.segment?.nodeId).toBe('think'); // not mis-nested under draft
    expect(out[1]!.segment?.nodeId).toBe('draft');
  });

  it('565 §30 — a steer-directive entry projects to a human-origin point item flagged `steer`', () => {
    const out = projectLiveAgentActivity(
      [
        { id: 'u1', type: 'user', content: 'go', timestamp: 1 },
        { id: 's1', type: 'steer-directive', content: 'Focus only on Q3.', timestamp: 2 },
        { id: 'a1', type: 'assistant-text', content: 'ok', timestamp: 3 },
      ] as LiveConversationEntry[],
      {},
    );
    const steer = out.find((i) => i.id === 's1');
    expect(steer, 'the steer directive survives projection (not a dropped boundary)').toBeDefined();
    expect(steer!.kind).toBe('progress');
    expect(steer!.originator).toBe('user'); // human-origin, distinct from agent steps
    expect(steer!.attributes.steer).toBe(true);
    expect(steer!.content).toBe('Focus only on Q3.');
  });

  it('WP6.2 — a live direction carries its outcome to the spine, like a reloaded one', () => {
    const out = projectLiveAgentActivity(
      [
        {
          id: 's1', type: 'steer-directive', content: 'Too late.', timestamp: 2,
          directiveOutcome: 'not_applied', directiveReason: 'run_stopped',
        },
      ] as LiveConversationEntry[],
      {},
    );
    expect(out[0]!.attributes).toEqual({
      steer: true, directiveOutcome: 'not_applied', directiveReason: 'run_stopped',
    });
  });

  it('projectLiveAgentActivity brackets a run-node entry pair into a segment', () => {
    const out = projectLiveAgentActivity(
      [
        { id: 'n1', type: 'run-node', content: '', nodeBoundary: 'start', nodeId: 'draft', nodeKind: 'llm', nodeLabel: 'draft', timestamp: 1 },
        { id: 'a1', type: 'assistant-text', content: 'a draft', timestamp: 2 },
        { id: 'n2', type: 'run-node', content: '', nodeBoundary: 'end', nodeId: 'draft', timestamp: 3 },
      ] as LiveConversationEntry[],
      {},
    );
    expect(out.map((i) => i.id)).toEqual(['a1']);
    expect(out[0]!.segment?.nodeId).toBe('draft');
    expect(out[0]!.segment?.originKind).toBe('workflow');
  });

  // §27.2 — the live mirror of the §26.I record-side tie fix. `nextEntryId` zero-pads (`e-000010`), so a
  // run with >9 entries sharing one millisecond sorts e-000002 < e-000012 (an UNPADDED `e-12` < `e-2`
  // would sort the node `end` boundary BEFORE the body and drop the bracketed items outside the segment).
  it('a >9-entry live run on one timestamp brackets correctly with padded ids', () => {
    const T = 5;
    const pad = (n: number) => `e-${String(n).padStart(6, '0')}`;
    const entries: LiveConversationEntry[] = [
      { id: pad(1), type: 'run-node', content: '', nodeBoundary: 'start', nodeId: 'n1', nodeKind: 'llm', nodeLabel: 'n1', timestamp: T },
    ];
    for (let i = 2; i <= 11; i++) {
      entries.push({ id: pad(i), type: 'assistant-text', content: `step-${i}`, timestamp: T });
    }
    entries.push({ id: pad(12), type: 'run-node', content: '', nodeBoundary: 'end', nodeId: 'n1', timestamp: T });
    const out = projectLiveAgentActivity(entries, {});
    // All 10 assistant items survive (boundaries dropped), in EMISSION order, bracketed inside n1 — the
    // `end` at e-000012 sorts AFTER e-000002, not before it.
    expect(out.map((i) => i.content)).toEqual(Array.from({ length: 10 }, (_, k) => `step-${k + 2}`));
    expect(out.every((i) => i.segment?.nodeId === 'n1')).toBe(true);
  });

  it('projectUnifiedThread brackets PROGRESS node-boundary events from the record side', () => {
    const out = projectUnifiedThread([
      ev({ id: 's', kind: 'PROGRESS', occurredAt: '2026-01-01T00:00:01Z', originator: 'agent', attributes: { nodeBoundary: 'start', nodeId: 'act', nodeKind: 'tool', label: 'act' } }),
      ev({ id: 't1', kind: 'TOOL_ACTIVITY', occurredAt: '2026-01-01T00:00:02Z', originator: 'agent', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } }),
      ev({ id: 'e', kind: 'PROGRESS', occurredAt: '2026-01-01T00:00:03Z', originator: 'agent', attributes: { nodeBoundary: 'end', nodeId: 'act' } }),
    ]);
    expect(out.map((i) => i.id)).toEqual(['t1']);
    expect(out[0]!.segment?.nodeId).toBe('act');
  });

  // Tempdoc S4a (risk-review finding #1) — forward-tolerant UNKNOWN-kind projection: a not-yet-shipped
  // backend event kind (e.g. SEARCH) must degrade to a generic item, never blank/omit the thread.
  describe('an UNKNOWN-kind event (S4a forward tolerance)', () => {
    it('projects to a secondary-prominence generic item labelled from the humanized rawKind', () => {
      const out = projectUnifiedThread([
        ev({ id: 'u1', kind: 'USER_MESSAGE', occurredAt: '2026-01-01T00:00:01Z', content: 'find invoices' }),
        ev({
          id: 's1',
          kind: 'UNKNOWN',
          occurredAt: '2026-01-01T00:00:02Z',
          originator: 'agent',
          rawKind: 'SEARCH',
          content: 'raw backend content',
          attributes: { query: 'invoices' },
        }),
        ev({ id: 'a1', kind: 'ASSISTANT_MESSAGE', occurredAt: '2026-01-01T00:00:03Z', content: 'done' }),
      ]);
      // Three events in -> three items out; an unrecognized kind never drops sibling events or blanks
      // the thread (the risk-review finding this closes).
      expect(out).toHaveLength(3);
      const unknown = out.find((i) => i.id === 's1')!;
      expect(unknown.kind).toBe('progress'); // carried as the existing kind (see KIND_MAP comment)
      expect(unknown.prominence).toBe('secondary'); // NOT the ambient default for `progress`
      expect(unknown.content).toBe('Search'); // 'SEARCH' humanized
      expect(unknown.attributes.rawKind).toBe('SEARCH');
      expect(unknown.attributes.query).toBe('invoices'); // original attributes passthrough
    });

    it('humanizes a multi-word rawKind (SEARCH_RESULT -> Search result)', () => {
      const out = projectUnifiedThread([
        ev({ id: 's1', kind: 'UNKNOWN', occurredAt: '2026-01-01T00:00:01Z', rawKind: 'SEARCH_RESULT' }),
      ]);
      expect(out[0]!.content).toBe('Search result');
    });
  });
});

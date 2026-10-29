import { describe, it, expect } from 'vitest';
import {
  projectUnifiedThread,
  assignRunSegments,
  type ThreadEvent,
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

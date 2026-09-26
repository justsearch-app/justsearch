/**
 * Slice 3a.1.9 — Subscription strategy registry tests.
 *
 * Covers all five SSE-driven Category strategies under canonical
 * lifecycle + update payload shapes drawn from the verified live
 * wire (slice 3a.1.9 §A.0 probes).
 */

import { describe, expect, it } from 'vitest';
import type { Resource } from '../../api/types/registry';
import type { SseEnvelope } from '../streaming/envelope-types';
import {
  hasStrategyFor,
  strategyFor,
  type TabularData,
  type EventStreamData,
  type HistoryData,
} from './subscriptionStrategy';

function envelope(
  frameKind: 'LIFECYCLE' | 'UPDATE',
  seq: number,
  payload: unknown,
): SseEnvelope {
  return {
    streamId: 'surface:test',
    frameKind,
    seq,
    ts: '2026-05-06T00:00:00Z',
    payload: payload as Record<string, unknown>,
    resumeToken: `token-${seq}`,
  };
}

function tabularResource(): Resource {
  return {
    id: 'core.indexing-jobs',
    presentation: {
      labelKey: 'k.label',
      descriptionKey: 'k.desc',
      iconHint: null,
      category: null,
    },
    schema: 'https://ssot.justsearch/v1/schemas/x.v1.json',
    category: 'TABULAR',
    subscriptionMode: 'SSE_STREAM',
    endpoint: '/api/x/stream',
    kind: 'x-table',
    history: null,
    recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
    itemOperations: [],
    collectionOperations: [],
    primaryKey: 'pathHash',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

function stateResource(): Resource {
  return { ...tabularResource(), category: 'STATE', primaryKey: '' };
}

function eventStreamResource(primaryKey = ''): Resource {
  return { ...tabularResource(), category: 'EVENT_STREAM', primaryKey };
}

function historyResource(primaryKey = ''): Resource {
  return { ...tabularResource(), category: 'HISTORY', primaryKey };
}

describe('subscription strategy registry', () => {
  describe('lookup', () => {
    it('returns a strategy for each (Category × SSE_STREAM) covered', () => {
      expect(hasStrategyFor('STATE', 'SSE_STREAM')).toBe(true);
      expect(hasStrategyFor('EVENT_STREAM', 'SSE_STREAM')).toBe(true);
      expect(hasStrategyFor('HISTORY', 'SSE_STREAM')).toBe(true);
      expect(hasStrategyFor('TABULAR', 'SSE_STREAM')).toBe(true);
      // (Slice 448 phase 6 retired LOG_TAIL — operator-trace surfaces use the
      // sibling DiagnosticChannel primitive; strategy lives in
      // `./diagnosticChannelStrategy.ts`.)
    });

    it('returns null for unsupported combos', () => {
      expect(hasStrategyFor('STATE', 'POLLING')).toBe(false);
      expect(hasStrategyFor('TIMESERIES', 'SSE_STREAM')).toBe(false);
    });

    it('TABULAR strategy throws when primaryKey is blank', () => {
      const r = { ...tabularResource(), primaryKey: '' };
      expect(() => strategyFor(r)).toThrow(/primaryKey/);
    });
  });

  describe('STATE × SSE_STREAM', () => {
    it('snapshot sets value', () => {
      const s = strategyFor(stateResource())!;
      const next = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        value: { mode: 'AUTO' },
      }));
      const data = next.data as { mode: string };
      expect(data.mode).toBe('AUTO');
    });

    it('UPDATE replaces value', () => {
      const s = strategyFor(stateResource())!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        value: { mode: 'AUTO' },
      }));
      st = s.reducer(st, envelope('UPDATE', 2, { value: { mode: 'MANUAL' } }));
      expect((st.data as { mode: string }).mode).toBe('MANUAL');
    });

    it('reset clears value', () => {
      const s = strategyFor(stateResource())!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        value: { x: 1 },
      }));
      st = s.reducer(st, envelope('LIFECYCLE', 2, { kind: 'reset' }));
      expect(st.data).toBeNull();
    });
  });

  describe('EVENT_STREAM × SSE_STREAM', () => {
    it('snapshot sets entries; UPDATE appends', () => {
      const s = strategyFor(eventStreamResource())!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        entries: [{ id: 'a' }, { id: 'b' }],
      }));
      expect((st.data as EventStreamData).events).toHaveLength(2);
      st = s.reducer(st, envelope('UPDATE', 2, { id: 'c' }));
      const events = (st.data as EventStreamData<{ id: string }>).events;
      expect(events.map((e) => e.id)).toEqual(['a', 'b', 'c']);
    });

    it('declared primaryKey deduplicates snapshot/live rows and keeps distinct invocations', () => {
      const s = strategyFor(eventStreamResource('operationKey'))!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        entries: [
          { operationId: 'core.x', operationKey: 'inv-a', status: 'RUNNING' },
          { operationId: 'core.x', operationKey: 'inv-b', status: 'DONE' },
          // Duplicate snapshot row: the later value for the declared key wins.
          { operationId: 'core.x', operationKey: 'inv-a', status: 'RETRYING' },
        ],
      }));
      st = s.reducer(st, envelope('UPDATE', 2, {
        operationId: 'core.x', operationKey: 'inv-a', status: 'SUCCEEDED',
      }));
      st = s.reducer(st, envelope('UPDATE', 3, {
        operationId: 'core.x', operationKey: 'inv-c', status: 'RUNNING',
      }));

      const events = (st.data as EventStreamData<{
        operationId: string;
        operationKey: string;
        status: string;
      }>).events;
      expect(events).toHaveLength(3);
      expect(events.map((event) => event.operationKey)).toEqual(['inv-a', 'inv-b', 'inv-c']);
      expect(events[0]?.status).toBe('SUCCEEDED');
    });

    it('declared primaryKey still appends rows without usable identities independently', () => {
      const s = strategyFor(eventStreamResource('operationKey'))!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        entries: [
          { label: 'missing-1' },
          { operationKey: null, label: 'null-1' },
          { operationKey: '', label: 'blank-1' },
        ],
      }));
      st = s.reducer(st, envelope('UPDATE', 2, { label: 'missing-2' }));
      st = s.reducer(st, envelope('UPDATE', 3, { operationKey: null, label: 'null-2' }));
      const events = (st.data as EventStreamData<{ label: string }>).events;
      expect(events.map((event) => event.label)).toEqual([
        'missing-1', 'null-1', 'blank-1', 'missing-2', 'null-2',
      ]);
    });

    it('keeps scalar identities distinct and does not coerce non-scalars into one key', () => {
      const s = strategyFor(eventStreamResource('identity'))!;
      const st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        entries: [
          { identity: 1, label: 'number-one' },
          { identity: '1', label: 'string-one' },
          { identity: false, label: 'false' },
          { identity: { value: 1 }, label: 'object-one' },
          { identity: { value: 1 }, label: 'object-two' },
          { identity: [1], label: 'array-one' },
          { identity: [1], label: 'array-two' },
        ],
      }));
      const events = (st.data as EventStreamData<{ identity: unknown; label: string }>).events;
      expect(events.map((event) => event.label)).toEqual([
        'number-one', 'string-one', 'false',
        'object-one', 'object-two', 'array-one', 'array-two',
      ]);
    });

    it('declared primaryKey keeps its bounded 200-entry tail', () => {
      const s = strategyFor(eventStreamResource('operationKey'))!;
      let st = s.initialState;
      for (let i = 0; i < 201; i++) {
        st = s.reducer(st, envelope('UPDATE', i + 1, { operationKey: `inv-${i}` }));
      }
      const events = (st.data as EventStreamData<{ operationKey: string }>).events;
      expect(events).toHaveLength(200);
      expect(events[0]?.operationKey).toBe('inv-1');
      expect(events.at(-1)?.operationKey).toBe('inv-200');
    });

    it('cap-bounded ring evicts oldest', () => {
      const s = strategyFor(eventStreamResource())!;
      let st = s.initialState;
      // Default cap = 200; push 201 entries.
      for (let i = 0; i < 201; i++) {
        st = s.reducer(st, envelope('UPDATE', i + 1, { id: i }));
      }
      const events = (st.data as EventStreamData<{ id: number }>).events;
      expect(events).toHaveLength(200);
      const first = events[0];
      const last = events[199];
      expect(first && first.id).toBe(1);
      expect(last && last.id).toBe(200);
    });
  });

  describe('HISTORY × SSE_STREAM', () => {
    it('snapshot+UPDATE matches the slice 444b OperationHistory shape', () => {
      const s = strategyFor(historyResource())!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        entries: [{ operationId: 'core.x' }],
      }));
      st = s.reducer(st, envelope('UPDATE', 2, { operationId: 'core.y' }));
      const entries = (st.data as HistoryData<{ operationId: string }>).entries;
      expect(entries.map((e) => e.operationId)).toEqual(['core.x', 'core.y']);
    });

    it('uses the declared primaryKey for snapshot/update convergence', () => {
      const s = strategyFor(historyResource('operationKey'))!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        entries: [
          { operationRef: 'core.same', operationKey: 'inv-a', outcome: 'RUNNING' },
          { operationRef: 'core.same', operationKey: 'inv-b', outcome: 'FAILED' },
        ],
      }));
      st = s.reducer(st, envelope('UPDATE', 2, {
        operationRef: 'core.same', operationKey: 'inv-a', outcome: 'SUCCEEDED',
      }));
      const entries = (st.data as HistoryData<{
        operationRef: string;
        operationKey: string;
        outcome: string;
      }>).entries;
      expect(entries).toHaveLength(2);
      expect(entries.map((entry) => entry.operationKey)).toEqual(['inv-a', 'inv-b']);
      expect(entries[0]?.outcome).toBe('SUCCEEDED');
    });
  });

  describe('TABULAR × SSE_STREAM', () => {
    it('snapshot loads items keyed by primaryKey', () => {
      const s = strategyFor(tabularResource())!;
      const items = [
        { pathHash: 'h1', state: 'PENDING' },
        { pathHash: 'h2', state: 'PROCESSING' },
      ];
      const st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        items,
      }));
      const data = st.data as TabularData;
      expect(data.items.size).toBe(2);
      expect(data.items.get('h1')).toEqual(items[0]);
    });

    it('Insert/Update upserts by primaryKey (slice 445 wire shape)', () => {
      const s = strategyFor(tabularResource())!;
      let st = s.initialState;
      st = s.reducer(st, envelope('UPDATE', 1, {
        row: { pathHash: 'h1', state: 'PENDING' },
      }));
      st = s.reducer(st, envelope('UPDATE', 2, {
        row: { pathHash: 'h1', state: 'PROCESSING' },
      }));
      const data = st.data as TabularData<{ pathHash: string; state: string }>;
      const row = data.items.get('h1');
      expect(row).toBeDefined();
      expect(row && row.state).toBe('PROCESSING');
      expect(data.items.size).toBe(1);
    });

    it('Delete removes by primaryKey value in payload', () => {
      const s = strategyFor(tabularResource())!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        items: [{ pathHash: 'h1', state: 'DONE' }],
      }));
      st = s.reducer(st, envelope('UPDATE', 2, { pathHash: 'h1' }));
      expect((st.data as TabularData).items.size).toBe(0);
    });

    it('SnapshotReplaced clears and reloads', () => {
      const s = strategyFor(tabularResource())!;
      let st = s.reducer(s.initialState, envelope('LIFECYCLE', 1, {
        kind: 'snapshot',
        items: [{ pathHash: 'h1' }, { pathHash: 'h2' }],
      }));
      st = s.reducer(st, envelope('UPDATE', 2, {
        items: [{ pathHash: 'h3' }],
      }));
      const data = st.data as TabularData;
      expect(data.items.size).toBe(1);
      expect(data.items.has('h3')).toBe(true);
      expect(data.items.has('h1')).toBe(false);
    });
  });

  // LOG_TAIL × SSE_STREAM tests deleted in slice 448 phase 6 — Category.LOG_TAIL
  // retired per CONFLICT-LEDGER C-012 path-b. Operator-trace surface tests live
  // alongside the DiagnosticChannel substrate at
  // `./diagnosticChannelStrategy.test.ts`.

  describe('catalog version tracking', () => {
    it('every reducer call advances catalogVersion', () => {
      const s = strategyFor(eventStreamResource())!;
      let st = s.initialState;
      expect(st.catalogVersion).toBe(0);
      st = s.reducer(st, envelope('LIFECYCLE', 5, { kind: 'connected' }));
      expect(st.catalogVersion).toBe(5);
    });
  });
});

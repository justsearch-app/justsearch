// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { Resource } from '../../api/types/registry';
import type { SseEnvelope } from '../streaming/envelope-types';
import { strategyFor, type StrategyState, type TabularData } from './subscriptionStrategy';

function resource(id = 'core.indexing-jobs'): Resource {
  return {
    id,
    presentation: { labelKey: 'jobs', descriptionKey: 'jobs', iconHint: null, category: null },
    schema: 'https://ssot.justsearch/v1/schemas/indexing-job-view.v1.json',
    category: 'TABULAR', subscriptionMode: 'SSE_STREAM',
    endpoint: '/api/indexing-jobs/stream', kind: 'indexing-jobs-table',
    history: null, recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
    itemOperations: [], collectionOperations: [], primaryKey: 'pathHash',
    audience: 'USER', consumers: [], role: 'PRODUCT',
  };
}

type Job = { pathHash: string; state: string; lastUpdatedMs: number };
const job = (pathHash: string, state: string, lastUpdatedMs = 1): Job => ({ pathHash, state, lastUpdatedMs });
const frame = (seq: number, payload: Record<string, unknown>, frameKind: 'LIFECYCLE' | 'UPDATE' = 'UPDATE'): SseEnvelope => ({
  streamId: 'surface:indexing-jobs', frameKind, seq, payload,
  ts: new Date(0).toISOString(), resumeToken: `token-${seq}`,
});
const rows = (state: StrategyState<unknown>) => (state.data as TabularData<Job>).items;
const entries = (state: StrategyState<unknown>) => [...rows(state)].sort(([a], [b]) => a.localeCompare(b));

describe('indexing-jobs bounded browser window', () => {
  it('live deltas and a reload of the same final queue converge, including retry and eviction', () => {
    const strategy = strategyFor(resource())!;
    const running = job('running', 'PROCESSING');
    let live = strategy.reducer(strategy.initialState, frame(1, {
      kind: 'snapshot', items: [running, job('requeued', 'FAILED'), job('completed', 'PROCESSING'), job('deleted', 'PROCESSING')],
    }, 'LIFECYCLE'));
    const original = live;
    live = strategy.reducer(live, frame(2, { kind: 'update', row: job('requeued', 'PENDING', 2) }));
    live = strategy.reducer(live, frame(3, { kind: 'update', row: job('completed', 'DONE', 3) }));
    live = strategy.reducer(live, frame(4, { kind: 'delete', primaryKeyValue: 'deleted' }));
    const failures = Array.from({ length: 200 }, (_, i) => job(`failed-${i}`, i % 2 ? 'FAILED' : 'RETRY_EXHAUSTED', 10 + i));
    for (const [i, row] of failures.entries()) live = strategy.reducer(live, frame(10 + i, { kind: 'insert', row }));
    const refreshed = job('failed-0', 'FAILED', 210);
    live = strategy.reducer(live, frame(210, { kind: 'update', row: refreshed }));
    const newest = job('failed-200', 'RETRY_EXHAUSTED', 211);
    live = strategy.reducer(live, frame(211, { kind: 'insert', row: newest }));
    // The head evicts by failure update order: refreshing failed-0 makes failed-1 oldest.
    const finalRows = [running, ...failures.slice(2), refreshed, newest].sort((a, b) => b.lastUpdatedMs - a.lastUpdatedMs);
    const reloaded = strategy.reducer(strategy.initialState, frame(212, { kind: 'snapshot', items: finalRows }, 'LIFECYCLE'));
    expect(entries(live)).toEqual(entries(reloaded));
    expect(rows(live).size).toBe(201);
    expect(rows(live).has('requeued')).toBe(false);
    expect(rows(live).has('completed')).toBe(false);
    expect(rows(live).has('deleted')).toBe(false);
    expect(rows(live).has('failed-1')).toBe(false);
    expect(rows(live).has('failed-0')).toBe(true);
    expect(rows(original).get('requeued')?.state).toBe('FAILED');
  });

  it('keeps every processing row, evicts the oldest snapshot failure, and replaces on resync', () => {
    const strategy = strategyFor(resource())!;
    const processing = Array.from({ length: 256 }, (_, i) => job(`running-${i}`, 'PROCESSING'));
    const failures = Array.from({ length: 200 }, (_, i) => job(`failed-${i}`, 'FAILED', i + 1));
    let state = strategy.reducer(strategy.initialState, frame(1, { kind: 'snapshot', items: [...processing, ...[...failures].reverse()] }, 'LIFECYCLE'));
    state = strategy.reducer(state, frame(2, { row: job('latest', 'FAILED', 201) }));
    expect(rows(state).size).toBe(456);
    expect(rows(state).has('failed-0')).toBe(false);
    for (const row of processing) expect(rows(state).get(row.pathHash)).toEqual(row);
    state = strategy.reducer(state, frame(3, { kind: 'snapshot-replaced', items: [job('replacement', 'FAILED', 300)] }));
    expect([...rows(state).keys()]).toEqual(['replacement']);
    state = strategy.reducer(state, frame(4, { kind: 'reset' }, 'LIFECYCLE'));
    expect(rows(state).size).toBe(0);
    state = strategy.reducer(state, frame(5, { kind: 'snapshot', items: [job('again', 'PROCESSING')] }, 'LIFECYCLE'));
    expect([...rows(state).keys()]).toEqual(['again']);
  });

  it('restores failure age from a head snapshot whose row insertion order is mixed', () => {
    const strategy = strategyFor(resource())!;
    const failures = Array.from({ length: 200 }, (_, i) => job(`failed-${i + 1}`, 'FAILED', i + 1));
    // Head row insertion order retains the initial newest-first rows, then appends live rows.
    const snapshot = [...failures.slice(0, 199).reverse(), failures[199]];
    let state = strategy.reducer(strategy.initialState, frame(1, { kind: 'snapshot', items: snapshot }, 'LIFECYCLE'));
    state = strategy.reducer(state, frame(2, { kind: 'insert', row: job('latest', 'FAILED', 201) }));
    expect(rows(state).has('failed-1')).toBe(false);
    expect(rows(state).has('failed-200')).toBe(true);
    expect(rows(state).size).toBe(200);
  });

  it('leaves other tabular resources unfiltered and uncapped', () => {
    const strategy = strategyFor(resource('core.other-table'))!;
    let state = strategy.reducer(strategy.initialState, frame(1, { kind: 'snapshot', items: [job('pending', 'PENDING'), job('done', 'DONE')] }, 'LIFECYCLE'));
    for (let i = 0; i < 201; i++) state = strategy.reducer(state, frame(i + 2, { kind: 'insert', row: job(`failed-${i}`, 'FAILED') }));
    expect(rows(state).size).toBe(203);
  });
});

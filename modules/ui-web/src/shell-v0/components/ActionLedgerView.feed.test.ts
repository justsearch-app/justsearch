// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * S6 Activity — the one feed. The ledger stream, the operation-history stream and the Effect
 * Journal are joined by exact identities; each source's state is said; the quick and advanced
 * filters classify by the declared table; the list is bounded by what the sources retain.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './ActionLedgerView.js';
import type { ActionLedgerView } from './ActionLedgerView.js';
import {
  recordEffect,
  restoreJournalFromStorage,
  __resetJournalForTest,
} from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetRecallCursor, getSeenCursor, markSeen } from '../substrates/recall/recallCursor.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import type { Resource } from '../../api/types/registry.js';
import { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { openActionLedgerStream } from '../operations/ActionLedgerClient.js';

class FakeEventSource {
  listeners: Record<string, Array<(e: unknown) => void>> = {};
  readyState = 1;
  constructor(public url: string) {}
  addEventListener(type: string, fn: (e: unknown) => void): void {
    (this.listeners[type] ??= []).push(fn);
  }
  removeEventListener(type: string, fn: (e: unknown) => void): void {
    this.listeners[type] = (this.listeners[type] ?? []).filter((f) => f !== fn);
  }
  close(): void {}
  frame(frameKind: 'LIFECYCLE' | 'UPDATE', payload: unknown): void {
    seq += 1;
    const data = JSON.stringify({ streamId: 'test', frameKind, seq, ts: '2026-11-01T00:00:00Z', resumeToken: `t${seq}`, payload });
    for (const fn of this.listeners['frame'] ?? []) fn({ data });
  }
  snapshot(entries: unknown[]): void { this.frame('LIFECYCLE', { kind: 'snapshot', entries }); }
  lifecycle(kind: string): void { this.frame('LIFECYCLE', { kind }); }
  update(row: unknown): void { this.frame('UPDATE', row); }
  fail(): void { for (const fn of this.listeners['error'] ?? []) fn({}); }
}

let seq = 0;
let host: ActionLedgerView;
let ledger: FakeEventSource;
let history: FakeEventSource | undefined;

function historyResource(): Resource {
  return {
    id: 'core.operation-history',
    presentation: { labelKey: 'registry-resource.operation-history.label',
      descriptionKey: 'registry-resource.operation-history.description', iconHint: null, category: null },
    schema: 'https://ssot.justsearch/v1/schemas/operation-history-entry.v1.json',
    category: 'EVENT_STREAM', subscriptionMode: 'SSE_STREAM', endpoint: '/api/operation-history/stream',
    kind: 'operation-history', history: null, recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: false, resolver: null },
    itemOperations: [], collectionOperations: [], primaryKey: 'operationKey', audience: 'USER', consumers: [], role: 'PRODUCT',
  };
}

/** A ledger GET answered by `answer` (default: an empty ledger). */
function mount(answer: () => Promise<unknown[]> = async () => []): ActionLedgerView {
  const el = document.createElement('jf-action-ledger') as ActionLedgerView;
  el.eventSourceFactory = (url) => { ledger = new FakeEventSource(url); return ledger as unknown as EventSource; };
  el.historyEventSourceFactory = (url) => { history = new FakeEventSource(url); return history as unknown as EventSource; };
  el.ledgerFetch = (async () => {
    const entries = await answer();
    return { ok: true, status: 200, json: async () => ({ entries }) } as unknown as Response;
  }) as unknown as typeof fetch;
  document.body.appendChild(el);
  host = el;
  return el;
}

async function settle(el: ActionLedgerView = host): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;
  }
}

/** The operation-history stream the feed opened (the feed reads that source itself). */
function historyStream(): FakeEventSource {
  expect(history, 'the feed opens the operation-history stream').toBeDefined();
  return history!;
}

function q(selector: string, el: ActionLedgerView = host): Element[] {
  return Array.from(el.shadowRoot!.querySelectorAll(selector));
}

/** Keys of the visible feed records (rows, scan rollups). */
function keys(el: ActionLedgerView = host): string[] {
  return q('.item[data-key], [data-testid="ledger-scan-rollup"]', el).map((r) => r.getAttribute('data-key') ?? '');
}

function op(key: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { id: `operation:${key}`, kind: 'operation', occurredAt: '2026-11-01T10:00:00Z', originator: 'user',
    operationId: 'core.reindex', outcome: 'SUCCESS', ...extra };
}

function committed(operationKey: string | null, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { operationId: 'core.reindex', operationKey, outcome: 'SUCCESS', actor: 'head', startTime: '2026-11-01T09:59:59Z',
    endTime: '2026-11-01T10:00:00Z', executionId: null, diagnosticsLink: null,
    provenance: { transport: 'BUTTON', correlationId: null }, ...extra };
}

beforeEach(() => {
  __resetJournalForTest();
  __resetRecallCursor();
  resetResourceCatalog();
  seedResourceCatalog({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [historyResource()] });
  history = undefined;
  seq = 0;
});

afterEach(() => {
  host?.remove();
  document.body.innerHTML = '';
  vi.useRealTimers();
});

describe('one feed: joins by exact identity (A1-A5)', () => {
  it('A1 — a keyed operation in the ledger, the history and the journal is one row carrying all three', async () => {
    recordEffect({ kind: 'invoke-operation', operationId: 'core.reindex' }, CORE_PROVENANCE, { operationKey: 'k1' });
    mount();
    ledger.snapshot([op('k1')]);
    historyStream().snapshot([committed('k1')]);
    await settle();
    expect(q('[data-testid="ledger-row"]')).toHaveLength(1);
    expect(q('.item').map((r) => r.getAttribute('data-sources'))).toEqual(['ledger history journal']);
  });

  it('A2 — same name and time with different keys stay separate; one id delivered by snapshot, update and reconnect is one row', async () => {
    mount();
    historyStream().snapshot([committed('k-a'), committed('k-b')]);
    ledger.snapshot([op('k-c', { operationId: 'core.search-index' })]);
    ledger.update(op('k-c', { operationId: 'core.search-index' }));
    ledger.lifecycle('connected');
    ledger.snapshot([op('k-c', { operationId: 'core.search-index' })]);
    await settle();
    expect(keys().sort()).toEqual(['operation:k-a', 'operation:k-b', 'operation:k-c']);
  });

  it('A3 — unkeyed history joins only the exact legacy identity, raw precision kept; shared legacy identities are kept and disclosed', async () => {
    mount();
    ledger.snapshot([
      { id: 'operation:2026-11-01T10:00:00.123456Z:core.reindex:FAILURE:', kind: 'operation',
        occurredAt: '2026-11-01T10:00:00.123456Z', originator: 'user', operationId: 'core.reindex', outcome: 'FAILURE' },
    ]);
    historyStream().snapshot([
      committed(null, { outcome: 'FAILURE', endTime: '2026-11-01T10:00:00.123456Z' }),
      committed(null, { outcome: 'FAILURE', endTime: '2026-11-01T10:00:00.123Z' }),
      committed(null, { operationId: 'core.rebuild-index', endTime: '2026-11-01T11:00:00Z' }),
      committed(null, { operationId: 'core.rebuild-index', endTime: '2026-11-01T11:00:00Z' }),
    ]);
    await settle();
    const items = q('.item');
    expect(items.map((r) => [r.getAttribute('data-key'), r.getAttribute('data-sources')])).toEqual([
      ['operation:2026-11-01T11:00:00Z:core.rebuild-index:SUCCESS:', 'history'],
      ['operation:2026-11-01T10:00:00.123456Z:core.reindex:FAILURE:', 'ledger history'],
      ['operation:2026-11-01T10:00:00.123Z:core.reindex:FAILURE:', 'history'],
    ]);
    expect(items[0]!.querySelector('[data-note="ambiguous-history"]')).not.toBeNull();
  });

  it('A3 — a legacy local entry whose numeric id matches a backend effect keeps both observations, each disclosing the unproven link', async () => {
    __resetJournalForTest();
    localStorage.setItem('justsearch.effect-journal.v1', JSON.stringify({ version: 1, nextId: 2, entries: [
      { id: 1, invokedAt: '2026-11-01T09:00:00.000Z', effect: { kind: 'open-pane', paneId: 'notes' },
        invokedBy: CORE_PROVENANCE, inverse: null, originator: 'agent' },
    ] }));
    restoreJournalFromStorage();
    mount();
    ledger.snapshot([{ id: 'fe-effect:1', kind: 'effect', occurredAt: '2026-11-01T09:30:00Z', originator: 'agent',
      effectKind: 'set-search-query', subject: '' }]);
    await settle();
    const items = q('.item');
    expect(items.map((r) => r.getAttribute('data-sources')).sort()).toEqual(['journal', 'ledger']);
    for (const item of items) expect(item.querySelector('[data-note="ambiguous-legacy"]')).not.toBeNull();
  });

  it('A4 — a local effect without a ledger copy reads "Recorded locally"; its later delivery joins it without a second row', async () => {
    const entry = recordEffect({ kind: 'open-pane', paneId: 'notes' }, CORE_PROVENANCE, { originator: 'agent' });
    mount();
    ledger.snapshot([]);
    await settle();
    expect(q('.item').map((r) => r.getAttribute('data-sources'))).toEqual(['journal']);
    expect(q('[data-note="recorded-locally"]')).toHaveLength(1);
    ledger.update({ id: entry.ledgerId, kind: 'effect', occurredAt: entry.invokedAt, originator: 'agent', effectKind: 'open-pane', subject: '' });
    await settle();
    expect(q('.item').map((r) => r.getAttribute('data-sources'))).toEqual(['ledger journal']);
    expect(q('[data-note="recorded-locally"]')).toHaveLength(0);
  });

  it('A5 — a smaller reconnect snapshot drops what the Engine no longer holds; retained history partners stay', async () => {
    mount();
    ledger.snapshot([op('k5'), { id: 'gate:1', kind: 'gate', occurredAt: '2026-11-01T10:01:00Z', originator: 'agent',
      operationId: 'core.reindex', disposition: 'DENIED', gateBehavior: 'TYPED_CONFIRM' },
    op('evicted', { operationId: 'core.search-index' })]);
    historyStream().snapshot([committed('k5')]);
    ledger.lifecycle('connected');
    ledger.snapshot([{ id: 'gate:1', kind: 'gate', occurredAt: '2026-11-01T10:01:00Z', originator: 'agent',
      operationId: 'core.reindex', disposition: 'DENIED', gateBehavior: 'TYPED_CONFIRM' }]);
    await settle();
    expect(q('.item').map((r) => [r.getAttribute('data-key'), r.getAttribute('data-sources')]))
      .toEqual([['gate:1', 'ledger'], ['operation:k5', 'history']]);
  });

  it('A6 — the bounded window is disclosed and no older-records control is offered', async () => {
    mount();
    ledger.snapshot([op('k6')]);
    await settle();
    expect(q('[data-testid="feed-window-notice"]')[0]?.textContent).toContain('Older records may not be loaded');
    expect(q('button').some((b) => /load older|older/i.test(b.textContent ?? ''))).toBe(false);
  });
});

describe('first load and reconnect arbitration (challenger 4)', () => {
  it('a connected or heartbeat frame alone never claims an empty ledger', async () => {
    mount(() => new Promise(() => {}));
    ledger.lifecycle('connected');
    ledger.lifecycle('heartbeat');
    await settle();
    expect(q('[data-testid="ledger-empty"]')).toHaveLength(0);
    expect(q('[data-testid="ledger-loading"]')).toHaveLength(1);
    ledger.snapshot([]);
    await settle();
    expect(q('[data-testid="ledger-empty"]')[0]?.textContent).toContain('No activity in loaded records');
  });

  it('an UPDATE that arrived on the connection before its snapshot survives the snapshot', async () => {
    mount();
    ledger.lifecycle('connected');
    ledger.update(op('late', { occurredAt: '2026-11-01T10:05:00Z' }));
    ledger.snapshot([op('older')]);
    await settle();
    expect(keys().sort()).toEqual(['operation:late', 'operation:older']);
  });

  it('the GET seed stands in before the stream, and gives way to its first snapshot', async () => {
    mount(async () => [op('seeded')]);
    await settle();
    expect(keys()).toEqual(['operation:seeded']);
    ledger.snapshot([op('streamed')]);
    await settle();
    expect(keys()).toEqual(['operation:streamed']);
  });

  it('a reset discards the rows, and a GET started before the reset cannot resurrect them', async () => {
    let answer: (rows: unknown[]) => void = () => {};
    mount(() => new Promise((resolve) => { answer = resolve; }));
    ledger.snapshot([op('before-reset')]);
    ledger.lifecycle('reset');
    answer([op('stale-read')]);
    await settle();
    expect(keys()).toEqual([]);
    expect(q('[data-testid="ledger-loading"]')).toHaveLength(1);
  });
  it('a ledger UPDATE no snapshot has contained survives a reconnect snapshot that lacks it; only a reset voids it', async () => {
    mount();
    ledger.snapshot([op('older')]);
    ledger.update(op('newer', { occurredAt: '2026-11-01T10:05:00Z' }));
    ledger.lifecycle('connected');
    ledger.snapshot([op('older')]);
    await settle();
    expect(keys().sort()).toEqual(['operation:newer', 'operation:older']);
    ledger.lifecycle('reset');
    ledger.snapshot([op('older')]);
    await settle();
    expect(keys()).toEqual(['operation:older']);
  });

  it('a history snapshot cannot erase a newer UPDATE it lacks; a reset voids it and later snapshots do not bring it back', async () => {
    mount();
    const h = historyStream();
    h.update(committed('k-new', { startTime: '2026-11-01T10:04:59Z', endTime: '2026-11-01T10:05:00Z' }));
    h.snapshot([committed('k-old')]);
    await settle();
    expect(keys()).toEqual(['operation:k-new', 'operation:k-old']);
    h.lifecycle('connected');
    h.snapshot([committed('k-old')]);
    await settle();
    expect(keys()).toEqual(['operation:k-new', 'operation:k-old']);
    h.lifecycle('reset');
    h.snapshot([committed('k-old')]);
    h.lifecycle('connected');
    h.snapshot([committed('k-old')]);
    await settle();
    expect(keys()).toEqual(['operation:k-old']);
  });

  it('a history record a snapshot has contained leaves with a smaller snapshot', async () => {
    mount();
    const h = historyStream();
    h.snapshot([committed('k-old')]);
    h.update(committed('k-gone', { endTime: '2026-11-01T10:05:00Z' }));
    h.lifecycle('connected');
    h.snapshot([committed('k-old'), committed('k-gone', { endTime: '2026-11-01T10:05:00Z' })]);
    h.lifecycle('connected');
    h.snapshot([committed('k-old')]);
    await settle();
    expect(keys()).toEqual(['operation:k-old']);
  });
});

/**
 * The Engine's `/api/shell-events/stream` as `MultiplexedSseWriter` and `SseEnvelopeWriter` send it:
 * on every connect, each stateful channel gets `connected` (no checkpoint yet) and then `snapshot`
 * carrying its rows and the boundary token; while idle the ledger channel sends nothing, and the
 * connection's heartbeat travels on its own `system:shell-events-heartbeat` channel every 15 s.
 */
class EngineShellEvents extends EventTarget {
  readyState = 0;
  private readonly seqs = new Map<string, number>();
  constructor(readonly url: string) { super(); }
  close(): void { this.readyState = 2; }
  private send(streamId: string, frameKind: 'LIFECYCLE' | 'UPDATE', payload: unknown, checkpoint: boolean): void {
    const seq = (this.seqs.get(streamId) ?? 0) + 1;
    this.seqs.set(streamId, seq);
    const resumeToken = checkpoint ? btoa(`${streamId}:${seq}:incarnation-1`) : '';
    const data = JSON.stringify({ streamId, frameKind, seq, ts: new Date().toISOString(), payload, resumeToken });
    this.dispatchEvent(new MessageEvent('frame', { data }));
  }
  /** The connection opens: the ledger channel's `connected`, then (unless held back) its snapshot. */
  open(entries: unknown[] | null): void {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
    this.send('surface:action-ledger', 'LIFECYCLE', { kind: 'connected' }, false);
    if (entries) this.snapshot(entries);
  }
  snapshot(entries: unknown[]): void { this.send('surface:action-ledger', 'LIFECYCLE', { kind: 'snapshot', entries }, true); }
  heartbeat(): void { this.send('system:shell-events-heartbeat', 'LIFECYCLE', { kind: 'heartbeat' }, false); }
  /** The connection really drops (the transport fails: readyState CLOSED, then `error`). */
  drop(): void { this.readyState = 2; this.dispatchEvent(new Event('error')); }
}

describe("the Shell's shared stream, as a real Engine sends it", () => {
  let engine: EngineShellEvents;
  let multiplex: MultiplexedStream;
  let stopBoot: () => void;

  /** The Shell at boot: the shared connection opened, with its own ledger readers already subscribed. */
  function bootShell(): void {
    multiplex = new MultiplexedStream({ url: '/api/shell-events/stream', watchdogStaleMs: 0,
      reconnectBaseMs: 60_000, eventSourceFactory: (url) => (engine = new EngineShellEvents(url)) });
    stopBoot = openActionLedgerStream({ multiplex, onActivity: () => {} });
    multiplex.start();
  }

  function mountActivity(answer: () => Promise<unknown[]>): ActionLedgerView {
    const el = mount(answer);
    el.remove();
    el.multiplex = multiplex;
    document.body.appendChild(el);
    historyStream().snapshot([]);
    return el;
  }

  const ledgerState = (): string | null => q('[data-testid="feed-source-ledger"]')[0]?.getAttribute('data-state') ?? null;

  afterEach(() => { stopBoot(); multiplex.stop(); });

  it('Activity opened on a healthy, idle stream is live at once, every time; a real drop shows last-known', async () => {
    bootShell();
    engine.open([op('earlier')]);
    engine.heartbeat();
    mountActivity(async () => [op('earlier')]);
    await settle();
    engine.heartbeat();
    await settle();
    expect(keys()).toEqual(['operation:earlier']);
    expect(ledgerState(), 'no ledger source notice while the stream is live').toBeNull();
    expect(host.shadowRoot!.textContent).not.toContain('live updates are not connected');

    // Leave Activity and open it again: still nothing new on the ledger channel, still live.
    host.remove();
    mountActivity(async () => [op('earlier')]);
    await settle();
    engine.heartbeat();
    await settle();
    expect(keys()).toEqual(['operation:earlier']);
    expect(ledgerState()).toBeNull();

    engine.drop();
    await settle();
    expect(ledgerState()).toBe('last-known');
    expect(q('[data-testid="feed-source-ledger"]')[0]?.textContent).toContain('live updates are not connected');
    expect(keys()).toEqual(['operation:earlier']);
  });

  it('connected and heartbeat frames alone never claim an empty ledger; the snapshot does', async () => {
    bootShell();
    engine.open(null);
    engine.heartbeat();
    mountActivity(() => new Promise(() => {}));
    await settle();
    engine.heartbeat();
    await settle();
    expect(q('[data-testid="ledger-empty"]')).toHaveLength(0);
    expect(q('[data-testid="ledger-loading"]')).toHaveLength(1);
    engine.snapshot([]);
    await settle();
    expect(q('[data-testid="ledger-empty"]')[0]?.textContent).toContain('No activity in loaded records');
    expect(ledgerState()).toBeNull();
  });
});

describe('per-source states (challenger 5)', () => {
  it('each source says loading, unavailable or last-known; rows from the others still show as partial activity', async () => {
    mount(async () => { throw new Error('refused'); });
    await settle();
    expect(q('[data-testid="feed-source-ledger"]')[0]?.getAttribute('data-state')).toBe('unavailable');
    expect(q('[data-testid="feed-source-history"]')[0]?.getAttribute('data-state')).toBe('loading');
    historyStream().snapshot([committed('kh')]);
    await settle();
    expect(keys()).toEqual(['operation:kh']);
    expect(q('[data-testid="feed-source-history"]')).toHaveLength(0);
    expect(q('[data-testid="feed-source-ledger"]')[0]?.textContent).toMatch(/^Partial activity: Activity ledger could not be read/);
    historyStream().fail();
    await settle();
    expect(q('[data-testid="feed-source-history"]')[0]?.getAttribute('data-state')).toBe('last-known');
    expect(keys()).toEqual(['operation:kh']);
  });

  it('the history stream failing before its first snapshot says it is unavailable, not loading, until a snapshot reads it', async () => {
    mount();
    ledger.snapshot([op('kl')]);
    const first = historyStream();
    first.fail();
    first.lifecycle('connected');
    first.lifecycle('heartbeat');
    await settle();
    const state = q('[data-testid="feed-source-history"]')[0];
    expect(state?.getAttribute('data-state')).toBe('unavailable');
    expect(state?.textContent).toMatch(/^Partial activity: Operation history could not be read\./);
    expect(state?.textContent).not.toContain('loading');
    (q('[data-testid="feed-source-history-retry"]')[0] as HTMLButtonElement).click();
    await settle();
    expect(history).not.toBe(first);
    expect(q('[data-testid="feed-source-history"]')[0]?.getAttribute('data-state')).toBe('loading');
    historyStream().snapshot([committed('kh')]);
    await settle();
    expect(q('[data-testid="feed-source-history"]')).toHaveLength(0);
    expect(keys().sort()).toEqual(['operation:kh', 'operation:kl']);
  });
});

describe('classification and quick filters (A9, A10)', () => {
  const gate = (n: number, disposition: string, operationId = 'core.search-index') => ({ id: `gate:${n}`, kind: 'gate',
    occurredAt: `2026-11-01T10:0${n}:00Z`, originator: 'agent', operationId, disposition, gateBehavior: 'TYPED_CONFIRM' });
  const index = (n: number, state: string, collection: string) => ({ id: `index:${n}`, kind: 'index',
    occurredAt: `2026-11-01T11:0${n}:00Z`, originator: 'system', collection, state, pathHash: `h${n}` });
  const scan = (n: number, outcome: string, docsFailed = 0) => ({ id: `scan:${n}`, kind: 'operation', operationId: 'core.scan-root',
    occurredAt: `2026-11-01T12:0${n}:00Z`, originator: 'system', outcome, scanId: `s${n}`, collection: 'docs', docsDone: 3, docsFailed });

  function choose(quick: string): void {
    const button = host.shadowRoot!.querySelector(`[data-quick="${quick}"]`);
    expect(button, `the ${quick} quick filter`).not.toBeNull();
    (button as HTMLButtonElement).click();
  }

  it('A9 — Problems holds failures, partial or failing scans, failed indexing, denied and expired gates and data errors', async () => {
    recordEffect({ kind: 'toast', message: 'Disk nearly full', severity: 'warning' }, CORE_PROVENANCE, { originator: 'system' });
    recordEffect({ kind: 'open-pane', paneId: 'p' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
    recordEffect({ kind: 'open-pane', paneId: 'q' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'accepted' });
    mount();
    ledger.snapshot([
      op('fail', { outcome: 'FAILURE' }), op('ok'), op('undone', { outcome: 'UNDONE' }),
      scan(1, 'PARTIAL'), scan(2, 'COMPLETED', 2), scan(3, 'COMPLETED'), scan(4, 'STARTED'),
      index(1, 'FAILED', 'a'), index(2, 'RETRY_EXHAUSTED', 'b'), index(3, 'DONE', 'c'),
      gate(1, 'DENIED'), gate(2, 'EXPIRED'), gate(3, 'GATED'), gate(4, 'APPROVED'), gate(5, 'WITHDRAWN'),
      { id: 'grant:1', kind: 'grant', occurredAt: '2026-11-01T13:00:00Z', originator: 'user', action: 'ISSUED', subject: 'core.search-index' },
      { id: 'effect:err', kind: 'effect', occurredAt: '2026-11-01T13:01:00Z', originator: 'agent', effectKind: 'data-error', subject: '' },
      { id: 'navigation:1', kind: 'navigation', occurredAt: '2026-11-01T13:02:00Z', originator: 'agent', targetSurface: 'core.search' },
    ]);
    await settle();
    choose('problems');
    await settle();
    const shown = keys();
    expect(shown.filter((k) => !k.startsWith('local:') && !k.startsWith('fe-effect:')).sort())
      .toEqual(['effect:err', 'gate:1', 'gate:2', 'index:1', 'index:2', 'operation:fail', 'scan:1', 'scan:2'].sort());
    const local = q('.item[data-sources="journal"]').map((r) => r.textContent ?? '');
    expect(local).toHaveLength(2);
    expect(local.some((t) => t.includes('Proposal rejected'))).toBe(true);
    expect(local.some((t) => t.includes('Proposal accepted'))).toBe(false);
  });

  it('A9 — Indexing holds index events and the explicit indexing operations, by declaration or agent wire name, and their gates and grants', async () => {
    mount();
    ledger.snapshot([
      op('reindex'), op('bulk', { operationId: 'core_bulk_reindex', originator: 'agent' }), op('search', { operationId: 'core.search-index' }),
      index(1, 'DONE', 'a'), gate(1, 'GATED', 'core.apply-excludes'), gate(2, 'GATED', 'core.search-index'),
      { id: 'grant:1', kind: 'grant', occurredAt: '2026-11-01T13:00:00Z', originator: 'user', action: 'ISSUED', subject: 'core.add-watched-root' },
      { id: 'navigation:1', kind: 'navigation', occurredAt: '2026-11-01T13:02:00Z', originator: 'agent', targetSurface: 'core.search' },
    ]);
    await settle();
    choose('indexing');
    await settle();
    expect(keys().sort()).toEqual(['gate:1', 'grant:1', 'index:1', 'operation:bulk', 'operation:reindex'].sort());
  });

  it('A9 — an operation with no recorded outcome stays visible and says so', async () => {
    mount();
    ledger.snapshot([op('unknown', { outcome: undefined })]);
    await settle();
    expect(q('[data-note="outcome-unavailable"]')).toHaveLength(1);
  });

  it('A10 — failed per-file indexing is reachable in Problems and Indexing although routine; All keeps the routine default', async () => {
    mount();
    ledger.snapshot([index(1, 'FAILED', 'a'), op('ok', { operationId: 'core.search-index' })]);
    await settle();
    expect(keys()).toEqual(['operation:ok']);
    choose('problems');
    await settle();
    expect(keys()).toEqual(['index:1']);
    choose('indexing');
    await settle();
    expect(keys()).toEqual(['index:1']);
  });

  it('A10 — a burst never reads as an unqualified success: it counts failures and expands to every member', async () => {
    mount();
    ledger.snapshot([index(1, 'DONE', 'docs'), index(2, 'FAILED', 'docs'), index(3, 'DONE', 'docs')]);
    host.filter = { ...host.filter, quick: 'indexing' };
    await settle();
    const burst = q('[data-testid="ledger-burst"]');
    expect(burst).toHaveLength(1);
    expect(burst[0]!.textContent).toContain('Indexed 2 · 1 failed · docs');
    (burst[0]!.querySelector('[data-testid="ledger-burst-expand"]') as HTMLButtonElement).click();
    await settle();
    expect(q('[data-testid="ledger-burst-member"]')).toHaveLength(3);
  });

  it('A9 — a burst counts a record only as its state establishes; an unknown state is counted and named, never indexed', async () => {
    mount();
    ledger.snapshot([index(1, 'DONE', 'docs'), index(2, 'PAUSED', 'docs')]);
    host.filter = { ...host.filter, quick: 'indexing' };
    await settle();
    const burst = q('[data-testid="ledger-burst"]');
    expect(burst).toHaveLength(1);
    expect(burst[0]!.textContent).not.toContain('Indexed 2');
    expect(burst[0]!.textContent).toContain('Indexed 1 · 1 with unknown state · docs');
    (burst[0]!.querySelector('[data-testid="ledger-burst-expand"]') as HTMLButtonElement).click();
    await settle();
    const members = q('[data-testid="ledger-burst-member"]').map((m) => m.textContent ?? '');
    expect(members).toHaveLength(2);
    expect(members.some((m) => m.includes('Index state unknown (PAUSED) · docs'))).toBe(true);
    expect(members.some((m) => m.includes('Indexed · docs'))).toBe(true);
  });

  it('A9 — a resolved file name keeps what the state established: failed, gave up and unknown never read as indexed', async () => {
    mount();
    host.resolveHash = async (_resource, hash) => `C:/corpus/${hash}.pdf`;
    ledger.snapshot([index(1, 'FAILED', 'a'), index(2, 'RETRY_EXHAUSTED', 'b'), index(3, 'PAUSED', 'c'), index(4, 'DONE', 'd')]);
    host.filter = { ...host.filter, quick: 'indexing' };
    await settle();
    await settle();
    const label = (key: string): string => (q(`.item[data-key="${key}"]`)[0]?.textContent ?? '').replace(/\s+/g, ' ');
    expect(label('index:1')).toContain('Index failed · corpus/h1.pdf');
    expect(label('index:2')).toContain('Index gave up · corpus/h2.pdf');
    expect(label('index:3')).toContain('Index state unknown (PAUSED) · corpus/h3.pdf');
    expect(label('index:4')).toContain('Indexed · corpus/h4.pdf');
  });

  it('a command palette use is not one of Your actions: its journaled no-op stays with the routine rows of All', async () => {
    // A palette action that does its own work returns a no-op, which applyEffect journals as the
    // user's, and the effect ingest gives the ledger its copy.
    const noop = recordEffect({ kind: 'noop' }, CORE_PROVENANCE, { originator: 'user' });
    recordEffect({ kind: 'navigate', to: 'justsearch://surface/core.library-surface' }, CORE_PROVENANCE, { originator: 'user' });
    mount();
    ledger.snapshot([op('mine'), { id: noop.ledgerId, kind: 'effect', occurredAt: noop.invokedAt, originator: 'user',
      effectKind: 'noop', subject: '' }]);
    await settle();
    choose('mine');
    await settle();
    const labels = q('.item').map((r) => r.textContent ?? '');
    expect(labels.some((t) => t.includes('No-op')), 'Your actions lists no no-op').toBe(false);
    expect(keys()).toContain('operation:mine');
    expect(labels.some((t) => /navigate|Navigate/.test(t)), 'witnessed navigation is still revealed').toBe(true);
    host.filter = { ...host.filter, quick: 'all', routine: 'show' };
    await settle();
    expect(q('.item').filter((r) => (r.textContent ?? '').includes('No-op')), 'All with routine shown keeps the no-op').toHaveLength(1);
  });

  it('an explicit advanced routine choice overrides every quick filter, as its label states', async () => {
    mount();
    ledger.snapshot([index(1, 'FAILED', 'a')]);
    host.filter = { ...host.filter, quick: 'problems', routine: 'hide' };
    await settle();
    expect(keys()).toEqual([]);
    const choice = q('[data-testid="feed-routine-choice"]')[0] as HTMLSelectElement;
    expect(choice, 'the advanced routine choice').toBeDefined();
    expect(Array.from(choice.options).map((o) => o.textContent)).toContain('Always hide, in every view');
  });
});

describe('proposal decisions (A11, challenger 12)', () => {
  it('normal, accepted and rejected stay independently filterable, combine with the actor, and are never outcomes', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'normal' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-pane', paneId: 'yes' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'accepted' });
    recordEffect({ kind: 'open-pane', paneId: 'no' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
    mount();
    ledger.snapshot([{ id: 'effect:ledger-only', kind: 'effect', occurredAt: '2026-11-01T10:00:00Z', originator: 'agent',
      effectKind: 'open-pane', subject: '' }]);
    await settle();
    host.filter = { ...host.filter, proposals: ['rejected'] };
    await settle();
    const rows = q('.item');
    expect(rows).toHaveLength(1);
    expect(rows[0]!.textContent).toContain('Proposal rejected');
    expect(rows[0]!.querySelector('[data-testid="ledger-outcome"]')).toBeNull();
    expect(rows[0]!.querySelector('button, jf-button')).toBeNull();
    host.filter = { ...host.filter, proposals: ['normal', 'accepted'] };
    await settle();
    expect(q('.item').map((r) => r.getAttribute('data-sources'))).toEqual(['journal', 'journal']);
    host.filter = { ...host.filter, originators: ['user'] };
    await settle();
    expect(q('.item')).toHaveLength(0);
  });
});

describe('seen cursor, panes and resolver (challenger 10, 11, 14)', () => {
  it('a millisecond record after a whole-second cursor is new', async () => {
    markSeen('2026-11-01T10:00:00Z');
    mount();
    ledger.snapshot([op('after', { occurredAt: '2026-11-01T10:00:00.100Z' }), op('before', { occurredAt: '2026-11-01T09:59:59.900Z' })]);
    await settle();
    expect(q('.item[data-key="operation:after"] [data-testid="ledger-new-dot"]')).toHaveLength(1);
    expect(q('.item[data-key="operation:before"] [data-testid="ledger-new-dot"]')).toHaveLength(0);
  });

  it('mark all read under an active filter marks every loaded record read', async () => {
    mount();
    ledger.snapshot([op('agent', { originator: 'agent', occurredAt: '2026-11-01T10:00:00Z' }),
      op('user', { occurredAt: '2026-11-01T10:00:05.5Z' })]);
    host.filter = { ...host.filter, quick: 'agents' };
    await settle();
    (q('[data-testid="ledger-mark-read"]')[0] as HTMLButtonElement).click();
    expect(getSeenCursor()).toBe('2026-11-01T10:00:05.5Z');
  });

  it('two feeds in one window keep independent filters', async () => {
    const first = mount();
    const firstLedger = ledger;
    const second = mount();
    for (const source of [firstLedger, ledger]) source.snapshot([op('fail', { outcome: 'FAILURE' }), op('ok')]);
    first.filter = { ...first.filter, quick: 'problems' };
    await settle(first);
    await settle(second);
    expect(keys(first)).toEqual(['operation:fail']);
    expect(keys(second).sort()).toEqual(['operation:fail', 'operation:ok']);
    first.remove();
  });

  it('a path the resolver could not answer is asked again once it recovers', async () => {
    const answers: Array<string | null> = [null, 'C:/corpus/report.pdf'];
    mount();
    host.resolveHash = async () => answers.shift() ?? null;
    host.filter = { ...host.filter, quick: 'indexing' };
    ledger.snapshot([{ id: 'index:1', kind: 'index', occurredAt: '2026-11-01T11:00:00Z', originator: 'system',
      collection: 'docs', state: 'DONE', pathHash: 'abcdef123456' }]);
    await settle();
    expect(q('.item')[0]?.textContent).toContain('docs (abcdef)');
    ledger.update(op('later'));
    await settle();
    await settle();
    expect(q('.item[data-key="index:1"]')[0]?.textContent).toContain('corpus/report.pdf');
  });
});

describe('bounded lifetime (challenger 15; windowing is checked in real Chromium)', () => {
  it("Today's boundary advances at local midnight", async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(new Date(2026, 10, 11, 23, 59, 30));
    mount();
    ledger.snapshot([op('tonight', { occurredAt: new Date(2026, 10, 11, 23, 59, 0).toISOString() }),
      op('yesterday', { occurredAt: new Date(2026, 10, 10, 12, 0, 0).toISOString() })]);
    host.filter = { ...host.filter, date: 'today' };
    await vi.advanceTimersByTimeAsync(0);
    await host.updateComplete;
    expect(keys()).toEqual(['operation:tonight']);
    await vi.advanceTimersByTimeAsync(31_000);
    await host.updateComplete;
    expect(keys()).toEqual([]);
    expect(q('[data-testid="ledger-empty"]')[0]?.textContent).toContain('No activity matches the filter');
  });
});

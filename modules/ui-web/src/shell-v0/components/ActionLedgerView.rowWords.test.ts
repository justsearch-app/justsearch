// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FC9 — Activity's rows read in words, not in recorded codes: who did it, what the record is and
 * how it ended are said with the details pane's own words (one shared module). No row shows
 * SUCCESS/FAILURE, DENIED/GATED, DONE, "backend", TYPED_CONFIRM, "copy-to-clipboard" or
 * "invoke-operation:", for the backend's records, this window's journal, another window's copies
 * and a journal restored after a reload. Identifiers stay where they are labelled for support.
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
import { __resetRecallCursor } from '../substrates/recall/recallCursor.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import type { Resource } from '../../api/types/registry.js';

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

function op(key: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { id: `operation:${key}`, kind: 'operation', occurredAt: '2026-11-01T10:00:00Z', originator: 'user',
    operationId: 'core.reindex', outcome: 'SUCCESS', ...extra };
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


/** Recorded codes that are not words a reader uses. */
const RAW = /\b(SUCCESS|FAILURE|UNDONE|DENIED|GATED|APPROVED|DONE|FAILED|ISSUED|CONSUMED|TYPED_CONFIRM|INLINE_CONFIRM)\b|\bbackend\b|copy-to-clipboard|open-pane|invoke-operation|navigate:|\b(user|agent|system)\b/;

/** Each visible row's text, as read. */
function rowTexts(): string[] {
  return q('.entry').map((row) => (row.textContent ?? '').replace(/\s+/g, ' ').trim());
}

function rowOf(key: string): string {
  const row = q(`.entry[data-entry-key="${key}"]`)[0];
  expect(row, `a row for ${key}`).toBeDefined();
  return (row!.textContent ?? '').replace(/\s+/g, ' ').trim();
}

const at = (n: number): string => `2026-11-01T10:${String(n).padStart(2, '0')}:00Z`;

const BACKEND_RECORDS = [
  op('ok', { occurredAt: at(1) }),
  op('fail', { outcome: 'FAILURE', occurredAt: at(2), originator: 'agent' }),
  op('undone', { outcome: 'UNDONE', occurredAt: at(3) }),
  { id: 'gate:1', kind: 'gate', occurredAt: at(4), originator: 'agent', operationId: 'core.rebuild-index',
    disposition: 'GATED', gateBehavior: 'TYPED_CONFIRM', pendingId: 'p-1' },
  { id: 'gate:2', kind: 'gate', occurredAt: at(5), originator: 'agent', operationId: 'core.rebuild-index',
    disposition: 'DENIED', gateBehavior: 'TYPED_CONFIRM', pendingId: 'p-2' },
  { id: 'grant:1', kind: 'grant', occurredAt: at(6), originator: 'user', action: 'ISSUED', grantId: 'g-1', subject: 'core.search-index' },
  { id: 'index:1', kind: 'index', occurredAt: at(7), originator: 'system', transport: 'WORKER_INDEXER', collection: 'reports',
    pathHash: 'aaaaaaaa1', state: 'DONE', outcome: 'DONE' },
  { id: 'index:2', kind: 'index', occurredAt: at(9), originator: 'system', transport: 'WORKER_INDEXER', collection: 'notes',
    pathHash: 'bbbbbbbb2', state: 'FAILED', outcome: 'FAILED' },
  // Another window's action, as the backend ledger keeps its copy: its kind and no fields.
  { id: 'fe-effect:other-window:7', kind: 'effect', occurredAt: at(11), originator: 'user', effectKind: 'copy-to-clipboard', subject: '' },
];

describe('FC9 rows read in words', () => {
  it('the backend\'s records: who, what and how it ended in words, never their codes', async () => {
    mount();
    ledger.snapshot(BACKEND_RECORDS);
    historyStream().snapshot([]);
    host.filter = { ...host.filter, routine: 'show' };
    await settle();
    const texts = rowTexts();
    expect(texts.length).toBe(BACKEND_RECORDS.length);
    for (const text of texts) expect(text, `row: ${text}`).not.toMatch(RAW);
    expect(rowOf('operation:ok')).toMatch(/^You .*Succeeded/);
    expect(rowOf('operation:fail')).toMatch(/^The assistant .*Failed/);
    expect(rowOf('operation:undone')).toContain('Undone');
    expect(rowOf('gate:1')).toContain('Asked for approval: Rebuild Index');
    expect(rowOf('gate:2')).toContain('Denied: Rebuild Index');
    expect(rowOf('grant:1')).toContain('Permission granted: Search Index');
    expect(rowOf('fe-effect:other-window:7')).toContain('Copy to clipboard');
  });

  it('index records say indexed or failed, and who did it is the engine', async () => {
    mount();
    ledger.snapshot(BACKEND_RECORDS.filter((r) => r.kind === 'index'));
    historyStream().snapshot([]);
    host.filter = { ...host.filter, routine: 'show' };
    await settle();
    const texts = rowTexts();
    expect(texts.length).toBeGreaterThan(0);
    for (const text of texts) {
      expect(text, `row: ${text}`).not.toMatch(RAW);
      expect(text).toMatch(/^The engine /);
    }
  });

  it('this window\'s journal: its actions in words, an invocation by its operation', async () => {
    recordEffect({ kind: 'copy-to-clipboard', text: 'secret words' }, CORE_PROVENANCE, { originator: 'user' });
    recordEffect({ kind: 'open-pane', paneId: 'notes' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'invoke-operation', operationId: 'core.reindex' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'navigate', to: 'justsearch://surface/core.library-surface' }, CORE_PROVENANCE, { originator: 'user' });
    mount();
    ledger.snapshot([]);
    historyStream().snapshot([]);
    host.filter = { ...host.filter, routine: 'show' };
    await settle();
    const texts = rowTexts();
    expect(texts.length).toBe(4);
    for (const text of texts) expect(text, `row: ${text}`).not.toMatch(RAW);
    expect(texts.some((t) => t.includes('Copy to clipboard'))).toBe(true);
    expect(texts.some((t) => /Reindex/.test(t))).toBe(true);
  });

  it('after a reload, the restored journal\'s rows read in words too', async () => {
    __resetJournalForTest();
    localStorage.setItem('justsearch.effect-journal.v1', JSON.stringify({ version: 1, nextId: 3, entries: [
      { id: 1, invokedAt: at(20), effect: { kind: 'copy-to-clipboard', text: 'x' }, invokedBy: CORE_PROVENANCE, inverse: null, originator: 'user' },
      { id: 2, invokedAt: at(21), effect: { kind: 'invoke-operation', operationId: 'core.rebuild-index' }, invokedBy: CORE_PROVENANCE,
        inverse: null, originator: 'agent' },
    ] }));
    restoreJournalFromStorage();
    mount();
    ledger.snapshot([]);
    historyStream().snapshot([]);
    host.filter = { ...host.filter, routine: 'show' };
    await settle();
    const texts = rowTexts();
    expect(texts.length).toBe(2);
    for (const text of texts) expect(text, `row: ${text}`).not.toMatch(RAW);
  });
});

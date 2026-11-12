// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FC9a — approval and permission rows (asked, approved, denied, expired, withdrawn; granted, used,
 * withdrawn) read in words, as the Engine records them: its `outcome` on these records is the
 * decision itself, which the row's title already says in the details pane's words (activityWords).
 * No row adds a "Not described (code GATED)" fallback beside it, and a row whose record names no
 * operation still says what was asked in words.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './ActionLedgerView.js';
import type { ActionLedgerView } from './ActionLedgerView.js';
import { __resetJournalForTest } from '../substrates/effects/index.js';
import { __resetRecallCursor } from '../substrates/recall/recallCursor.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import type { Resource } from '../../api/types/registry.js';
import { GATE_WORDS, GRANT_WORDS } from '../operations/activityWords.js';

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


const at = (n: number): string => `2026-11-01T10:${String(n).padStart(2, '0')}:00Z`;

/** The approval records as the Engine writes them (ActionLedgerProjection.projectGate): the decision is also the outcome. */
const DECISIONS = ['GATED', 'APPROVED', 'DENIED', 'EXPIRED', 'WITHDRAWN'] as const;
const GATES = DECISIONS.map((disposition, i) => ({ id: `gate:${disposition}`, kind: 'gate', occurredAt: at(i + 1),
  originator: 'agent', transport: 'MCP', operationId: 'core.ingest-files', disposition, outcome: disposition,
  gateBehavior: 'INLINE_CONFIRM', sourceTier: 'CORE', pendingId: `p-${i}` }));
const GRANTS = (['ISSUED', 'CONSUMED', 'REVOKED'] as const).map((action, i) => ({ id: `grant:${action}`, kind: 'grant',
  occurredAt: at(i + 10), originator: 'agent', transport: 'MCP', action, outcome: action, subject: 'core.ingest-files', grantId: `g-${i}` }));
/** An approval whose record names no operation. */
const UNNAMED = { id: 'gate:unnamed', kind: 'gate', occurredAt: at(20), originator: 'agent', transport: 'MCP',
  disposition: 'GATED', outcome: 'GATED', gateBehavior: 'INLINE_CONFIRM', sourceTier: 'CORE' };

function rowOf(key: string): string {
  const row = q(`.entry[data-entry-key="${key}"]`)[0];
  expect(row, `a row for ${key}`).toBeDefined();
  return (row!.textContent ?? '').replace(/\s+/g, ' ').trim();
}

describe('FC9a approval and permission rows read in words', () => {
  it('say the decision once, in the details pane\'s words, with no code beside it', async () => {
    mount();
    ledger.snapshot([...GATES, ...GRANTS, UNNAMED]);
    historyStream().snapshot([]);
    host.filter = { ...host.filter, routine: 'show' };
    await settle();
    for (const gate of GATES) {
      const text = rowOf(gate.id);
      expect(text, `row: ${text}`).toContain(`${GATE_WORDS[gate.disposition]}: Ingest Files`);
      expect(text, `row: ${text}`).not.toMatch(/Not described|\(code|\b(GATED|APPROVED|DENIED|EXPIRED|WITHDRAWN|INLINE_CONFIRM)\b/);
      expect(text.split(GATE_WORDS[gate.disposition]!).length - 1, `the decision is said once: ${text}`).toBe(1);
    }
    for (const grant of GRANTS) {
      const text = rowOf(grant.id);
      expect(text, `row: ${text}`).toContain(`${GRANT_WORDS[grant.action]}: Ingest Files`);
      expect(text, `row: ${text}`).not.toMatch(/Not described|\(code|\b(ISSUED|CONSUMED|REVOKED)\b/);
    }
    const unnamed = rowOf(UNNAMED.id);
    expect(unnamed, 'what was asked, in words').toMatch(/^The assistant Asked for approval: an operation not recorded/);
    expect(unnamed).not.toMatch(/Not described|\(code|\bGATED\b/);
  });
});

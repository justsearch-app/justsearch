// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * S6 Activity details, repair after the real-Engine check of 2026-11-12.
 *   - Safety: every details action acts on the record shown, after any sequence of selections:
 *     records chosen one after another without waiting, and a record that is re-keyed by a join after
 *     the details moved to it from another record.
 *   - Amendment 14: a failed export withdraws an earlier success message, and a refused copy is not
 *     recorded in the journal as done.
 *   - Decision 2026-11-11: the details pane builds no title or line from a raw code: approvals and
 *     permissions are titled in words, a related approval is not "Not described", a success has no
 *     code line, a journaled operation is named by its label, an export is "Copy to clipboard", and
 *     an approval request's identifier is labelled for support.
 * Activity runs against an Engine faked at `fetch`, as in ActivitySurface.details-honesty.test.ts.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import './ActivitySurface.js';
import type { ActivitySurface } from './ActivitySurface.js';
import { __resetJournalForTest, associateOperation, listJournal, recordEffect } from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetActionsForTest } from '../substrates/actions/index.js';
import { __resetRecallCursor } from '../substrates/recall/recallCursor.js';
import { __resetAgentSessionStore } from '../state/agentSessionStore.js';
import { present } from '../display/present.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import type { Resource } from '../../api/types/registry.js';

vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: (...args: unknown[]) => globalThis.fetch(...args as Parameters<typeof fetch>) }));

// ---------------------------------------------------------------- the fake Engine

// A host no resolver answers: a read that escaped the fake Engine could reach no running service.
const API = 'http://activity-engine.invalid';

/** One open SSE response body: frames are pushed as the Engine writes them. */
class SseBody {
  private readonly queue: Uint8Array[] = [];
  private waiting: ((chunk: ReadableStreamReadResult<Uint8Array>) => void) | null = null;
  private seq = 0;
  constructor(readonly path: string) {}
  private enqueue(text: string): void {
    const chunk = new TextEncoder().encode(text);
    if (this.waiting) { const w = this.waiting; this.waiting = null; w({ done: false, value: chunk }); } else this.queue.push(chunk);
  }
  private frame(frameKind: 'LIFECYCLE' | 'UPDATE', payload: unknown): void {
    this.seq += 1;
    this.enqueue(`event: frame\ndata: ${JSON.stringify({ streamId: this.path, frameKind, seq: this.seq,
      ts: '2026-11-01T00:00:00Z', payload, resumeToken: `t${this.seq}` })}\n\n`);
  }
  snapshot(entries: unknown[]): void { this.frame('LIFECYCLE', { kind: 'snapshot', entries }); }
  update(row: unknown): void { this.frame('UPDATE', row); }
  reader() {
    return {
      read: (): Promise<ReadableStreamReadResult<Uint8Array>> => {
        const next = this.queue.shift();
        if (next) return Promise.resolve({ done: false, value: next });
        return new Promise((resolve) => { this.waiting = resolve; });
      },
      cancel: async (): Promise<void> => {},
      releaseLock: (): void => {},
    };
  }
}

let streams: SseBody[] = [];
/** The receipt the Engine keeps for any invocation key. */
let receiptAnswer: unknown;

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function engineFetch(input: RequestInfo | URL): Promise<Response> {
  const path = new URL(String(input instanceof Request ? input.url : input)).pathname;
  if (path.endsWith('/stream')) {
    const body = new SseBody(path);
    streams.push(body);
    return Promise.resolve({ ok: true, status: 200, headers: new Headers({ 'content-type': 'text/event-stream' }),
      body: { getReader: () => body.reader(), cancel: async () => {} } } as unknown as Response);
  }
  if (path.startsWith('/api/operation-history/')) return Promise.resolve(json(receiptAnswer));
  if (path === '/api/action-ledger') return Promise.resolve(json({ entries: [] }));
  if (path === '/api/presence') return Promise.resolve(json({ runs: [] }));
  return Promise.resolve(json({}));
}

const ledgerStream = (): SseBody => streams.filter((s) => s.path === '/api/action-ledger/stream').at(-1)!;
const historyStream = (): SseBody => streams.filter((s) => s.path === '/api/operation-history/stream').at(-1)!;

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

/** An operation record as the ledger keeps it. */
function op(id: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { id, kind: 'operation', occurredAt: '2026-11-01T10:00:00Z', originator: 'agent',
    operationId: 'core.reindex', outcome: 'SUCCESS', transport: 'BUTTON', ...extra };
}

beforeEach(() => {
  __resetJournalForTest(); __resetActionsForTest(); __resetRecallCursor(); __resetAgentSessionStore();
  resetResourceCatalog();
  seedResourceCatalog({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [historyResource()] });
  streams = [];
  receiptAnswer = { state: 'unknown', historySince: 0 };
});

beforeAll(() => { vi.stubGlobal('fetch', vi.fn(engineFetch)); });
afterAll(() => { vi.unstubAllGlobals(); });
afterEach(() => {
  document.body.replaceChildren();
  __resetAgentSessionStore();
  __resetJournalForTest();
  Reflect.deleteProperty(navigator, 'clipboard');
});

// ---------------------------------------------------------------- reading Activity

/** Activity with the ledger snapshot `entries`. */
async function mount(entries: unknown[] = []): Promise<ActivitySurface> {
  const el = document.createElement('jf-activity-surface') as ActivitySurface;
  el.apiBase = API;
  document.body.append(el);
  await settle(el);
  ledgerStream().snapshot(entries);
  historyStream().snapshot([]);
  await settle(el);
  return el;
}

async function settle(el: ActivitySurface): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;
    const feed = el.shadowRoot?.querySelector('jf-action-ledger') as (Element & { updateComplete?: Promise<unknown> }) | null;
    await feed?.updateComplete;
    const details = el.shadowRoot?.querySelector('jf-activity-details') as (Element & { updateComplete?: Promise<unknown> }) | null;
    await details?.updateComplete;
  }
}

const feedRoot = (el: ActivitySurface): ShadowRoot => el.shadowRoot!.querySelector('jf-action-ledger')!.shadowRoot!;
const details = (el: ActivitySurface): ShadowRoot | null => el.shadowRoot!.querySelector('jf-activity-details')?.shadowRoot ?? null;
const text = (node: ParentNode | null | undefined): string => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const testid = (root: ParentNode | null, id: string): HTMLElement | null => root?.querySelector<HTMLElement>(`[data-testid="${id}"]`) ?? null;
const fact = (el: ActivitySurface, id: string): string => text(testid(details(el), id));

/** Select the record `key` (a ledger id, or a journal record's ledger id). */
async function select(el: ActivitySurface, key: string): Promise<void> {
  const target = [...feedRoot(el).querySelectorAll<HTMLElement>('.entry')].find((e) => e.dataset.entryKey === key);
  expect(target, `the feed shows ${key}`).toBeDefined();
  target!.click();
  await settle(el);
}

async function activate(button: Element | null): Promise<void> {
  expect(button, 'the control is there').not.toBeNull();
  const jf = button as Element & { updateComplete?: Promise<unknown> };
  await jf.updateComplete;
  const control = jf.shadowRoot?.querySelector('jf-control') as (Element & { updateComplete?: Promise<unknown> }) | null;
  await control?.updateComplete;
  (control?.shadowRoot?.querySelector('button') ?? jf.shadowRoot?.querySelector('button') ?? (button as HTMLElement)).click();
}


const K2 = '019a0000-0000-7000-8000-000000000002';
const K3 = '019a0000-0000-7000-8000-000000000003';

/** A journaled invocation with an execution the Engine can be asked to undo. */
function undoable(executionId: string, operationKey?: string): { id: number; key: string } {
  const entry = recordEffect({ kind: 'invoke-operation', operationId: 'core.file-operations' }, CORE_PROVENANCE, { originator: 'agent' });
  associateOperation(entry.id, { executionId, ...(operationKey ? { operationKey } : {}) });
  return { id: entry.id, key: entry.ledgerId! };
}

/** The executions the undo requests dispatched so far name, oldest first. */
const undoRequests = (): string[] => listJournal().flatMap((e) => (e.effect.kind === 'undo-operation' ? [e.effect.executionId] : []));

const selectedKey = (el: ActivitySurface): string | null =>
  feedRoot(el).querySelector<HTMLElement>('.entry[aria-selected="true"]')?.dataset.entryKey ?? null;

const exportLabel = (el: ActivitySurface): string => {
  const button = testid(el.shadowRoot!, 'activity-export-chosen') as (HTMLElement & { label?: string }) | null;
  return `${button?.label ?? ''} ${text(button)}`;
};

// ---------------------------------------------------------------- safety

describe('safety — every details action acts on the record shown', () => {
  it('records chosen one after another without waiting: Ask the engine to undo names the last one', async () => {
    const records = [undoable('exec-a'), undoable('exec-b'), undoable('exec-c')];
    const el = await mount();
    await select(el, records[0]!.key);
    for (const record of [records[1]!, records[2]!, records[1]!, records[2]!]) {
      [...feedRoot(el).querySelectorAll<HTMLElement>('.entry')].find((e) => e.dataset.entryKey === record.key)!.click();
    }
    await settle(el);
    expect(selectedKey(el)).toBe(records[2]!.key);
    await activate(testid(details(el), 'activity-details-undo-operation'));
    await settle(el);
    expect(undoRequests(), 'one request, for the execution shown').toEqual(['exec-c']);
  });

  it('a record re-keyed by a join after the details moved to it: Ask the engine to undo names it', async () => {
    const before = undoable('exec-before');
    const shown = undoable('exec-shown', K2);
    const el = await mount();
    await select(el, before.key);
    await select(el, shown.key);
    // The Engine's record of the same invocation arrives: the record joins it and answers to its key.
    ledgerStream().update(op(`operation:${K2}`, { operationId: 'core.file-operations', occurredAt: new Date().toISOString() }));
    await settle(el);
    expect(selectedKey(el), 'the selection followed the record').toBe(`operation:${K2}`);
    await activate(testid(details(el), 'activity-details-undo-operation'));
    await settle(el);
    expect(undoRequests(), 'one request, for the execution shown').toEqual(['exec-shown']);
  });

  it('Restore to this point and Undo this AI turn, after the details moved from another record, preview the record shown', async () => {
    const other = recordEffect({ kind: 'open-pane', paneId: 'other' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-pane', paneId: 'other-2' }, CORE_PROVENANCE, { originator: 'agent', causation: other.id });
    const root = recordEffect({ kind: 'open-pane', paneId: 'shown' }, CORE_PROVENANCE, { originator: 'agent' });
    const step = recordEffect({ kind: 'open-pane', paneId: 'shown-2' }, CORE_PROVENANCE, { originator: 'agent', causation: root.id });
    const el = await mount();
    await select(el, other.ledgerId!);
    await select(el, root.ledgerId!);
    await activate(testid(details(el), 'activity-details-restore'));
    await settle(el);
    const restore = testid(details(el), 'activity-details-preview');
    expect(restore?.dataset.preview, 'Restore previews for the record shown').toBe('restore');
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-preview-row"]')].map(text)).toEqual(['Open pane "shown-2"']);
    await activate(testid(details(el), 'activity-details-preview-cancel'));
    await settle(el);
    const otherStep = listJournal().find((e) => e.causation === other.id)!;
    await select(el, otherStep.ledgerId!);
    await select(el, step.ledgerId!);
    await activate(testid(details(el), 'activity-details-undo-turn'));
    await settle(el);
    expect(testid(details(el), 'activity-details-preview')?.dataset.preview, 'Undo this AI turn previews for the record shown').toBe('turn');
    const rows = [...details(el)!.querySelectorAll('[data-testid="activity-details-preview-row"]')].map(text);
    expect(rows.sort()).toEqual(['Open pane "shown"', 'Open pane "shown-2"']);
  });
});

// ---------------------------------------------------------------- amendment 14

describe('amendment 14 — an export says only what happened', () => {
  async function chooseOne(): Promise<ActivitySurface> {
    const chosen = recordEffect({ kind: 'open-pane', paneId: 'chosen' }, CORE_PROVENANCE, { originator: 'agent' });
    const el = await mount();
    await select(el, chosen.ledgerId!);
    (testid(details(el), 'activity-details-choose') as HTMLInputElement).click();
    await settle(el);
    return el;
  }

  it('a refused export right after a successful one withdraws "Exported" beside its alert', async () => {
    let refuse = false;
    Object.defineProperty(navigator, 'clipboard', { configurable: true,
      value: { writeText: () => (refuse ? Promise.reject(new DOMException('Write permission denied.', 'NotAllowedError')) : Promise.resolve()) } });
    const el = await chooseOne();
    await activate(testid(el.shadowRoot!, 'activity-export-chosen'));
    await settle(el);
    expect(exportLabel(el)).toMatch(/Exported 1 entry/);
    refuse = true;
    await activate(testid(el.shadowRoot!, 'activity-export-chosen'));
    await settle(el);
    expect(testid(el.shadowRoot!, 'activity-export-failed'), 'the refusal is said').not.toBeNull();
    expect(exportLabel(el), 'no success stands beside it').not.toMatch(/Exported/);
  });

  it('a refused copy is not recorded as done; a copy the clipboard took is recorded once', async () => {
    let refuse = true;
    Object.defineProperty(navigator, 'clipboard', { configurable: true,
      value: { writeText: () => (refuse ? Promise.reject(new DOMException('Write permission denied.', 'NotAllowedError')) : Promise.resolve()) } });
    const el = await chooseOne();
    const copies = (): number => listJournal().filter((e) => e.effect.kind === 'copy-to-clipboard').length;
    await activate(testid(el.shadowRoot!, 'activity-export-chosen'));
    await settle(el);
    expect(copies(), 'a refused copy is not in the journal').toBe(0);
    refuse = false;
    await activate(testid(el.shadowRoot!, 'activity-export-chosen'));
    await settle(el);
    expect(copies(), 'the copy that happened is').toBe(1);
  });
});

// ---------------------------------------------------------------- words, not codes

describe('decision 2026-11-11 — the details pane builds nothing from a raw code', () => {
  const gate = (id: string, disposition: string, occurredAt: string) => ({ id, kind: 'gate', originator: 'agent', occurredAt,
    disposition, operationId: 'core.rebuild-index', gateBehavior: 'TYPED_CONFIRM', transport: 'MCP', pendingId: 'pa-e9f6a3ef',
    outcome: disposition });

  it('an approval is titled in words, in the details and on their tab, and its request names no code', async () => {
    const el = await mount([gate('gate:asked', 'GATED', '2026-11-01T09:00:00Z'), gate('gate:denied', 'DENIED', '2026-11-01T10:00:00Z')]);
    const operation = present({ kind: 'operation', id: 'core.rebuild-index' }).label;
    await select(el, 'gate:denied');
    const tab = text(el.shadowRoot!.querySelector('jf-details-pane')!.shadowRoot!.querySelector('[role="tab"]'));
    expect(fact(el, 'activity-details-title')).toBe(`Denied: ${operation}`);
    expect(tab).toBe(`Denied: ${operation}`);
    const related = fact(el, 'activity-details-related');
    expect(related).toMatch(new RegExp(`^Asked for approval: ${operation} · `));
    for (const shown of [fact(el, 'activity-details-title'), tab, related]) {
      expect(shown).not.toMatch(/GATED|DENIED|TYPED_CONFIRM|Not described/);
    }
    expect(fact(el, 'activity-details-request')).toBe('pa-e9f6a3ef (for support)');
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-gate"] dt')].map(text)).toContain('Request reference');
  });

  it('a successful receipt has no code line', async () => {
    receiptAnswer = { state: 'complete', historySince: 0, unitsCompleted: 0, result: { code: 'SUCCESS' } };
    const el = await mount([op(`operation:${K3}`, { operationId: 'core.clear-failed-jobs', outcome: 'SUCCESS' })]);
    await select(el, `operation:${K3}`);
    expect(testid(details(el), 'activity-details-receipt')?.dataset.state).toBe('complete');
    expect(testid(details(el), 'activity-details-code')).toBeNull();
    expect(text(details(el))).not.toMatch(/SUCCESS/);
  });

  it('what this window recorded names the operation by its label, and an export is titled Copy to clipboard', async () => {
    const run = recordEffect({ kind: 'invoke-operation', operationId: 'core.clear-failed-jobs' }, CORE_PROVENANCE, { originator: 'user' });
    const copy = recordEffect({ kind: 'copy-to-clipboard', text: '[]' }, CORE_PROVENANCE, { originator: 'user' });
    const el = await mount();
    await select(el, run.ledgerId!);
    const line = details(el)!.querySelector('jf-effect-line')!.shadowRoot!.querySelector('[data-testid="effect-label"]');
    expect(text(line)).toBe(`Run ${present({ kind: 'operation', id: 'core.clear-failed-jobs' }).label}`);
    // A copy is routine: it is listed once routine records are shown.
    const feed = el.shadowRoot!.querySelector('jf-action-ledger') as Element & { filter: Record<string, unknown> };
    feed.filter = { ...feed.filter, routine: 'show' };
    await settle(el);
    await select(el, copy.ledgerId!);
    expect(fact(el, 'activity-details-title')).toBe('Copy to clipboard');
    const tab = text(el.shadowRoot!.querySelector('jf-details-pane')!.shadowRoot!.querySelector('[role="tab"]'));
    expect(tab).toBe('Copy to clipboard');
  });
});

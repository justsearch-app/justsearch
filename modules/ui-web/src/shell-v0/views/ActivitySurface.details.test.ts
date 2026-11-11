// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * S6 Activity, candidate 2 — the selected record's details and where each per-record capability
 * lives. Activity runs against an Engine faked at `fetch`: the ledger and operation-history streams
 * send SSE `frame` events as SseEnvelopeWriter writes them, the receipt, ledger, run and presence
 * reads answer when the test says so.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import './ActivitySurface.js';
import type { ActivitySurface } from './ActivitySurface.js';
import {
  associateOperation,
  listJournal,
  recordEffect,
  settleOperationRequest,
  __resetJournalForTest,
} from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetActionsForTest } from '../substrates/actions/index.js';
import { __resetElicitForTest, listPendingElicits, resolveElicit } from '../substrates/elicit/index.js';
import { __resetMacrosForTest, listMacros } from '../substrates/macros/index.js';
import { __resetRecallCursor } from '../substrates/recall/recallCursor.js';
import { __resetAgentSessionStore, getAgentSessionController } from '../state/agentSessionStore.js';
import { requestActivitySection, takeActivitySection } from '../state/activitySection.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import type { Resource } from '../../api/types/registry.js';
import { createProfile, setActiveProfileId } from '../state/UserStateDocument.js';

vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: (...args: unknown[]) => globalThis.fetch(...args as Parameters<typeof fetch>) }));

// ---------------------------------------------------------------- the fake Engine

// A host no resolver answers: a read that escaped the fake Engine could reach no running service.
const API = 'http://activity-engine.invalid';
const K1 = '019a0000-0000-7000-8000-000000000001';
const K2 = '019a0000-0000-7000-8000-000000000002';

/** One open SSE response body: frames are pushed as the Engine writes them. */
class SseBody {
  private readonly queue: Uint8Array[] = [];
  private waiting: ((chunk: ReadableStreamReadResult<Uint8Array>) => void) | null = null;
  private seq = 0;
  closed = false;
  constructor(readonly path: string) {}
  private enqueue(text: string): void {
    const chunk = new TextEncoder().encode(text);
    if (this.waiting) { const w = this.waiting; this.waiting = null; w({ done: false, value: chunk }); } else this.queue.push(chunk);
  }
  frame(frameKind: 'LIFECYCLE' | 'UPDATE', payload: unknown): void {
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
      cancel: async (): Promise<void> => { this.closed = true; },
      releaseLock: (): void => {},
    };
  }
}

interface Deferred { resolve(body: unknown, status?: number): void; reject(): void; readonly settled: boolean }

let streams: SseBody[] = [];
let ledgerAnswer: () => Promise<unknown[]>;
let presenceRuns: unknown[];
/** Receipt reads waiting for an answer, oldest first, per operation key. */
let receipts: Map<string, Deferred[]>;
let runReads: Array<{ sessionId: string; answer: Deferred }>;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function deferred(): { promise: Promise<Response>; handle: Deferred } {
  let settle!: (r: Response) => void;
  let fail!: (e: unknown) => void;
  let done = false;
  const promise = new Promise<Response>((resolve, reject) => { settle = resolve; fail = reject; });
  return { promise, handle: {
    resolve: (body, status = 200) => { done = true; settle(json(body, status)); },
    reject: () => { done = true; fail(new TypeError('network')); },
    get settled() { return done; },
  } };
}

function engineFetch(input: RequestInfo | URL): Promise<Response> {
  const url = new URL(String(input instanceof Request ? input.url : input));
  const path = url.pathname;
  if (path.endsWith('/stream')) {
    const body = new SseBody(path);
    streams.push(body);
    return Promise.resolve({ ok: true, status: 200, headers: new Headers({ 'content-type': 'text/event-stream' }),
      body: { getReader: () => body.reader(), cancel: async () => {} } } as unknown as Response);
  }
  if (path === '/api/action-ledger' && url.searchParams.get('correlationId')) {
    const { promise, handle } = deferred();
    runReads.push({ sessionId: url.searchParams.get('correlationId')!, answer: handle });
    return promise;
  }
  if (path === '/api/action-ledger') return ledgerAnswer().then((entries) => json({ entries }));
  if (path.startsWith('/api/operation-history/')) {
    const key = decodeURIComponent(path.slice('/api/operation-history/'.length));
    const { promise, handle } = deferred();
    receipts.set(key, [...(receipts.get(key) ?? []), handle]);
    return promise;
  }
  if (path === '/api/presence') return Promise.resolve(json({ runs: presenceRuns }));
  return Promise.resolve(json({}));
}

const ledgerStream = (): SseBody => streams.filter((s) => s.path === '/api/action-ledger/stream').at(-1)!;
const historyStream = (): SseBody => streams.filter((s) => s.path === '/api/operation-history/stream').at(-1)!;
/** The oldest unanswered receipt read for `key`. */
function receipt(key: string): Deferred {
  const open = (receipts.get(key) ?? []).filter((r) => !r.settled);
  expect(open.length, `a receipt read for ${key} is waiting`).toBeGreaterThan(0);
  return open[0]!;
}

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

function op(key: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { id: `operation:${key}`, kind: 'operation', occurredAt: '2026-11-01T10:00:00Z', originator: 'user',
    operationId: 'core.reindex', outcome: 'SUCCESS', transport: 'BUTTON', ...extra };
}

function committed(operationKey: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { operationId: 'core.reindex', operationKey, outcome: 'SUCCESS', actor: 'head', startTime: '2026-11-01T09:58:30Z',
    endTime: '2026-11-01T10:00:00Z', executionId: null, diagnosticsLink: null,
    provenance: { transport: 'BUTTON', correlationId: null }, ...extra };
}

beforeEach(() => {
  __resetJournalForTest(); __resetActionsForTest(); __resetElicitForTest(); __resetMacrosForTest(); __resetRecallCursor();
  __resetAgentSessionStore();
  resetResourceCatalog();
  seedResourceCatalog({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [historyResource()] });
  streams = [];
  ledgerAnswer = async () => [];
  presenceRuns = [];
  receipts = new Map();
  runReads = [];
});

// The fake Engine answers for the whole file: the shared agent controller's own reads can still be
// in flight when a test ends.
beforeAll(() => { vi.stubGlobal('fetch', vi.fn(engineFetch)); });
afterAll(() => { vi.unstubAllGlobals(); });

afterEach(() => {
  document.body.replaceChildren();
  __resetAgentSessionStore();
  __resetJournalForTest();
});

// ---------------------------------------------------------------- reading Activity

async function mount(): Promise<ActivitySurface> {
  const el = document.createElement('jf-activity-surface') as ActivitySurface;
  el.apiBase = API;
  document.body.append(el);
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
const entries = (el: ActivitySurface): HTMLElement[] => [...feedRoot(el).querySelectorAll<HTMLElement>('.entry')];
/** The key of the record an entry shows (the entry's own, else the record's inside it). */
const keyOf = (e: HTMLElement): string => e.dataset.entryKey ?? e.querySelector('[data-key]')?.getAttribute('data-key') ?? '';
/** The feed entry for `key`: by the entry's own key, else by the record it holds (any version of the feed). */
const entry = (el: ActivitySurface, key: string): HTMLElement | undefined => entries(el).find((e) => e.dataset.entryKey === key
  || [...e.querySelectorAll('[data-key]')].some((n) => n.getAttribute('data-key') === key));
const details = (el: ActivitySurface): ShadowRoot | null => el.shadowRoot!.querySelector('jf-activity-details')?.shadowRoot ?? null;
const text = (node: ParentNode | null | undefined): string => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const testid = (root: ParentNode | null, id: string): HTMLElement | null => root?.querySelector<HTMLElement>(`[data-testid="${id}"]`) ?? null;

async function select(el: ActivitySurface, key: string): Promise<void> {
  const target = entry(el, key);
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

// ---------------------------------------------------------------- the details pane

describe('A17/A19 — the feed is a list box; a record opens its details in the shared pane', () => {
  it('a click selects the record and opens the shared details pane; rows hold no controls of their own', async () => {
    const el = await mount();
    ledgerStream().snapshot([op(K1), op(K2, { occurredAt: '2026-11-01T09:00:00Z', operationId: 'core.open-file' })]);
    await settle(el);
    expect(feedRoot(el).querySelector('[data-testid="feed-list"]')?.getAttribute('role')).toBe('listbox');
    expect(entries(el).map((e) => e.getAttribute('role'))).toEqual(['option', 'option']);
    for (const option of entries(el)) {
      expect(option.querySelectorAll('button, a[href], input, select, textarea, [tabindex]').length,
        'no focusable control inside a row').toBe(0);
    }
    await select(el, `operation:${K1}`);
    expect(entry(el, `operation:${K1}`)?.getAttribute('aria-selected')).toBe('true');
    expect(entry(el, `operation:${K2}`)?.getAttribute('aria-selected')).toBe('false');
    const pane = el.shadowRoot!.querySelector('jf-details-pane');
    expect(pane, 'the shared jf-details-pane holds the details').not.toBeNull();
    expect(pane!.hasAttribute('data-details-pane')).toBe(true);
    expect(text(testid(details(el), 'activity-details-title'))).toBe('Reindex');
  });

  it('Enter and Space on a focused record select it; Escape closes the details and returns focus to that record', async () => {
    const el = await mount();
    ledgerStream().snapshot([op(K1), op(K2, { occurredAt: '2026-11-01T09:00:00Z' })]);
    await settle(el);
    const second = entry(el, `operation:${K2}`)!;
    second.focus();
    second.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }));
    await settle(el);
    expect(second.getAttribute('aria-selected')).toBe('true');
    const first = entry(el, `operation:${K1}`)!;
    first.focus();
    first.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, composed: true }));
    await settle(el);
    expect(entry(el, `operation:${K1}`)!.getAttribute('aria-selected')).toBe('true');
    const close = el.shadowRoot!.querySelector('jf-details-pane')!;
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    await settle(el);
    expect(el.shadowRoot!.querySelector('jf-details-pane'), 'Escape closes the details').toBeNull();
    expect(feedRoot(el).activeElement?.getAttribute('data-entry-key')).toBe(`operation:${K1}`);
  });
});

describe('14:10Z decision — every record says how long it took and where it came from', () => {
  it('an operation shows its committed duration and its source, and which records it joins', async () => {
    const el = await mount();
    ledgerStream().snapshot([op(K1, { transport: 'MCP', originator: 'agent' })]);
    historyStream().snapshot([committed(K1, { provenance: { transport: 'MCP', correlationId: null } })]);
    await settle(el);
    await select(el, `operation:${K1}`);
    expect(text(testid(details(el), 'activity-details-duration'))).toBe('1m 30s');
    expect(text(testid(details(el), 'activity-details-source'))).toBe('An outside assistant (MCP)');
    expect(text(testid(details(el), 'activity-details-records'))).toBe('Activity ledger · Operation history');
  });
});

// ---------------------------------------------------------------- receipts

describe('A7 / amendment 3 — a selected running operation converges without regressing', () => {
  it('the receipt completes before the history arrives: the selection and focus follow the join into one row', async () => {
    const entryId = recordEffect({ kind: 'invoke-operation', operationId: 'core.reindex' }, CORE_PROVENANCE).id;
    associateOperation(entryId, { operationKey: K1 });
    const el = await mount();
    ledgerStream().snapshot([]);
    historyStream().snapshot([]);
    await settle(el);
    const local = keyOf(entries(el)[0]!);
    await select(el, local);
    receipt(K1).resolve({ state: 'complete', historySince: 0, acceptedAt: 1_000, completedAt: 4_000, unitsCompleted: 3 });
    await settle(el);
    expect(text(testid(details(el), 'activity-details-outcome'))).toBe('Completed');
    expect(text(testid(details(el), 'activity-details-duration'))).toBe('3s');
    entry(el, local)!.focus();
    // History and ledger deliver the invocation: one row, still selected, focus kept, committed outcome.
    historyStream().update(committed(K1));
    ledgerStream().update(op(K1));
    await settle(el);
    expect(entries(el)).toHaveLength(1);
    expect(keyOf(entries(el)[0]!)).toBe(`operation:${K1}`);
    expect(entries(el)[0]!.getAttribute('aria-selected')).toBe('true');
    expect(text(testid(details(el), 'activity-details-outcome'))).toBe('SUCCESS');
    expect(text(testid(details(el), 'activity-details-records'))).toBe("Activity ledger · Operation history · This window's journal");
    expect(feedRoot(el).activeElement?.getAttribute('data-entry-key'), 'focus stays on the record').toBe(`operation:${K1}`);
  });

  it('the history arrives first: a receipt still reading running is shown as behind, not as the outcome', async () => {
    const el = await mount();
    ledgerStream().snapshot([op(K1)]);
    historyStream().snapshot([committed(K1)]);
    await settle(el);
    await select(el, `operation:${K1}`);
    receipt(K1).resolve({ state: 'running', historySince: 0 });
    await settle(el);
    expect(text(testid(details(el), 'activity-details-outcome'))).toBe('SUCCESS');
    expect(testid(details(el), 'activity-details-receipt')?.dataset.state).toBe('behind');
  });

  it('A18 — an older running read answering after a newer completed one, for an earlier selection, shows nothing stale', async () => {
    const el = await mount();
    ledgerStream().snapshot([op(K1), op(K2, { occurredAt: '2026-11-01T09:00:00Z' })]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, `operation:${K1}`);
    const first = receipt(K1);
    await select(el, `operation:${K2}`);
    await select(el, `operation:${K1}`);
    const second = (receipts.get(K1) ?? []).filter((r) => !r.settled)[1]!;
    second.resolve({ state: 'complete', historySince: 0 });
    await settle(el);
    first.resolve({ state: 'running', historySince: 0 });
    receipt(K2).resolve({ state: 'failed', historySince: 0, message: 'K2 refused' });
    await settle(el);
    expect(testid(details(el), 'activity-details-receipt')?.dataset.state).toBe('complete');
    expect(text(details(el))).not.toContain('K2 refused');
  });
});

describe('A8 — a missing or unreadable receipt is never a failed execution', () => {
  it.each([
    ['unknown', { state: 'unknown', historySince: 0 }, 'does not mean it failed'],
    ['expired', { state: 'expired', historySince: 0 }, 'has expired'],
  ] as const)('a receipt that is %s keeps the record and says what it means', async (state, body, words) => {
    const el = await mount();
    ledgerStream().snapshot([op(K1, { outcome: undefined })]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, `operation:${K1}`);
    receipt(K1).resolve(body);
    await settle(el);
    expect(testid(details(el), 'activity-details-receipt')?.dataset.state).toBe(state);
    expect(text(testid(details(el), 'activity-details-receipt'))).toContain(words);
    expect(text(testid(details(el), 'activity-details-outcome'))).toBe('Outcome unavailable');
    expect(entries(el)).toHaveLength(1);
  });

  it('a refused read says the receipt could not be read, and Try again reads it', async () => {
    const el = await mount();
    ledgerStream().snapshot([op(K1)]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, `operation:${K1}`);
    receipt(K1).resolve({ error: 'busy' }, 500);
    await settle(el);
    expect(testid(details(el), 'activity-details-receipt')?.dataset.state).toBe('unreadable');
    expect(text(testid(details(el), 'activity-details-receipt'))).toContain('says nothing about how the operation ended');
    expect(text(testid(details(el), 'activity-details-outcome'))).toBe('SUCCESS');
    await activate(testid(details(el), 'activity-details-receipt-retry'));
    receipt(K1).resolve({ state: 'failed', historySince: 0, message: 'Index locked', result: { code: 'LOCKED' } });
    await settle(el);
    expect(testid(details(el), 'activity-details-receipt')?.dataset.state).toBe('failed');
    expect(text(testid(details(el), 'activity-details-receipt'))).toContain('Index locked');
  });
});

// ---------------------------------------------------------------- journal capabilities

/** A journaled invocation the Engine joined (ledger + history), with an execution to undo. */
async function undoableRecord(answerReceipt = true): Promise<{ el: ActivitySurface; id: number }> {
  const id = recordEffect({ kind: 'invoke-operation', operationId: 'core.file-operations' }, CORE_PROVENANCE, { originator: 'agent' }).id;
  associateOperation(id, { operationKey: K1, executionId: 'exec-1' });
  const el = await mount();
  ledgerStream().snapshot([op(K1, { operationId: 'core.file-operations', originator: 'agent', executionId: 'exec-1' })]);
  historyStream().snapshot([committed(K1, { operationId: 'core.file-operations', executionId: 'exec-1' })]);
  await settle(el);
  await select(el, `operation:${K1}`);
  if (answerReceipt) receipt(K1).resolve({ state: 'complete', historySince: 0 });
  await settle(el);
  return { el, id };
}

describe('A12 / A14 — the journal record\'s actions live in its details', () => {
  it('Ask the engine to undo is a request until the Engine answers; a refusal is said as one, and a second click asks nothing', async () => {
    const { el } = await undoableRecord();
    const undo = vi.fn();
    document.addEventListener('jf-undo-operation', undo);
    await activate(testid(details(el), 'activity-details-undo-operation'));
    await settle(el);
    await activate(testid(details(el), 'activity-details-undo-operation'));
    await settle(el);
    document.removeEventListener('jf-undo-operation', undo);
    expect(undo).toHaveBeenCalledTimes(1);
    expect(testid(details(el), 'activity-details-undo-answer')?.dataset.answer).toBe('asked');
    const reversal = listJournal().at(-1)!;
    expect(reversal.effect.kind).toBe('undo-operation');
    settleOperationRequest(reversal.id, 'not-done');
    await settle(el);
    expect(testid(details(el), 'activity-details-undo-answer')?.dataset.answer).toBe('not-done');
    expect(text(testid(details(el), 'activity-details-undo-answer'))).toBe('The engine did not undo this action.');
  });

  it('Restore to this point previews a fixed set, says what will remain, Cancel changes nothing, and confirming runs once', async () => {
    const target = recordEffect({ kind: 'open-pane', paneId: 'base' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-pane', paneId: 'later' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'toast', message: 'irreversible', severity: 'info' }, CORE_PROVENANCE, { originator: 'agent' });
    const el = await mount();
    ledgerStream().snapshot([]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, target.ledgerId!);
    await activate(testid(details(el), 'activity-details-restore'));
    await settle(el);
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-preview-row"]')]).toHaveLength(1);
    expect(text(testid(details(el), 'activity-details-irreversible'))).toContain('1 other action');
    // A new reversible action after the preview was drawn is not added to it.
    recordEffect({ kind: 'open-pane', paneId: 'newer' }, CORE_PROVENANCE, { originator: 'agent' });
    await settle(el);
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-preview-row"]')]).toHaveLength(1);
    const before = listJournal().length;
    await activate(testid(details(el), 'activity-details-preview-cancel'));
    await settle(el);
    expect(listJournal()).toHaveLength(before);
    await activate(testid(details(el), 'activity-details-restore'));
    await settle(el);
    const confirm = testid(details(el), 'activity-details-preview-confirm');
    await activate(confirm);
    await activate(confirm);
    await settle(el);
    const reversals = listJournal().slice(before).filter((e) => e.effect.kind === 'close-pane');
    expect(reversals.map((e) => (e.effect as { paneId: string }).paneId)).toEqual(['newer', 'later']);
  });

  it('Undo this AI turn and the causation trace are in the turn member\'s details', async () => {
    const root = recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    const step = recordEffect({ kind: 'open-pane', paneId: 'b' }, CORE_PROVENANCE,
      { originator: 'agent', causation: root.id });
    const el = await mount();
    ledgerStream().snapshot([]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, step.ledgerId!);
    expect(details(el), 'the step has details').not.toBeNull();
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-trace-step"]')]).toHaveLength(2);
    await activate(testid(details(el), 'activity-details-undo-turn'));
    await settle(el);
    expect(testid(details(el), 'activity-details-preview')?.dataset.preview).toBe('turn');
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-preview-row"]')]).toHaveLength(2);
    await activate(testid(details(el), 'activity-details-preview-confirm'));
    await settle(el);
    expect(listJournal().filter((e) => e.effect.kind === 'close-pane').map((e) => (e.effect as { paneId: string }).paneId)).toEqual(['b', 'a']);
  });

  it('records chosen in details save as a macro and export in journal order, whatever order they were chosen in', async () => {
    const first = recordEffect({ kind: 'open-pane', paneId: 'first' }, CORE_PROVENANCE, { originator: 'agent' });
    const second = recordEffect({ kind: 'open-pane', paneId: 'second' }, CORE_PROVENANCE, { originator: 'agent' });
    const el = await mount();
    ledgerStream().snapshot([]);
    historyStream().snapshot([]);
    await settle(el);
    for (const chosen of [second, first]) {
      await select(el, chosen.ledgerId!);
      const box = testid(details(el), 'activity-details-choose') as HTMLInputElement | null;
      expect(box, 'the record\'s details offer to include it').not.toBeNull();
      box!.click();
      await settle(el);
    }
    const written: string[] = [];
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (t: string) => { written.push(t); } } });
    await activate(testid(el.shadowRoot!, 'activity-export-chosen'));
    await settle(el);
    const archive = JSON.parse(written.at(-1) ?? '{}') as { entries?: Array<{ effect: { paneId: string } }> };
    expect(archive.entries?.map((e) => e.effect.paneId), 'exported in journal order').toEqual(['first', 'second']);
    // Tempdoc 613 §6 — the confirmation is a receipt in the control, not a window toast.
    expect(testid(el.shadowRoot!, 'activity-export-chosen')?.getAttribute('label') ?? (testid(el.shadowRoot!, 'activity-export-chosen') as unknown as { label: string }).label)
      .toContain('Exported 2 entries');
    expect(listJournal().some((e) => e.effect.kind === 'toast' && /Exported/.test((e.effect as { message: string }).message))).toBe(false);
    await activate(testid(el.shadowRoot!, 'activity-save-chosen'));
    await settle(el);
    const prompt = listPendingElicits()[0];
    expect(prompt, 'saving asks for the macro name').toBeDefined();
    resolveElicit(prompt!.id, { label: 'Two panes' });
    await settle(el);
    expect(listMacros().at(-1)?.effects.map((e) => (e as { paneId: string }).paneId)).toEqual(['first', 'second']);
  });

  it('Import journal archive stays an Activity utility and keeps a pasted archive as a macro', async () => {
    const el = await mount();
    await activate(testid(el.shadowRoot!, 'activity-import-archive'));
    await settle(el);
    const prompt = listPendingElicits()[0];
    expect(prompt?.title).toBe('Import journal archive');
    const archive = JSON.stringify({ version: 1, exportedAt: '2026-11-01T00:00:00Z',
      entries: [{ effect: { kind: 'open-pane', paneId: 'imported' }, originator: 'agent', invokedAt: '2026-11-01T00:00:00Z' }] });
    resolveElicit(prompt!.id, { json: archive, label: 'Imported panes' });
    await settle(el);
    expect(listMacros().find((m) => m.label === 'Imported panes')?.effects).toEqual([{ kind: 'open-pane', paneId: 'imported' }]);
  });

  it('Undo last AI action stays an Activity utility and names what it undid', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'last' }, CORE_PROVENANCE, { originator: 'agent' });
    const el = await mount();
    await activate(testid(el.shadowRoot!, 'activity-undo-last'));
    await settle(el);
    expect(listJournal().some((e) => e.effect.kind === 'close-pane' && (e.effect as { paneId: string }).paneId === 'last')).toBe(true);
    expect(listJournal().some((e) => e.effect.kind === 'toast' && /^Undid: /.test((e.effect as { message: string }).message))).toBe(true);
  });

  it('an execution association recorded after the details opened brings its Undo there at once', async () => {
    const id = recordEffect({ kind: 'invoke-operation', operationId: 'core.file-operations' }, CORE_PROVENANCE, { originator: 'agent' }).id;
    const el = await mount();
    ledgerStream().snapshot([]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, keyOf(entries(el)[0]!));
    expect(testid(details(el), 'activity-details-undo-operation')).toBeNull();
    associateOperation(id, { executionId: 'exec-late' });
    await settle(el);
    expect(testid(details(el), 'activity-details-undo-operation')).not.toBeNull();
  });
});

describe('amendment 10 — local capabilities only where their source exists', () => {
  it('a backend copy of another window\'s effect has no proposal, undo, restore or choice', async () => {
    const el = await mount();
    ledgerStream().snapshot([{ id: 'fe-journal:other-window-1', kind: 'effect', occurredAt: '2026-11-01T10:00:00Z',
      originator: 'agent', effectKind: 'open-pane' }]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, 'fe-journal:other-window-1');
    const root = details(el);
    expect(testid(root, 'activity-details-elsewhere')).not.toBeNull();
    for (const id of ['activity-details-undo-operation', 'activity-details-restore', 'activity-details-undo-turn',
      'activity-details-choose', 'activity-details-proposal']) expect(testid(root, id), id).toBeNull();
  });

  it('two Activities keep their own selections', async () => {
    const a = await mount();
    const b = await mount();
    for (const s of streams.filter((x) => x.path === '/api/action-ledger/stream')) s.snapshot([op(K1), op(K2, { occurredAt: '2026-11-01T09:00:00Z' })]);
    await settle(a); await settle(b);
    await select(a, `operation:${K1}`);
    await select(b, `operation:${K2}`);
    expect(entry(a, `operation:${K1}`)?.getAttribute('aria-selected')).toBe('true');
    expect(entry(b, `operation:${K1}`)?.getAttribute('aria-selected')).toBe('false');
    expect(entry(b, `operation:${K2}`)?.getAttribute('aria-selected')).toBe('true');
  });
});

describe('amendment 14 — related files only where the runtime can open them', () => {
  const index = { id: 'index:h1', kind: 'index', occurredAt: '2026-11-01T10:00:00Z', originator: 'system',
    pathHash: 'h1', collection: 'default', state: 'FAILED' };

  it('in a browser a resolved file is named, and Open file / Show in folder are said to be desktop-only', async () => {
    const el = await mount();
    const feed = el.shadowRoot!.querySelector('jf-action-ledger') as unknown as { resolveHash: (r: string, h: string) => Promise<string | null> };
    feed.resolveHash = async () => '/home/me/docs/report.pdf';
    ledgerStream().snapshot([index]);
    historyStream().snapshot([]);
    await settle(el);
    feedRoot(el).querySelector<HTMLElement>('[data-testid="feed-quick"] [data-quick="problems"]')!.click();
    await settle(el);
    await select(el, 'index:h1');
    expect(text(testid(details(el), 'activity-details-file'))).toBe('/home/me/docs/report.pdf');
    expect(testid(details(el), 'details-open-file')).toBeNull();
    expect(text(testid(details(el), 'activity-details-file-unavailable'))).toContain('desktop app');
  });

  it('in the desktop app a resolved file offers Open file and Show in folder; an unresolved one offers neither until it resolves', async () => {
    (window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__ = {};
    try {
      const el = await mount();
      const feed = el.shadowRoot!.querySelector('jf-action-ledger') as unknown as { resolveHash: (r: string, h: string) => Promise<string | null> };
      let answer: string | null = null;
      feed.resolveHash = async () => answer;
      ledgerStream().snapshot([index]);
      historyStream().snapshot([]);
      await settle(el);
      feedRoot(el).querySelector<HTMLElement>('[data-quick="problems"]')!.click();
      await settle(el);
      await select(el, 'index:h1');
      expect(text(testid(details(el), 'activity-details-file'))).toBe('Not resolved yet');
      expect(testid(details(el), 'details-open-file')).toBeNull();
      answer = '/home/me/docs/report.pdf';
      ledgerStream().update({ ...index, id: 'index:h2', pathHash: 'h2' });
      await settle(el);
      expect(text(testid(details(el), 'activity-details-file'))).toBe('/home/me/docs/report.pdf');
      expect(testid(details(el), 'details-open-file')).not.toBeNull();
      expect(testid(details(el), 'details-show-in-folder')).not.toBeNull();
    } finally {
      delete (window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__;
    }
  });
});

describe('A10 — a burst and a scan list their members in details', () => {
  it('a burst counts its failures and its details list every member', async () => {
    const el = await mount();
    const idx = (i: number, state: string) => ({ id: `index:b${i}`, kind: 'index', originator: 'system', pathHash: `b${i}`,
      collection: 'docs', state, occurredAt: `2026-11-01T10:00:0${i}Z` });
    const feed = el.shadowRoot!.querySelector('jf-action-ledger') as unknown as { resolveHash: (r: string, h: string) => Promise<string | null> };
    feed.resolveHash = async () => null;
    ledgerStream().snapshot([idx(1, 'DONE'), idx(2, 'FAILED'), idx(3, 'DONE')]);
    historyStream().snapshot([]);
    await settle(el);
    feedRoot(el).querySelector<HTMLElement>('[data-quick="indexing"]')!.click();
    await settle(el);
    const burst = entries(el).find((e) => e.querySelector('[data-testid="ledger-burst"]'))!;
    expect(text(burst)).toContain('1 failed');
    await select(el, keyOf(burst));
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-member"]')]).toHaveLength(3);
    expect([...details(el)!.querySelectorAll('[data-testid="activity-details-member"][data-problem]')]).toHaveLength(1);
  });
});

// ---------------------------------------------------------------- run scope and arrivals

describe('A15 / amendment 7 — the current run is a scope of the feed, reachable when the stream is down', () => {
  it('the run read\'s records outside the general window show in the run scope; a previous run\'s late answer is not taken', async () => {
    const ctrl = getAgentSessionController(API) as unknown as { sessionId: string | null; loadHistory(): Promise<void> };
    ctrl.sessionId = 'run-1';
    ledgerAnswer = async () => { throw new Error('down'); };
    const el = await mount();
    const read = runReads.find((r) => r.sessionId === 'run-1');
    expect(read, 'Activity asks for the current run\'s records').toBeDefined();
    // The run switches while that read is out; the new run's read answers first.
    ctrl.sessionId = 'run-2';
    const second = ctrl.loadHistory();
    runReads.find((r) => r.sessionId === 'run-2')!.answer.resolve({ entries: [op(K2, { originator: 'agent', correlationId: 'run-2' })] });
    await second;
    read!.answer.resolve({ entries: [op(K1, { originator: 'agent', correlationId: 'run-1' })] });
    await settle(el);
    el.requestUpdate();
    await settle(el);
    const scope = feedRoot(el).querySelector<HTMLElement>('[data-testid="feed-scope-run"]');
    expect(scope, 'the advanced filter offers This run').not.toBeNull();
    scope!.click();
    await settle(el);
    expect(entries(el).map(keyOf)).toEqual([`operation:${K2}`]);
    expect(text(testid(feedRoot(el), 'feed-scope'))).toContain('current agent run');
  });
});

describe('A15 / amendment 13 — the journal arrival lands on this window\'s records, whatever filter was left', () => {
  it('a pointer to the journal resets the feed\'s filters to this window\'s records and puts the keyboard there', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'here' }, CORE_PROVENANCE, { originator: 'agent' });
    const el = await mount();
    ledgerStream().snapshot([op(K1)]);
    historyStream().snapshot([]);
    await settle(el);
    feedRoot(el).querySelector<HTMLElement>('[data-quick="problems"]')!.click();
    await settle(el);
    requestActivitySection('effect-journal');
    await settle(el);
    expect(takeActivitySection()).toBeNull();
    expect(testid(feedRoot(el), 'feed-scope')?.dataset.scope).toBe('local');
    expect(entries(el)).toHaveLength(1);
    expect(entries(el)[0]!.textContent).toContain('open-pane');
    expect(feedRoot(el).activeElement).toBe(entries(el)[0]);
  });

  it('an Activity closing before it renders leaves the request to another, which reveals it', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'here' }, CORE_PROVENANCE, { originator: 'agent' });
    const leaving = await mount();
    const staying = await mount();
    requestActivitySection('effect-journal');
    leaving.remove();
    await settle(staying);
    expect(takeActivitySection()).toBeNull();
    expect(testid(feedRoot(staying), 'feed-scope')?.dataset.scope).toBe('local');
  });
});

// ---------------------------------------------------------------- lifetimes

describe('amendment 6 — leaving Activity during reads: obsolete answers change nothing', () => {
  it('a receipt read answered after Activity closed does not reach the reopened Activity or move focus', async () => {
    const first = await mount();
    ledgerStream().snapshot([op(K1)]);
    historyStream().snapshot([]);
    await settle(first);
    await select(first, `operation:${K1}`);
    const late = receipt(K1);
    first.remove();
    const second = await mount();
    ledgerStream().snapshot([op(K1)]);
    historyStream().snapshot([]);
    await settle(second);
    const focused = document.activeElement;
    late.resolve({ state: 'failed', historySince: 0, message: 'stale answer' });
    await settle(second);
    expect(second.shadowRoot!.querySelector('jf-details-pane')).toBeNull();
    expect(text(second.shadowRoot)).not.toContain('stale answer');
    expect(document.activeElement).toBe(focused);
  });
});

describe('amendment 9 — a profile switch with details, a receipt read and an undo preview open', () => {
  it('closes them without confirming anything, keeps every record, and relabels none', async () => {
    const { el } = await undoableRecord(false);
    recordEffect({ kind: 'open-pane', paneId: 'after' }, CORE_PROVENANCE, { originator: 'agent' });
    await settle(el);
    await activate(testid(details(el), 'activity-details-restore'));
    await settle(el);
    expect(testid(details(el), 'activity-details-preview'), 'a restore preview is open').not.toBeNull();
    const before = entries(el).map((e) => e.textContent);
    const late = receipt(K1);
    const profile = `work-${listJournal().length}-${Math.random().toString(36).slice(2)}`;
    createProfile(profile, 'Work');
    setActiveProfileId(profile);
    await settle(el);
    expect(el.shadowRoot!.querySelector('jf-details-pane'), 'the details close').toBeNull();
    late.resolve({ state: 'failed', historySince: 0, message: 'answer for the old details' });
    await settle(el);
    expect(text(el.shadowRoot)).not.toContain('answer for the old details');
    expect(entries(el).map((e) => e.textContent)).toEqual(before);
    expect(entries(el).every((e) => e.getAttribute('aria-selected') === 'false')).toBe(true);
    expect(listJournal().some((e) => e.effect.kind === 'undo-operation' || e.effect.kind === 'close-pane')).toBe(false);
  });
});

describe('selection lifetime — a selected record no source holds any more', () => {
  it('stays readable with its last-known content, says so, and offers no actions', async () => {
    const el = await mount();
    ledgerStream().snapshot([op(K1, { operationId: 'core.open-file' })]);
    historyStream().snapshot([]);
    await settle(el);
    await select(el, `operation:${K1}`);
    ledgerStream().frame('LIFECYCLE', { kind: 'reset' });
    ledgerStream().snapshot([]);
    await settle(el);
    expect(testid(details(el), 'activity-details-evicted')).not.toBeNull();
    expect(text(testid(details(el), 'activity-details-title'))).toBe('Open File');
  });
});

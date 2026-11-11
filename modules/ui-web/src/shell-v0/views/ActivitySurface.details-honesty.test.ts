// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * S6 Activity, candidate 2 — what the details say is true, and in words.
 *   - Amendment 14: Export chosen confirms an export only once the clipboard took it; a refused write
 *     says nothing was exported.
 *   - Decision 2026-11-11: the details never show a recorded code as if it were a word. Outcomes, how
 *     an approval was asked for, and who recorded a journal entry are words; a code with no words, and
 *     an error code kept for support, are labelled as codes. A record this window journaled is titled
 *     by what it did, without the effect kind in front.
 * Activity runs against an Engine faked at `fetch`: the ledger and operation-history streams send SSE
 * `frame` events as SseEnvelopeWriter writes them; a receipt read answers what the test says.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import './ActivitySurface.js';
import type { ActivitySurface } from './ActivitySurface.js';
import { __resetJournalForTest, listJournal, recordEffect } from '../substrates/effects/index.js';
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
const KEY = '019a0000-0000-7000-8000-000000000001';

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
  snapshot(entries: unknown[]): void {
    this.seq += 1;
    this.enqueue(`event: frame\ndata: ${JSON.stringify({ streamId: this.path, frameKind: 'LIFECYCLE', seq: this.seq,
      ts: '2026-11-01T00:00:00Z', payload: { kind: 'snapshot', entries }, resumeToken: `t${this.seq}` })}\n\n`);
  }
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

const exportLabel = (el: ActivitySurface): string => {
  const button = testid(el.shadowRoot!, 'activity-export-chosen') as (HTMLElement & { label?: string }) | null;
  return `${button?.label ?? ''} ${text(button)}`;
};

// ---------------------------------------------------------------- export

describe('amendment 14 — Export chosen confirms only a copy the clipboard took', () => {
  async function chooseOne(): Promise<ActivitySurface> {
    const chosen = recordEffect({ kind: 'open-pane', paneId: 'chosen' }, CORE_PROVENANCE, { originator: 'agent' });
    const el = await mount();
    await select(el, chosen.ledgerId!);
    (testid(details(el), 'activity-details-choose') as HTMLInputElement).click();
    await settle(el);
    return el;
  }

  it('a refused clipboard write says nothing was exported, and never says Exported', async () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true,
      value: { writeText: () => Promise.reject(new DOMException('Write permission denied.', 'NotAllowedError')) } });
    const el = await chooseOne();
    await activate(testid(el.shadowRoot!, 'activity-export-chosen'));
    await settle(el);
    expect(exportLabel(el), 'no export is confirmed').not.toMatch(/Exported/);
    const failed = testid(el.shadowRoot!, 'activity-export-failed');
    expect(failed, 'the refusal is said').not.toBeNull();
    expect(failed!.getAttribute('role')).toBe('alert');
    expect(text(failed)).toMatch(/nothing was copied/);
  });

  it('a copy the clipboard took is confirmed once written, with nothing said to have failed', async () => {
    let finish!: () => void;
    const written: string[] = [];
    Object.defineProperty(navigator, 'clipboard', { configurable: true,
      value: { writeText: (t: string) => new Promise<void>((resolve) => { finish = () => { written.push(t); resolve(); }; }) } });
    const el = await chooseOne();
    await activate(testid(el.shadowRoot!, 'activity-export-chosen'));
    await settle(el);
    expect(exportLabel(el), 'nothing is confirmed while the clipboard has not answered').not.toMatch(/Exported/);
    finish();
    await settle(el);
    expect(written).toHaveLength(1);
    expect(exportLabel(el)).toMatch(/Exported 1 entry/);
    expect(testid(el.shadowRoot!, 'activity-export-failed')).toBeNull();
    expect(listJournal().some((e) => e.effect.kind === 'copy-to-clipboard'), 'the copy is journaled as every effect is').toBe(true);
  });
});

// ---------------------------------------------------------------- words, not codes

describe('decision 2026-11-11 — the details say recorded codes in words', () => {
  it('an operation\'s recorded outcome is a word: SUCCESS, FAILURE and UNDONE never show as they were recorded', async () => {
    const el = await mount([op('operation:a', { outcome: 'SUCCESS' }), op('operation:b', { outcome: 'FAILURE', occurredAt: '2026-11-01T09:00:00Z' }),
      op('operation:c', { outcome: 'UNDONE', occurredAt: '2026-11-01T08:00:00Z' })]);
    const seen: string[] = [];
    for (const key of ['operation:a', 'operation:b', 'operation:c']) {
      await select(el, key);
      seen.push(fact(el, 'activity-details-outcome'));
    }
    expect(seen).toEqual(['Succeeded', 'Failed', 'Undone']);
  });

  it('an outcome with no words is labelled as a code', async () => {
    const el = await mount([op('operation:a', { outcome: 'HALTED_BY_POLICY' })]);
    await select(el, 'operation:a');
    expect(fact(el, 'activity-details-outcome')).toBe('Not described (code HALTED_BY_POLICY)');
  });

  it('how an approval was asked for is said in words', async () => {
    const gate = (id: string, gateBehavior: string, occurredAt: string) => ({ id, kind: 'gate', originator: 'agent', occurredAt,
      disposition: 'GATED', operationId: 'core.rebuild-index', gateBehavior, transport: 'MCP' });
    const el = await mount([gate('gate:typed', 'TYPED_CONFIRM', '2026-11-01T10:00:00Z'), gate('gate:inline', 'INLINE_CONFIRM', '2026-11-01T09:00:00Z')]);
    await select(el, 'gate:typed');
    expect(fact(el, 'activity-details-asked')).toBe('A typed confirmation');
    expect(fact(el, 'activity-details-gate')).not.toMatch(/TYPED_CONFIRM/);
    await select(el, 'gate:inline');
    expect(fact(el, 'activity-details-asked')).toBe('A confirmation');
  });

  it('a failed receipt\'s error code is labelled as a code for support, beside what happened', async () => {
    receiptAnswer = { state: 'failed', historySince: 0, message: 'The engine refused the request.', result: { code: 'BAD_REQUEST' } };
    const el = await mount([op(`operation:${KEY}`, { operationId: 'core.rebuild-index', outcome: 'FAILURE' })]);
    await select(el, `operation:${KEY}`);
    const receipt = testid(details(el), 'activity-details-receipt');
    expect(receipt?.dataset.state).toBe('failed');
    const terms = [...receipt!.querySelectorAll('dt')].map((dt) => text(dt));
    expect(terms).toContain('Error code');
    expect(terms).not.toContain('Code');
    expect(fact(el, 'activity-details-code')).toBe('BAD_REQUEST (for support)');
    expect(text(receipt)).toMatch(/Failed/);
    expect(text(receipt)).toMatch(/The engine refused the request\./);
  });

  it('a record this window journaled is titled by what it did, and says who recorded it in words', async () => {
    const run = recordEffect({ kind: 'invoke-operation', operationId: 'core.rebuild-index' }, CORE_PROVENANCE, { originator: 'user' });
    const el = await mount();
    await select(el, run.ledgerId!);
    const title = fact(el, 'activity-details-title');
    expect(title).toBe(present({ kind: 'operation', id: 'core.rebuild-index' }).label);
    expect(title).not.toMatch(/invoke-operation/);
    expect(fact(el, 'activity-details-recorder')).toBe('JustSearch itself');
    expect(text(details(el))).not.toMatch(/\(CORE\)/);
  });

  it('a plugin that recorded an entry is named with how far it is trusted, not by its raw tier', async () => {
    const run = recordEffect({ kind: 'open-pane', paneId: 'notes' },
      { tier: 'UNTRUSTED_PLUGIN', contributorId: 'acme.notes', version: '1.0.0' }, { originator: 'agent' });
    const el = await mount();
    await select(el, run.ledgerId!);
    expect(fact(el, 'activity-details-recorder')).toBe('A plugin without trusted status (acme.notes)');
    expect(text(details(el))).not.toMatch(/UNTRUSTED_PLUGIN/);
  });
});

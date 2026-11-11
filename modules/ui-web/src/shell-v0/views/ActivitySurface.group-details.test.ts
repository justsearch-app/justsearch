// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * S6 Activity, candidate 2 — a grouped record's details: an indexing burst and a legacy scan rollup.
 * Their members are listed where the person reads them now, the details pane, by the labels the feed
 * gives them (a resolved file name, else the short-hash fallback). Like every record, a group says how
 * long it took and where it came from (the 14:10Z decision), and says so when that is not recorded.
 * Activity runs against an Engine faked at `fetch`: the ledger and operation-history streams send SSE
 * `frame` events as SseEnvelopeWriter writes them.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import './ActivitySurface.js';
import type { ActivitySurface } from './ActivitySurface.js';
import { __resetJournalForTest } from '../substrates/effects/index.js';
import { __resetRecallCursor } from '../substrates/recall/recallCursor.js';
import { __resetAgentSessionStore } from '../state/agentSessionStore.js';
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

/** A per-file indexing record as the backend projects it (ActionLedgerProjection.projectIndex). */
function index(n: number, state: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { id: `index:${n}`, kind: 'index', originator: 'system', pathHash: `hash${n}`, collection: 'docs', state,
    occurredAt: `2026-11-01T10:00:${String(n * 10).padStart(2, '0')}Z`, ...extra };
}

/** A legacy scan rollup and the per-document records it still has, as the ActionLedgerView tests send them. */
function scanWithDocs(n: number, scanId: string, extra: Record<string, unknown> = {}): Array<Record<string, unknown>> {
  const docs = Array.from({ length: n }, (_, i) => ({
    id: `index:${scanId}:${i}`, kind: 'index', occurredAt: `2026-08-06T00:00:${String(i).padStart(2, '0')}.000Z`,
    originator: 'system', collection: 'scifact', state: i === 0 ? 'FAILED' : 'DONE', pathHash: `hash${i}`, scanId,
  }));
  return [...docs, { id: `scan:${scanId}`, kind: 'operation', operationId: 'core.scan-root', occurredAt: '2026-08-06T00:01:00.000Z',
    originator: 'system', outcome: 'COMPLETED', scanId, collection: 'scifact', root: 'C:/corpus/scifact',
    docsDone: n - 1, docsFailed: 1, docsAdmitted: n, durationMs: 372_000, ...extra }];
}

beforeEach(() => {
  __resetJournalForTest(); __resetRecallCursor(); __resetAgentSessionStore();
  resetResourceCatalog();
  seedResourceCatalog({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [historyResource()] });
  streams = [];
});

beforeAll(() => { vi.stubGlobal('fetch', vi.fn(engineFetch)); });
afterAll(() => { vi.unstubAllGlobals(); });
afterEach(() => {
  document.body.replaceChildren();
  __resetAgentSessionStore();
  __resetJournalForTest();
});

// ---------------------------------------------------------------- reading Activity

type Resolver = (resource: string, hash: string) => Promise<string | null>;

/** Activity with the ledger snapshot `entries`, file names answered by `resolve`. */
async function mount(entries: unknown[], resolve: Resolver = async () => null): Promise<ActivitySurface> {
  const el = document.createElement('jf-activity-surface') as ActivitySurface;
  el.apiBase = API;
  document.body.append(el);
  await settle(el);
  (el.shadowRoot!.querySelector('jf-action-ledger') as unknown as { resolveHash: Resolver }).resolveHash = resolve;
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
const fact = (el: ActivitySurface, id: string): string => text(details(el)?.querySelector(`[data-testid="${id}"]`));
/** The members the details pane lists, as rendered. */
const members = (el: ActivitySurface): string[] =>
  [...(details(el)?.querySelectorAll('[data-testid="activity-details-member"]') ?? [])].map((m) => text(m));

/** Show the Indexing records and select the one burst they collapse into. */
async function selectBurst(el: ActivitySurface): Promise<Element> {
  feedRoot(el).querySelector<HTMLElement>('[data-quick="indexing"]')!.click();
  await settle(el);
  const bursts = [...feedRoot(el).querySelectorAll('[data-testid="ledger-burst"]')];
  expect(bursts, 'the indexing records collapse into one burst').toHaveLength(1);
  ((bursts[0]!.closest('.entry') ?? bursts[0]) as HTMLElement).click();
  await settle(el);
  return bursts[0]!;
}

/** Select the scan rollup of `scanId`. */
async function selectScan(el: ActivitySurface, scanId: string): Promise<void> {
  const rollup = [...feedRoot(el).querySelectorAll('[data-testid="ledger-scan-rollup"]')]
    .find((r) => r.getAttribute('data-scan-id') === scanId);
  expect(rollup, `the feed shows the rollup of ${scanId}`).toBeDefined();
  ((rollup!.closest('.entry') ?? rollup) as HTMLElement).click();
  await settle(el);
}

// ---------------------------------------------------------------- members, where they are read now

describe('A9 / A10 — a burst\'s members are listed in its details by the labels the feed gives them', () => {
  it('a burst of a known and an unknown state lists both: the unknown state named, never as indexed', async () => {
    const el = await mount([index(1, 'DONE'), index(2, 'PAUSED')]);
    const burst = await selectBurst(el);
    expect(text(burst)).toContain('Indexed 1 · 1 with unknown state · docs');
    const listed = members(el);
    expect(listed).toHaveLength(2);
    expect(listed.some((m) => m.includes('Index state unknown (PAUSED) · docs'))).toBe(true);
    expect(listed.some((m) => m.includes('Indexed · docs'))).toBe(true);
  });

  it('a member whose file resolved reads as its name; one that did not keeps the short-hash label', async () => {
    const el = await mount([index(1, 'DONE'), index(2, 'FAILED'), index(3, 'DONE')],
      async (_resource, hash) => (hash === 'hash1' ? 'C:/corpus/docs/report.pdf' : null));
    const burst = await selectBurst(el);
    expect(text(burst)).toContain('Indexed 2 · 1 failed · docs');
    const listed = members(el);
    expect(listed).toHaveLength(3);
    expect(listed.filter((m) => m.includes('Indexed · docs/report.pdf'))).toHaveLength(1);
    expect(listed.filter((m) => m.includes('Index failed · docs (hash2)'))).toHaveLength(1);
    expect(listed.filter((m) => m.includes('Indexed · docs (hash3)'))).toHaveLength(1);
  });
});

describe('D4 — a scan rollup\'s details list the per-document records of that scan', () => {
  it('lists that scan\'s documents (matched by scanId, not another scan\'s): a resolved one by name, the rest by short hash', async () => {
    const el = await mount([...scanWithDocs(3, 'scan-a'), ...scanWithDocs(2, 'scan-b')],
      async (_resource, hash) => (hash === 'hash1' ? 'C:/corpus/scifact/report.pdf' : null));
    expect(feedRoot(el).querySelectorAll('[data-testid="ledger-scan-rollup"]')).toHaveLength(2);
    await selectScan(el, 'scan-a');
    const listed = members(el);
    expect(listed, "scan-a's three documents, not scan-b's two").toHaveLength(3);
    const all = listed.join('|');
    expect(all).toContain('scifact/report.pdf');
    expect(all).toContain('(hash0)');
    expect(all).toContain('(hash2)');
  });

  it('says how long the scan took and where it came from, from its own rollup record', async () => {
    const el = await mount(scanWithDocs(3, 'scan-a', { transport: 'SYSTEM_INTERNAL' }));
    await selectScan(el, 'scan-a');
    expect(fact(el, 'activity-details-duration')).toBe('6m 12s');
    expect(fact(el, 'activity-details-source')).toBe('The engine');
  });
});

// ---------------------------------------------------------------- how long, and from where

describe('14:10Z decision — a burst says how long it took and where it came from', () => {
  it('members that record neither: the burst says both are not recorded, as an ordinary record does', async () => {
    const el = await mount([index(1, 'DONE'), index(2, 'FAILED'), index(3, 'DONE')]);
    await selectBurst(el);
    expect(members(el)).toHaveLength(3);
    expect(fact(el, 'activity-details-duration')).toBe('Not recorded');
    expect(fact(el, 'activity-details-source')).toBe('Not recorded');
  });

  it('members that record both: from the first start to the last end, and the source they share', async () => {
    // Started 10:00:06 (10 s less 4 s) and 10:00:17 (20 s less 3 s); the last ended at 10:00:20.
    const el = await mount([index(1, 'DONE', { durationMs: 4_000, transport: 'SYSTEM_INTERNAL' }),
      index(2, 'DONE', { durationMs: 3_000, transport: 'SYSTEM_INTERNAL' })]);
    await selectBurst(el);
    expect(fact(el, 'activity-details-duration')).toBe('14s');
    expect(fact(el, 'activity-details-source')).toBe('The engine');
  });

  it('members from different sources, one with a duration not recorded: each source named as the members are listed, the duration not recorded', async () => {
    const el = await mount([index(1, 'DONE', { durationMs: 4_000, transport: 'SYSTEM_INTERNAL' }),
      index(2, 'DONE', { transport: 'SCHEDULED' })]);
    await selectBurst(el);
    expect(fact(el, 'activity-details-duration')).toBe('Not recorded');
    // The members are listed newest first: the schedule's, then the engine's.
    expect(fact(el, 'activity-details-source')).toBe('Several: A schedule · The engine');
  });
});

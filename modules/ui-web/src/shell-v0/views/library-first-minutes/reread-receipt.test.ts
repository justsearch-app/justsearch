// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// fm-07 (fm-rest-a) — Library after "Read everything again", and a folder's meaning line while it is read:
//  L1' from the confirmation until the outcome completes or fails (it reads accepted or running), exactly
//      one line under the headline, "Reading everything again.", the same element throughout: before
//      reading shows, while it runs and between folders; never "accepted", "Completion has not yet been
//      confirmed", "Check reading outcome" or the activity paragraph. A lost or unconfirmed status keeps it.
//  L2' a completed outcome says "Everything has been read again." until the person leaves Library.
//  L3 the failed outcome keeps its message and alert; an outcome in no known state, or one that cannot be
//     read, keeps the uncertain sentence and its check action.
//  L4 while a folder is scanned or read, its "Search by meaning" fact says only what is known about
//     meaning ("Needs AI"), never anything about word search.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../LibrarySurface.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import type { IndexedRootView } from '../../../api/generated/schema-types/indexed-root-view.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { getOperationClient } from '../../operations/OperationClient.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../../state/aiStateStore.js';

const KEY = '0194f72c-0000-7000-8000-000000000001';
/** No AI installed: meaning work waits for it (the packaged app's first minutes). */
const NO_AI = { embeddingEnabled: false, spladeEnabled: false, nerEnabled: false };
function observe(reading: boolean) {
  __feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexHealthy: true,
    indexState: reading ? 'INDEXING' : 'IDLE', pendingJobs: reading ? 8 : 0 }, migration: { migrationState: 'IDLE' }, enrichment: NO_AI },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
  __feedContactForTest();
}
const READY: IndexedRootView = { pathHash: 'a', collection: 'default', status: 'indexed', lastIndexedIsoTime: '2026-11-17T09:00:00Z',
  fileCount: 8, failedCount: 0, inFlightCount: 0, walkCompleted: true };

async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 35; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function library(options: { reading: boolean; root?: IndexedRootView; outcome?: () => Response }) {
  observe(options.reading);
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async (path: string) => path.startsWith('/api/indexing-roots/substrate') ? Response.json({ items: [options.root ?? READY] })
    : path.startsWith('/api/operation-history/') && options.outcome ? options.outcome() : new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host; el.resolvedPaths = { a: '/home/me/Documents' };
  document.body.append(el); await settle(el);
  return el;
}
/** "Read everything again", then "Read again" in the ask; the Engine accepts with a request key. */
async function reread(el: LibrarySurface) {
  vi.spyOn(getOperationClient(el.apiBase), 'invokeWithConsent').mockResolvedValue({ success: true, message: 'Accepted', structuredData: { operationKey: KEY } });
  el.shadowRoot!.querySelector<HTMLButtonElement>('[data-reread]')!.click(); await settle(el);
  const ask = el.shadowRoot!.querySelector('[aria-label="Read every watched folder again?"]')!;
  [...ask.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Read again')!.click();
  await settle(el);
}
const headline = (el: LibrarySurface) => el.shadowRoot!.querySelector('.header h2')!.textContent!.trim();
/** Every line the page says about reading outside its headline: the receipt and the line under it. */
const said = (el: LibrarySurface) => [...el.shadowRoot!.querySelectorAll('.action-message')]
  .map(node => node.textContent!.replace(/\s+/g, ' ').trim()).filter(Boolean);
const checkAction = (el: LibrarySurface) => el.shadowRoot!.querySelector('jf-button[label="Check reading outcome"]');
const outcome = (state: string) => () => Response.json({ state, historySince: 0 });

beforeEach(() => { vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('No backend in this test'); })); });
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const LINE = 'Reading everything again.';
const DONE = 'Everything has been read again.';
const NEVER = /accepted|Completion has not yet been confirmed|recorded request|Check reading outcome/;
const line = (el: LibrarySurface) => [...el.shadowRoot!.querySelectorAll('.action-message')].find(node => node.textContent!.trim() === LINE);
/** The status ticks; a tick 5 s after the last outcome read reads the outcome again (Library's own rule). */
let clock = Date.parse('2026-11-17T10:00:00Z');
async function tick(el: LibrarySurface, reading: boolean) {
  clock += 5_000;
  observe(reading); await settle(el);
}
const outcomeReads = (el: LibrarySurface) => (el.host_.data.fetch as ReturnType<typeof vi.fn>).mock.calls
  .filter(([path]) => String(path).startsWith('/api/operation-history/')).length;

describe("L1' one constant line from the confirmation until the outcome completes", () => {
  beforeEach(() => { vi.spyOn(Date, 'now').mockImplementation(() => clock); });

  it('accepted -> running without visible reading -> reading visible -> a gap between folders -> complete', async () => {
    let state = 'accepted';
    const el = await library({ reading: false, outcome: () => Response.json({ state, historySince: 0 }) });
    await reread(el);
    // Accepted: the outcome was read and says accepted; reading does not show yet.
    expect(outcomeReads(el)).toBe(1);
    expect(headline(el)).not.toBe('Reading');
    expect(said(el)).toEqual([LINE]);
    const node = line(el)!;
    expect(node.getAttribute('role')).toBe('status');
    expect(checkAction(el)).toBeNull();
    expect(el.shadowRoot!.textContent).not.toMatch(NEVER);
    // The Engine reports running at once; reading becomes visible later.
    state = 'running';
    await tick(el, false);
    expect(outcomeReads(el)).toBe(2);
    expect(headline(el)).not.toBe('Reading');
    expect(said(el)).toEqual([LINE]);
    expect(line(el)).toBe(node);
    expect(el.shadowRoot!.textContent).not.toMatch(NEVER);
    // Reading shows.
    await tick(el, true);
    expect(outcomeReads(el)).toBe(3);
    expect(headline(el)).toBe('Reading');
    expect(said(el)).toEqual([LINE]);
    expect(line(el)).toBe(node);
    expect(el.shadowRoot!.textContent).not.toMatch(NEVER);
    // Between folders: no reading shows for a moment, the outcome still says running.
    await tick(el, false);
    expect(outcomeReads(el)).toBe(4);
    expect(headline(el)).not.toBe('Reading');
    expect(said(el)).toEqual([LINE]);
    expect(line(el)).toBe(node);
    expect(checkAction(el)).toBeNull();
    expect(el.shadowRoot!.textContent).not.toMatch(NEVER);
    // A status refresh with no outcome read (inside 4 s) leaves the same element too.
    clock += 1_000; observe(true); await settle(el);
    expect(outcomeReads(el)).toBe(4);
    expect(said(el)).toEqual([LINE]);
    expect(line(el)).toBe(node);
    // L2' — complete.
    state = 'complete';
    await tick(el, false);
    expect(said(el)).toEqual([DONE]);
    expect(el.shadowRoot!.textContent).not.toMatch(NEVER);
    expect(checkAction(el)).toBeNull();
  });

  it('reading visible from the first moment: the same one line, not a second "Reading"', async () => {
    const el = await library({ reading: true, outcome: outcome('running') });
    await reread(el);
    expect(headline(el)).toBe('Reading');
    expect(said(el)).toEqual([LINE]);
    expect(el.shadowRoot!.textContent).not.toMatch(NEVER);
  });

  it('a lost status while the outcome runs still shows only the line; the headline says what is known', async () => {
    const el = await library({ reading: false, outcome: outcome('running') });
    await reread(el);
    const node = line(el)!;
    __resetAiStateForTest(); await settle(el);
    expect(headline(el)).not.toBe('Reading');
    expect(said(el)).toEqual([LINE]);
    expect(line(el)).toBe(node);
    expect(el.shadowRoot!.textContent).not.toMatch(NEVER);
  });
});

describe("L2' the completed outcome", () => {
  it('"Everything has been read again." alone, kept while in Library, gone after leaving and returning', async () => {
    const el = await library({ reading: false, outcome: outcome('complete') });
    await reread(el);
    expect(said(el)).toEqual([DONE]);
    observe(true); await settle(el);
    expect(said(el)).toEqual([DONE]);
    el.remove(); document.body.append(el); await settle(el);
    expect(said(el)).toEqual([]);
  });
  it('completed while reading still runs: the same sentence, without a second "Reading" line', async () => {
    const el = await library({ reading: true, outcome: outcome('complete') });
    await reread(el);
    expect(headline(el)).toBe('Reading');
    expect(said(el)).toEqual([DONE]);
  });
});

describe('L3 outcomes that are not in progress keep their messages', () => {
  it('failed keeps its message and alert role', async () => {
    const failed = await library({ reading: false, outcome: () => Response.json({ state: 'failed', historySince: 0 }) });
    await reread(failed);
    expect(failed.shadowRoot!.querySelector('.action-message[role="alert"]')?.textContent).toContain('Read again did not complete');
    expect(said(failed)).not.toContain(LINE);
  });
  it('an outcome in no known state: the uncertain sentence and its check action', async () => {
    const el = await library({ reading: false, outcome: outcome('expired') });
    await reread(el);
    expect(said(el)[0]).toContain('Read again accepted. Its outcome is unavailable; check again before retrying.');
    expect(checkAction(el)).not.toBeNull();
  });
  it('an outcome that cannot be read: the uncertain sentence and its check action', async () => {
    const el = await library({ reading: false });
    await reread(el);
    expect(said(el)[0]).toContain('Read again accepted. Its outcome could not be read; check again before retrying.');
    expect(checkAction(el)).not.toBeNull();
  });
  it('"Check reading outcome" reads the outcome again and says what it now is', async () => {
    let readable = false;
    const el = await library({ reading: false, outcome: () => readable ? Response.json({ state: 'complete', historySince: 0 }) : new Response('{}', { status: 503 }) });
    await reread(el);
    expect(said(el)[0]).toContain('Its outcome could not be read');
    const reads = outcomeReads(el);
    readable = true;
    (checkAction(el) as unknown as { onActivate: () => void }).onActivate(); await settle(el);
    expect(outcomeReads(el)).toBe(reads + 1);
    expect(said(el)).toEqual([DONE]);
    expect(checkAction(el)).toBeNull();
  });
  it('a later read that fails replaces the line with the uncertain sentence', async () => {
    let ok = true;
    vi.spyOn(Date, 'now').mockImplementation(() => clock);
    const el = await library({ reading: false, outcome: () => ok ? Response.json({ state: 'running', historySince: 0 }) : new Response('{}', { status: 503 }) });
    await reread(el);
    expect(said(el)).toEqual([LINE]);
    ok = false;
    await tick(el, false);
    expect(said(el)[0]).toContain('Its outcome could not be read');
    expect(checkAction(el)).not.toBeNull();
  });
});

describe('L4 the meaning line while a folder is read', () => {
  const meaning = (el: LibrarySurface) => {
    el.shadowRoot!.querySelector<HTMLButtonElement>('.folder-toggle')!.click();
    return settle(el).then(() => [...el.shadowRoot!.querySelectorAll('.folder-details dl > div')]
      .find(row => row.querySelector('dt')?.textContent === 'Search by meaning')?.querySelector('dd')?.textContent?.trim());
  };
  it('a folder being read says "Needs AI" and nothing about word search', async () => {
    const el = await library({ reading: true, root: { ...READY, inFlightCount: 8 } });
    expect(await meaning(el)).toBe('Needs AI');
  });
  it('a folder being scanned says "Needs AI" and nothing about word search', async () => {
    const el = await library({ reading: true, root: { ...READY, lastIndexedIsoTime: undefined, status: 'scanning', walkCompleted: false } as IndexedRootView });
    expect(await meaning(el)).toBe('Needs AI');
  });
  it('kept: a folder that is up to date still says word search is available', async () => {
    const el = await library({ reading: false });
    expect(await meaning(el)).toBe('Needs AI · word search is available');
  });
});

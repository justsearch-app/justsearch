// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// fm-07 (fm-rest-a) — Library after "Read everything again", and a folder's meaning line while it is read:
//  L1 while reading visibly runs (the headline says so) the page shows no second "Reading" line and no
//     sentence about the request being accepted or its completion being unconfirmed.
//  L2 between the confirmation and reading becoming visible, one plain line: "Starting to read
//     everything again."
//  L3 the completed and the failed outcome keep their messages; with no reading visibly running and the
//     outcome still unconfirmed or unreadable, the page says so and offers its check action.
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

describe('L1 while reading visibly runs, the headline says it once', () => {
  it('the Engine still runs the request: no second "Reading" line, no accepted or unconfirmed sentence', async () => {
    const el = await library({ reading: true, outcome: outcome('running') });
    await reread(el);
    expect(headline(el)).toBe('Reading');
    expect(said(el)).toEqual([]);
    expect(el.shadowRoot!.textContent).not.toMatch(/accepted|Completion has not yet been confirmed/);
  });
  it('the request completed while reading still runs: its message, without a second "Reading" line', async () => {
    const el = await library({ reading: true, outcome: outcome('complete') });
    await reread(el);
    expect(headline(el)).toBe('Reading');
    expect(said(el)).toEqual(['Read again completed for the recorded request. Current reading activity is shown below.']);
  });
});

describe('L2 between the confirmation and reading becoming visible', () => {
  it('one plain line "Starting to read everything again.", then nothing while reading runs', async () => {
    const el = await library({ reading: false, outcome: outcome('accepted') });
    await reread(el);
    expect(said(el)).toEqual(['Starting to read everything again.']);
    expect(checkAction(el)).toBeNull();
    observe(true); await settle(el);
    expect(headline(el)).toBe('Reading');
    expect(said(el)).toEqual([]);
  });
});

describe('L3 outcomes keep their messages', () => {
  it('reading ended but the outcome is still unconfirmed: the page says so and offers its check action', async () => {
    const el = await library({ reading: false, outcome: outcome('running') });
    await reread(el);
    observe(true); await settle(el);
    observe(false); await settle(el);
    expect(said(el)[0]).toContain('Read again accepted. Completion has not yet been confirmed.');
    expect(checkAction(el)).not.toBeNull();
  });
  it('an outcome that cannot be read: the page says so and offers its check action', async () => {
    const el = await library({ reading: false });
    await reread(el);
    expect(said(el)[0]).toContain('Read again accepted. Its outcome could not be read; check again before retrying.');
    expect(checkAction(el)).not.toBeNull();
  });
  it('completed and failed keep their messages', async () => {
    const done = await library({ reading: false, outcome: outcome('complete') });
    await reread(done);
    expect(said(done)[0]).toContain('Read again completed for the recorded request.');
    done.remove();
    const failed = await library({ reading: false, outcome: () => Response.json({ state: 'failed', historySince: 0 }) });
    await reread(failed);
    expect(failed.shadowRoot!.querySelector('.action-message[role="alert"]')?.textContent).toContain('Read again did not complete');
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

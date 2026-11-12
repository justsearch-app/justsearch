// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// FA4 — Library's "N searchable documents" counts only the person's own files (the Engine's
// `ownSearchableDocuments`), never the bundled Help; none in words; unknown said, never 0.
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';

const HELP = 250;
const root: IndexedRootView = { pathHash: 'a', status: 'indexed', collection: 'personal',
  fileCount: 10, inFlightCount: 0, failedCount: 0, walkCompleted: true };

/** A settled status with `own` of the person's files beside the bundled Help; `own: undefined` is an Engine that does not report it. */
function observe(own: number | undefined): void {
  const searchable = (own ?? 0) + HELP;
  __feedForTest({ status: { worker: { core: { indexedDocuments: searchable, searchableDocuments: searchable,
    ...(own === undefined ? {} : { ownSearchableDocuments: own }),
    indexState: 'IDLE', indexHealthy: true }, migration: { migrationState: 'IDLE' } } } as unknown as StatusSnapshot });
}
async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function header(): Promise<{ headline: string; count: string }> {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async () => new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host;
  document.body.append(el); await settle(el);
  el.roots = [root]; el.resolvedPaths = { a: '/notes' }; el.loading = false; await settle(el);
  return { headline: el.shadowRoot!.querySelector('h2')!.textContent!.trim(),
    count: el.shadowRoot!.querySelector('.subtitle')!.textContent!.split('·')[0]!.replace(/\s+/g, ' ').trim() };
}
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.restoreAllMocks(); });

it('FA4 counts the person\'s own files, not the bundled Help', async () => {
  observe(3);
  expect(await header()).toEqual({ headline: 'Your files are searchable', count: '3 searchable documents' });
});

it('FA4 one own file reads in the singular', async () => {
  observe(1);
  expect((await header()).count).toBe('1 searchable document');
});

it('FA4 with no own files it says so in words, whatever Help holds', async () => {
  observe(0);
  expect(await header()).toEqual({ headline: 'Indexing your files', count: 'None of your files are searchable yet' });
});

it('FA4 an Engine that does not report the own count: unknown in words, never a number', async () => {
  observe(undefined);
  const { count } = await header();
  expect(count).toBe('Searchable document count not known');
});

it('FA4 the own count reported late replaces the unknown one', async () => {
  observe(undefined);
  expect((await header()).count).toBe('Searchable document count not known');
  observe(5); await new Promise(r => setTimeout(r, 0));
  const el = document.querySelector('jf-library-surface') as LibrarySurface; await settle(el);
  expect(el.shadowRoot!.querySelector('.subtitle')!.textContent).toContain('5 searchable documents');
});

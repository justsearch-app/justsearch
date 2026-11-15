// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import '../LibrarySurface.js';
import '../BrowseSurface.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import type { BrowseSurface } from '../BrowseSurface.js';
import type { IndexedRootView } from '../../../api/generated/schema-types/indexed-root-view.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../../state/aiStateStore.js';
import { getIndexedRoots, readIndexedRoots } from '../../state/indexedRoots.js';
import { folderStatus } from '../../state/folderStatus.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';

function must<T extends Element = HTMLElement>(root: ParentNode | null, selector: string): T {
  expect(root, `root for ${selector}`).not.toBeNull();
  const found = root!.querySelector<T>(selector);
  expect(found, selector).not.toBeNull();
  return found!;
}
async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 35; i++) { await Promise.resolve(); await el.updateComplete; }
}
function observe() {
  __feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8,
    indexState: 'IDLE', indexHealthy: true }, migration: { migrationState: 'IDLE' } } } as unknown as StatusSnapshot });
}
const root: IndexedRootView = { pathHash: 'a', status: 'indexed', fileCount: 8, failedCount: 0,
  inFlightCount: 0, walkCompleted: true };
async function library(row: IndexedRootView = root) {
  observe();
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async path => path.startsWith('/api/indexing-roots/substrate')
    ? Response.json({ items: [row] }) : new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host; el.resolvedPaths = { a: '/Documents' };
  document.body.append(el); await settle(el);
  return { el, host };
}
type Root = { path: string; fileCount?: number; fileCountCapped?: boolean };
async function browse(roots: Root[] = [{ path: '/Documents' }]) {
  const host = createMockHostApi();
  let entries = ['old.md', 'keep.md'];
  let failed = false;
  let pendingFiles: Promise<void> | undefined;
  const fetch = vi.fn(async (path: string) => path.includes('/api/indexing/roots') ? Response.json({ roots })
    : failed ? new Response('{}', { status: 503 })
    : path.endsWith('/folders') ? Response.json({ folders: [{ path: '/Documents/Nested', name: 'Nested' }] })
    : await (async () => { await pendingFiles;
      return Response.json({ files: entries.map(name => ({ docId: `/Documents/${name}`, fields: { path: `/Documents/${name}`, filename: name } })) });
    })());
  host.data.fetch = fetch;
  const el = document.createElement('jf-browse-surface') as BrowseSurface;
  el.host_ = host; document.body.append(el); await settle(el);
  return { el, fetch, setEntries: (names: string[]) => { entries = names; }, fail: (value: boolean) => { failed = value; },
    holdFiles: () => {
      let release!: () => void;
      pendingFiles = new Promise<void>(resolve => { release = resolve; });
      return () => { pendingFiles = undefined; release(); };
    } };
}
function key(el: Element, value: string) { el.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, composed: true })); }
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); setShowDeveloperTools(false); vi.restoreAllMocks(); });

it('a failed background inventory read makes the headline and retained folder facts unconfirmed despite fresh status', async () => {
  const { el, host } = await library();
  expect(must(el.shadowRoot, 'h2').textContent).toBe('Your files are searchable');
  host.data.fetch = vi.fn(async () => new Response('{}', { status: 503 }));
  await readIndexedRoots(async () => new Response('{}', { status: 503 }));
  observe(); await settle(el);
  expect(getIndexedRoots().confirmed).toBe(false);
  expect(must(el.shadowRoot, 'h2').textContent).toBe('Reading status unconfirmed');
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('Last known 8 files');
  expect(el.shadowRoot!.textContent).not.toContain('Up to date');
  must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  expect(must(el.shadowRoot, '.folder-details').textContent).toContain('Not known');
  await readIndexedRoots(async () => Response.json({ items: [root] })); await settle(el);
  expect(must(el.shadowRoot, 'h2').textContent).toBe('Your files are searchable');
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('8 files');
});

it.each([true, undefined])('count provenance is shared by Library, Browse, removal and status rows (cap=%s)', async cap => {
  const row = { ...root, fileCount: 50000, fileCountCapped: cap };
  const { el } = await library(row);
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('50,000+ files');
  must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  const remove = must<HTMLElement & { onActivate(): void }>(el.shadowRoot, '[label="Remove from library"]');
  remove.onActivate(); await settle(el);
  const band = must<HTMLElement & { consequence: string }>(el.shadowRoot, 'jf-confirm-band');
  expect(band.consequence).toContain('50,000+ files leave');
  band.dispatchEvent(new CustomEvent('confirm-band-cancel', { bubbles: true, composed: true })); await settle(el);
  const ctx = { relativeTime: '', verifiedRelativeTime: '', provisional: false };
  expect(folderStatus(row, ctx).metaText).toContain('50,000+ files');
  const { el: tree } = await browse([{ path: '/Documents', fileCount: 50000, fileCountCapped: cap }]);
  expect(must(tree.shadowRoot, '.row .meta').textContent).toBe('50,000+ files');
  for (const count of [-1, undefined]) {
    el.roots = [{ ...root, fileCount: count }]; tree.roots = [{ path: '/Documents', name: 'Documents', fileCount: count }];
    await settle(el); await settle(tree);
    expect(must(el.shadowRoot, '.files-found').textContent).toBe('');
    expect(must(tree.shadowRoot, '.row .meta').textContent).toBe('');
    expect(folderStatus({ ...root, fileCount: count }, ctx).metaText).not.toMatch(/\d+ files/);
    must<HTMLElement & { onActivate(): void }>(el.shadowRoot, '[label="Remove from library"]').onActivate(); await settle(el);
    const unknownBand = must<HTMLElement & { consequence: string }>(el.shadowRoot, 'jf-confirm-band');
    expect(unknownBand.consequence).toMatch(/^Its files leave search/);
    unknownBand.dispatchEvent(new CustomEvent('confirm-band-cancel', { bubbles: true, composed: true })); await settle(el);
  }
  el.roots = [{ ...root, fileCount: 50000, fileCountCapped: false }];
  tree.roots = [{ path: '/Documents', name: 'Documents', fileCount: 50000, fileCountCapped: false }];
  await settle(el); await settle(tree);
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('50,000 files');
  expect(must(tree.shadowRoot, '.row .meta').textContent).toBe('50,000 files');
});

it('nested registrations have independent expansion, roving focus and parent recovery for each occurrence', async () => {
  const { el } = await browse([{ path: '/Documents' }, { path: '/Documents/Nested' }]);
  must<HTMLElement>(el.shadowRoot, '[data-path="/Documents"]').click(); await settle(el);
  const copies = [...el.shadowRoot!.querySelectorAll<HTMLElement>('[data-path="/Documents/Nested"]')];
  expect(copies).toHaveLength(2);
  copies[0]!.focus(); await settle(el);
  expect(el.shadowRoot!.querySelectorAll('[role="treeitem"][tabindex="0"]')).toHaveLength(1);
  expect(copies[0]!.getAttribute('tabindex')).toBe('0'); expect(copies[1]!.getAttribute('tabindex')).toBe('-1');
  key(copies[0]!, 'ArrowRight'); await settle(el);
  expect(copies[0]!.getAttribute('aria-expanded')).toBe('true');
  expect(copies[1]!.getAttribute('aria-expanded')).toBe('false');
  copies[1]!.focus(); key(copies[1]!, 'ArrowUp'); await settle(el);
  expect(el.shadowRoot!.activeElement).not.toBe(copies[1]);
  copies[0]!.focus(); el.expanded = {}; await settle(el);
  expect(el.shadowRoot!.activeElement).toBe(must(el.shadowRoot, '[data-path="/Documents"]'));
});

it('ordinary Browse reloads roots on show and replaces cached children on each expansion', async () => {
  const { el, fetch, setEntries, holdFiles } = await browse();
  expect(el.shadowRoot!.querySelector('[label="Refresh"]')).toBeNull();
  expect(el.shadowRoot!.querySelector('.filter-input')).toBeNull();
  const folder = must<HTMLElement>(el.shadowRoot, '[data-path="/Documents"]');
  folder.click(); await settle(el); expect(el.shadowRoot!.querySelector('[data-path="/Documents/old.md"]')).not.toBeNull();
  folder.click(); await settle(el); setEntries(['keep.md', 'new.md']);
  const release = holdFiles();
  folder.click(); await settle(el);
  expect(fetch.mock.calls.filter(([path]) => path.endsWith('/folder-files'))).toHaveLength(2);
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/old.md"]')).not.toBeNull();
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/new.md"]')).toBeNull();
  const keep = must<HTMLElement>(el.shadowRoot, '[data-path="/Documents/keep.md"]');
  keep.focus(); await settle(el); release(); await settle(el);
  expect(el.shadowRoot!.activeElement).toBe(keep);
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/old.md"]')).toBeNull();
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/new.md"]')).not.toBeNull();
  el.remove(); document.body.append(el); await settle(el);
  expect(fetch.mock.calls.filter(([path]) => path.includes('/api/indexing/roots'))).toHaveLength(2);
});

it('a failed child reload preserves cached rows and exposes Retry, which replaces them on success', async () => {
  const { el, setEntries, fail } = await browse();
  const folder = must<HTMLElement>(el.shadowRoot, '[data-path="/Documents"]');
  folder.click(); await settle(el); folder.click(); await settle(el); fail(true);
  folder.click(); await settle(el);
  const retry = must<HTMLElement & { onActivate(): void }>(el.shadowRoot, '[label="Retry Documents"]');
  expect(el.shadowRoot!.textContent).toContain('Could not load Documents');
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/old.md"]')).not.toBeNull();
  fail(false); setEntries(['replacement.md']); retry.onActivate(); await settle(el);
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/replacement.md"]')).not.toBeNull();
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/old.md"]')).toBeNull();
  expect(el.shadowRoot!.textContent).not.toContain('Could not load Documents');
});

it('complete root coverage wins over a global meaning-work block affecting other folders', async () => {
  const { el } = await library();
  Object.assign(el, { enrichmentStages: { embedding: true, splade: false, ner: false }, enrichmentBlocked: true,
    roots: [{ ...root, parentDocsTotalEmbedding: 8, parentDocsSettledEmbedding: 8 }] });
  await settle(el); must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  const meaning = () => [...el.shadowRoot!.querySelectorAll('.folder-details dl > div')].find(row => row.querySelector('dt')?.textContent === 'Search by meaning');
  expect(meaning()).toBeDefined(); expect(must(meaning()!, 'dd').textContent).toBe('On');
  el.roots = [{ ...el.roots[0]!, parentDocsSettledEmbedding: 4 }]; await settle(el);
  expect(meaning()).toBeDefined(); expect(must(meaning()!, 'dd').textContent).toContain('Blocked');
});

it('remaining source and rebuild messages use reading/search-data wording and preserve consequences', async () => {
  const { el } = await library();
  Object.assign(el, { otherSources: { sources: [{ collection: 'notes', docCount: 3 }], truncated: true },
    reindexCancelKey: '0194f72c-0000-7000-8000-000000000001' });
  await settle(el);
  const sources = must(el.shadowRoot, '[data-testid="library-other-sources"]');
  expect(sources.textContent).toContain('Nothing reads them again');
  expect(sources.textContent).toContain('removing a source is permanent');
  expect(must(el.shadowRoot, '.gap-decision h3').textContent).toBe('Reading everything again');
  expect(must(el.shadowRoot, '.gap-decision').textContent).toContain('current search data stays available');
  expect(el.shadowRoot!.textContent).not.toMatch(/\bindex(?:es|ed|ing)?\b|re-index/i);
});

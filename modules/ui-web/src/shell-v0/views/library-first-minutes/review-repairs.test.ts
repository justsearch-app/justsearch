// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
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
import { observeStatusSignal } from '../../../api/statusInvalidation.js';

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
/** Word search works; no AI is installed, so meaning work waits (the captured no-model enrichment block). */
function observeNoAi() {
  __feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true },
    migration: { migrationState: 'IDLE' }, enrichment: { embeddingEnabled: false, spladeEnabled: false, nerEnabled: false,
      embeddingDocCount: 8, embeddingPendingCount: 8, embeddingCoveragePercent: 0, spladeDocCount: 8, spladePendingCount: 8,
      spladeCoveragePercent: 0, pendingNerCount: 8, completedNerCount: 0,
      chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2, chunkVectorsReady: false } } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
}
const root: IndexedRootView = { pathHash: 'a', status: 'indexed', fileCount: 8, failedCount: 0,
  inFlightCount: 0, walkCompleted: true };
async function library(row: IndexedRootView = root, feed: () => void = observe) {
  feed();
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
  expect(el.shadowRoot!.textContent).toContain("Couldn't load this folder");
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/old.md"]')).not.toBeNull();
  fail(false); setEntries(['replacement.md']); retry.onActivate(); await settle(el);
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/replacement.md"]')).not.toBeNull();
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/old.md"]')).toBeNull();
  expect(el.shadowRoot!.textContent).not.toContain("Couldn't load this folder");
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

it('V1: with no AI installed the Library says each fact once and names meaning only as Search by meaning', async () => {
  const { el } = await library(root, observeNoAi);
  expect(el.enrichmentBlocked).toBe(true);
  expect(must(el.shadowRoot, 'h2').textContent).toBe('Your files are searchable');
  expect(must(el.shadowRoot, '.card-meta').textContent).toBe('Up to date');
  must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  const meaning = [...el.shadowRoot!.querySelectorAll('.folder-details dl > div')].find(row => row.querySelector('dt')?.textContent === 'Search by meaning');
  expect(meaning).toBeDefined(); expect(must(meaning!, 'dd').textContent).toContain('Needs AI');
  const text = el.shadowRoot!.textContent ?? '';
  expect(text).not.toMatch(/semantic search/i);
  expect(text.match(/Needs AI/g) ?? []).toHaveLength(1);
});

it('V2: a provisional state that is not an observed rebuild is not called "Rebuilding"', async () => {
  const { el } = await library();
  Object.assign(el, { provisional: true, rebuilding: false }); await settle(el);
  expect(must(el.shadowRoot, '.card-meta').textContent).not.toBe('');
  expect(el.shadowRoot!.textContent).not.toMatch(/Rebuilding/);
  Object.assign(el, { rebuilding: true }); el.requestUpdate(); await settle(el);
  expect(must(el.shadowRoot, '.card-meta').textContent).toContain('Rebuilding');
});

it('V2: walk codes are plain words, with codes only behind developer tools', async () => {
  const missing = { ...root, status: 'unavailable', walkError: 'ROOT_NOT_DIRECTORY' };
  const { el } = await library(missing);
  Object.assign(el, { provisional: true, rebuilding: false }); await settle(el);
  expect(el.shadowRoot!.querySelector('.attention-item')).not.toBeNull();
  let text = el.shadowRoot!.textContent ?? '';
  expect(text).not.toContain('ROOT_NOT_DIRECTORY');
  expect(text).toContain('This folder is missing or is not a folder');
  setShowDeveloperTools(true); await settle(el);
  must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  expect(el.shadowRoot!.textContent).toContain('ROOT_NOT_DIRECTORY');
  setShowDeveloperTools(false);
  Object.assign(el, { provisional: false, rebuilding: false, roots: [{ ...root, status: 'error', walkError: 'INCOMPLETE_COVERAGE' }] }); await settle(el);
  expect(must(el.shadowRoot, '.card-meta').textContent).toBe("Some subfolders couldn't be read");
  text = el.shadowRoot!.textContent ?? '';
  expect(text).not.toContain('INCOMPLETE_COVERAGE');
});

it('V4: Browse lists folders and then files by name', async () => {
  const { el, setEntries } = await browse();
  setEntries(['b.md', 'A.md', 'c.md']);
  must<HTMLElement>(el.shadowRoot, '[data-path="/Documents"]').click(); await settle(el);
  expect(el.shadowRoot!.querySelector('[data-path="/Documents/c.md"]')).not.toBeNull();
  expect([...el.shadowRoot!.querySelectorAll<HTMLElement>('[role="treeitem"][aria-level="2"]')].map(row => row.dataset.path))
    .toEqual(['/Documents/Nested', '/Documents/A.md', '/Documents/b.md', '/Documents/c.md']);
});

it('V4: a total of one file reads "1 file"', async () => {
  const { el: lib } = await library({ ...root, fileCount: 1, fileCountCapped: false });
  expect(must(lib.shadowRoot, '.files-found').textContent).toBe('1 file');
});

it('V5: the shared formatter groups digits even where the runtime does not', async () => {
  const Native = Intl.NumberFormat;
  vi.spyOn(Intl, 'NumberFormat').mockImplementation(function (locale?: string | string[], options?: Intl.NumberFormatOptions) {
    return new Native(locale ?? 'en-US', locale === undefined ? { ...options, useGrouping: false } : options);
  } as unknown as typeof Intl.NumberFormat);
  expect(new Intl.NumberFormat().format(50000)).toBe('50000');
  vi.resetModules();
  const format = await import('../../display/format.js');
  expect(typeof format.formatFileCount, 'format.formatFileCount').toBe('function');
  expect(format.formatFileCount(50_000, true)).toBe('50,000+ files');
  expect(format.formatCount(1234)).toBe('1,234');
});

it('V5: Library and Browse show one count per folder, the newest either of them read', async () => {
  const { el } = await library({ ...root, fileCount: -1 });
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('');
  const { el: tree } = await browse([{ path: '/Documents', fileCount: 8, fileCountCapped: false }]);
  expect(must(tree.shadowRoot, '.row .meta').textContent).toBe('8 files');
  await settle(el);
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('8 files');
});

it('V5: a counted folder read that finishes after a newer counts-free read still delivers its count', async () => {
  observe();
  const row = { ...root, pathHash: 'overtaken-count' };
  let release!: () => void;
  const held = new Promise<void>(done => { release = done; });
  const counted = readIndexedRoots(async () => { await held; return Response.json({ items: [{ ...row, fileCount: 8 }] }); }, true);
  await readIndexedRoots(async () => Response.json({ items: [{ ...row, fileCount: -1 }] }), false);
  expect(getIndexedRoots().roots.find(item => item.pathHash === row.pathHash)?.fileCount).toBe(-1);
  release(); await counted;
  expect(getIndexedRoots().roots.find(item => item.pathHash === row.pathHash)?.fileCount).toBe(8);
});

async function reread(el: LibrarySurface) {
  must<HTMLButtonElement>(el.shadowRoot, '[data-reread]').click(); await settle(el);
  const ask = must(el.shadowRoot, '[aria-label="Read every watched folder again?"]');
  const confirm = [...ask.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === 'Read again');
  expect(confirm).toBeDefined(); confirm!.click(); await settle(el);
}

it('V6: when Read everything again completes, the folders are read again so the rows show the new file counts', async () => {
  const { el, host } = await library({ ...root, pathHash: 'reread-count' });
  el.resolvedPaths = { 'reread-count': '/Documents' };
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('8 files');
  let invoked = 0;
  const reads: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async () => { invoked++; return Response.json({ success: true, structuredData: { operationKey: '0194f72c-0000-7000-8000-000000000002' } }); }));
  host.data.fetch = vi.fn(async (path: string) => path.startsWith('/api/operation-history/') ? Response.json({ state: 'complete', historySince: 0 })
    : path.startsWith('/api/indexing-roots/substrate') ? (reads.push(path), Response.json({ items: [{ ...root, pathHash: 'reread-count', fileCount: 12 }] }))
    : new Response('{}', { status: 503 }));
  await reread(el);
  expect(invoked).toBe(1);
  expect(el.shadowRoot!.textContent).toContain('Everything has been read again.');
  expect(reads.some(path => path.includes('counts=true')), JSON.stringify(reads)).toBe(true);
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('12 files');
  vi.unstubAllGlobals();
});

it('V7: when the Engine cannot be reached, Read everything again says so in plain words; developer tools add the Engine message', async () => {
  const { el } = await library();
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
  await reread(el);
  const banner = must(el.shadowRoot, '.error-banner');
  expect(banner.textContent).toContain('could not reach the engine');
  expect(banner.textContent).not.toMatch(/index|core\./i);
  setShowDeveloperTools(true); await settle(el);
  await reread(el);
  expect(must(el.shadowRoot, '.error-banner').textContent).toContain('core.reindex');
  vi.unstubAllGlobals();
});

it('V7: maintenance actions the command palette shows without developer tools do not say index', () => {
  const catalog = readFileSync(resolve(process.cwd(), '../app-api/src/main/resources/messages/registry-operation.en.properties'), 'utf8');
  const labels = Object.fromEntries(catalog.split('\n').filter(line => /^[^#].*=/.test(line)).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  for (const key of ['ops.index-gc.label', 'ops.settle-index.label']) {
    expect(labels[key], key).toBeDefined();
    expect(labels[key]).not.toMatch(/index/i);
  }
});

it.each([
  ['failed', { failedStages: { embedding: true } }, 'Reading for meaning failed for some files'],
  ['missing', { missingArtifactStages: { embedding: true } }, 'Reading for meaning is incomplete for some files'],
])('R1: %s meaning work keeps a visible row status, unlike meaning work that only waits for AI', async (_name, facts, words) => {
  const { el } = await library();
  Object.assign(el, { enrichmentStages: { embedding: true, splade: false, ner: false }, enrichmentBlocked: false,
    roots: [{ ...root, ...facts }] });
  await settle(el);
  const status = must(el.shadowRoot, '.card-meta').textContent ?? '';
  expect(status).not.toBe('Up to date');
  expect(status).toContain(words);
  expect(status).toContain('Word search remains available');
  expect(status).not.toMatch(/enrichment|keyword|index/i);
  expect(must(el.shadowRoot, '.status-icon').classList.contains('unverified')).toBe(true);
});

it('R3: a first inventory read that fails says the folders could not be checked, not that there are none', async () => {
  const { el } = await library(root, observe);
  expect(must(el.shadowRoot, 'h2').textContent).toBe('Your files are searchable');
  document.body.replaceChildren();
  observe();
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async () => new Response('{}', { status: 503 }));
  const fresh = document.createElement('jf-library-surface') as LibrarySurface;
  fresh.host_ = host;
  await readIndexedRoots(async () => new Response('{}', { status: 503 }));
  document.body.append(fresh); await settle(fresh);
  expect(getIndexedRoots().confirmed).toBe(false);
  expect(fresh.roots).toHaveLength(0);
  expect(must(fresh.shadowRoot, 'h2').textContent).toBe('Reading status unconfirmed');
  expect(fresh.shadowRoot!.textContent).not.toMatch(/Add a folder to/);
  expect(fresh.shadowRoot!.textContent).not.toContain('Your files are searchable');
});

it('R3: after an Engine change, rows retained through a failed inventory read are not Up to date', async () => {
  await observeStatusSignal('r3-epoch-a', 1);
  const { el, host } = await library();
  expect(must(el.shadowRoot, '.card-meta').textContent).toBe('Up to date');
  await observeStatusSignal('r3-epoch-b', 1);
  host.data.fetch = vi.fn(async () => new Response('{}', { status: 503 }));
  await readIndexedRoots(async () => new Response('{}', { status: 503 }));
  observe(); await settle(el);
  expect(getIndexedRoots().confirmed).toBe(false);
  expect(getIndexedRoots().roots, 'the Engine change cleared the shared inventory').toHaveLength(0);
  expect(el.roots).toHaveLength(1);
  expect(must(el.shadowRoot, 'h2').textContent).toBe('Reading status unconfirmed');
  expect(el.shadowRoot!.textContent).not.toContain('Up to date');
});

async function browseNested(roots: Root[]) {
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async (path: string) => path.includes('/api/indexing/roots') ? Response.json({ roots })
    : path.endsWith('/folders') ? Response.json({ folders: [{ path: '/Documents/Nested', name: 'Nested', fileCount: 8 }] })
    : Response.json({ files: [] }));
  const el = document.createElement('jf-browse-surface') as BrowseSurface;
  el.host_ = host; document.body.append(el); await settle(el);
  return el;
}

it('R4: every occurrence of a watched folder shows the shared count with its cap, nested ones included', async () => {
  const el = await browseNested([{ path: '/Documents', fileCount: 100, fileCountCapped: false },
    { path: '/Documents/Nested', fileCount: 50000, fileCountCapped: true }]);
  must<HTMLElement>(el.shadowRoot, '[data-path="/Documents"]').click(); await settle(el);
  const copies = [...el.shadowRoot!.querySelectorAll<HTMLElement>('[data-path="/Documents/Nested"]')];
  expect(copies).toHaveLength(2);
  expect(copies.map(row => must(row, '.meta').textContent)).toEqual(['50,000+ files', '50,000+ files']);
});

it('R5: turning developer tools off clears the filter, and no filter applies while they are off', async () => {
  setShowDeveloperTools(true);
  const { el } = await browse();
  const input = must<HTMLInputElement>(el.shadowRoot, '.filter-input');
  input.value = 'zzz'; input.dispatchEvent(new Event('input')); await settle(el);
  expect(el.shadowRoot!.querySelector('[data-path="/Documents"]')).toBeNull();
  setShowDeveloperTools(false); await settle(el);
  expect(el.filter).toBe('');
  expect(el.shadowRoot!.querySelector('[data-path="/Documents"]')).not.toBeNull();
  el.filter = 'zzz'; await settle(el);
  expect(el.shadowRoot!.querySelector('[data-path="/Documents"]')).not.toBeNull();
  expect(el.shadowRoot!.textContent).not.toContain('No matching');
});

it('R7: top-level Browse folders are listed by name like their children', async () => {
  const { el } = await browse([{ path: '/b/Zeta' }, { path: '/a/alpha' }, { path: '/c/Beta' }]);
  expect([...el.shadowRoot!.querySelectorAll<HTMLElement>('[role="treeitem"][aria-level="1"]')].map(row => row.dataset.path))
    .toEqual(['/a/alpha', '/c/Beta', '/b/Zeta']);
});

it('P1: the open row has no Read time; it says Reading now only while reading and keeps Checked for changes', async () => {
  const { el, host } = await library({ ...root, lastIndexedIsoTime: '2026-01-01T00:00:00Z', lastVerifiedIsoTime: '2026-01-02T00:00:00Z' });
  host.utilities.formatRelativeTime = (iso: string) => iso ? `at ${iso}` : '';
  el.requestUpdate(); await settle(el);
  must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  const facts = () => [...el.shadowRoot!.querySelectorAll('.folder-details dl > div')]
    .map(row => [row.querySelector('dt')?.textContent ?? '', row.querySelector('dd')?.textContent ?? '']);
  const details = () => must(el.shadowRoot, '.folder-details').textContent ?? '';
  expect(facts().find(([term]) => term === 'Checked for changes')).toEqual(['Checked for changes', 'at 2026-01-02T00:00:00Z']);
  expect(facts().find(([term]) => term === 'Read'), 'no Read line when nothing is being read').toBeUndefined();
  expect(details()).not.toContain('2026-01-01');
  expect(details()).not.toContain('Not yet confirmed');
  expect(details()).not.toContain('Reading now');
  el.roots = [{ ...el.roots[0]!, inFlightCount: 2 }]; await settle(el);
  expect(details()).toContain('Reading now');
  expect(details()).not.toContain('2026-01-01');
  expect(facts().find(([term]) => term === 'Checked for changes')).toEqual(['Checked for changes', 'at 2026-01-02T00:00:00Z']);
});

it.each(['blocked', 'deferred', 'paused'] as const)('P3: %s meaning work does not claim Reading now', async state => {
  const { el } = await library({ ...root, parentDocsTotalEmbedding: 8, parentDocsSettledEmbedding: 4 }, () => {
    __feedForTest({ status: {
      operating: { indexing: { state: state === 'paused' ? 'PAUSED' : 'RUNNING',
        reasons: state === 'paused' ? ['user_paused'] : state === 'deferred' ? ['energy_saver'] : [] } },
      worker: {
        core: { indexedDocuments: 8, ownSearchableDocuments: 8, pendingJobs: 0, indexState: 'IDLE', indexHealthy: true },
        migration: { migrationState: 'IDLE' },
        enrichment: { embeddingEnabled: true, embeddingAvailable: state !== 'blocked',
          embeddingDocCount: 8, embeddingPendingCount: 4, spladeEnabled: false, nerEnabled: false },
      },
    } as unknown as StatusSnapshot });
  });
  expect(el.indexingActivity.confirmed).toBe(true);
  expect(el.enrichmentBlocked).toBe(state === 'blocked');
  expect(el.indexingActivity.enrichmentDeferred).toBe(true);
  if (state === 'blocked') expect(must(el.shadowRoot, '.card-meta').textContent).toBe('Up to date');
  must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  const details = must(el.shadowRoot, '.folder-details');
  const meaning = [...details.querySelectorAll('dl > div')].find(row => row.querySelector('dt')?.textContent === 'Search by meaning');
  expect(meaning).toBeDefined();
  expect(must(meaning!, 'dd').textContent).toBe(`${state === 'blocked' ? 'Blocked' : 'In progress'} · word search is available`);
  expect(details.textContent).not.toContain('Reading now');
});

it('P3: file jobs in flight still show Reading now', async () => {
  const { el } = await library({ ...root, inFlightCount: 2 });
  expect(el.indexingActivity.confirmed).toBe(true);
  must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  expect(must(el.shadowRoot, '.folder-details').textContent).toContain('Reading now');
});

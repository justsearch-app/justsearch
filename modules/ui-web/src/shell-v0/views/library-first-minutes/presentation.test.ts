// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import '../LibrarySurface.js';
import '../BrowseSurface.js';
import '../../chrome/TopBar.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import type { BrowseSurface } from '../BrowseSurface.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../../state/aiStateStore.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import { projectResultView } from '../searchResultViewModel.js';
import { renderFolderRow } from '../../components/folderRow.js';
import { render } from 'lit';

function must<T extends Element = Element>(root: ParentNode | null, selector: string): T {
  expect(root, `root for ${selector}`).not.toBeNull();
  const found = root!.querySelector<T>(selector);
  expect(found, selector).not.toBeNull();
  return found!;
}

async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
function observe() {
  __feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8,
    indexState: 'IDLE', indexHealthy: true }, migration: { migrationState: 'IDLE' } } } as unknown as StatusSnapshot });
}
async function library() {
  observe(); setShowDeveloperTools(false);
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async () => new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host; document.body.append(el); await settle(el);
  Object.assign(el, { loading: false, rootsObserved: true, forgetReadStatus: 'ready', roots: [{ pathHash: 'a',
    status: 'indexed', fileCount: 8, failedCount: 0, inFlightCount: 0, walkCompleted: true }], resolvedPaths: { a: '/Documents' } });
  await settle(el); expect(el.shadowRoot).not.toBeNull(); return el;
}
async function browse(failure = false) {
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async path => path.includes('/api/indexing/roots')
    ? Response.json({ roots: [{ path: '/Documents', fileCount: 50000, fileCountCapped: true }] })
    : failure ? new Response('{}', { status: 503 })
    : path.includes('folder-files') ? Response.json({ files: [{ docId: '/Documents/a.md', fields: {
      path: '/Documents/a.md', filename: 'a.md', file_kind: 'markdown', size_bytes: '90', modified_at: '946684800000' } }], totalCount: 1001 })
    : Response.json({ folders: [{ path: '/Documents/Nested' }] }));
  const el = document.createElement('jf-browse-surface') as BrowseSurface;
  el.host_ = host; document.body.append(el); await settle(el);
  expect(el.shadowRoot).not.toBeNull(); return { el, host };
}
function key(el: Element, value: string, shiftKey = false) {
  el.dispatchEvent(new KeyboardEvent('keydown', { key: value, shiftKey, bubbles: true, composed: true }));
}
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); setShowDeveloperTools(false); vi.restoreAllMocks(); });

it('K2/K3: one state headline, no count subtitle, Add a folder, healthy rows have one scoped count', async () => {
  const el = await library();
  expect(must(el.shadowRoot, 'h2').textContent).toBe('Your files are searchable');
  expect(el.shadowRoot!.querySelector('.header .subtitle')).toBeNull();
  expect(must(el.shadowRoot, '[data-add-folder]').getAttribute('label')).toBe('Add a folder');
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('8 files');
});
it('K2/M3/M4: expanded facts retain uncertainty, active reading and deletion recovery without expert facts', async () => {
  const el = await library();
  el.roots = [{ ...el.roots[0]!, inFlightCount: 2, deleteDetectionUnverified: true, lastIndexedIsoTime: '2026-01-01T00:00:00Z' }];
  await settle(el); must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  const text = must(el.shadowRoot, '.folder-details').textContent!;
  expect(text).toContain('Search by meaning'); expect(text).toContain("Couldn't be read");
  expect(text).toContain('Reading now'); expect(text).not.toContain('Collection'); expect(text).not.toContain('Searchable by words');
  expect(text).not.toContain('Checked for changes');
  expect(el.shadowRoot!.textContent).toMatch(/verify deletions|[Dd]eletions unverified/);
});
it('M5: capped and unknown Library counts keep their scope', async () => {
  const el = await library();
  el.roots = [{ ...el.roots[0]!, fileCount: 50000, ...{ fileCountCapped: true } }]; await settle(el);
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('50,000+ files');
  el.roots = [{ ...el.roots[0]!, fileCount: -1 }]; await settle(el);
  expect(must(el.shadowRoot, '.files-found').textContent).toBe('');
});
it('M3: no current observation of zero failures says none reported', () => {
  const container = document.createElement('div');
  render(renderFolderRow({ failed: 0 }, { expanded: true, enabled: true, toggle() {}, invoke() {} }), container);
  expect(must(container, '.folder-details').textContent).toContain('None reported');
});
it('K4/M2: shared Library menu says what runs; empty and uncertain waiting differ', async () => {
  const el = await library();
  const trigger = must<HTMLButtonElement>(el.shadowRoot, '[aria-label="More Library actions"]');
  trigger.click(); await settle(el);
  const menu = must(el.shadowRoot, 'jf-menu');
  if (menu) await settle(menu as unknown as { updateComplete: Promise<unknown> });
  expect(menu!.textContent).toContain('Read everything again'); expect(menu!.textContent).toContain('Read waiting files now');
  const waiting = () => {
    const item = [...menu.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')].find(item => item.textContent?.includes('Read waiting files now'));
    expect(item, 'Read waiting files now menu item').toBeDefined();
    return item!;
  };
  expect(waiting().disabled).toBe(false); expect(menu!.textContent).not.toContain('Nothing is waiting');
  expect(menu!.textContent).not.toContain('All 8 files');
  const snapshot = { worker: { core: { pendingVduCount: 0, indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true },
    enrichment: { embeddingPendingCount: 0, spladePendingCount: 0, pendingNerCount: 0, chunk: { chunkEmbeddingPendingCount: 0, chunkSpladePendingCount: 0 } },
    migration: { migrationState: 'IDLE' } } } as unknown as StatusSnapshot;
  __feedForTest({ status: snapshot }); await settle(el); await settle(menu as unknown as { updateComplete: Promise<unknown> });
  expect(waiting().disabled).toBe(true); expect(menu!.textContent).toContain('Nothing is waiting');
  __feedForTest({ status: { ...snapshot, meta: { workerRpcStale: true } } as StatusSnapshot });
  await settle(el); await settle(menu as unknown as { updateComplete: Promise<unknown> });
  expect(waiting().disabled).toBe(false); expect(menu!.textContent).not.toContain('Nothing is waiting');
  key(menu!, 'Escape'); await settle(el); expect(el.shadowRoot!.querySelector('jf-menu')).toBeNull();
});
it('K5/M1/M7/M11: expert history and activity are opt-in, unavailable removals and other sources stay reachable', async () => {
  const el = await library();
  expect(el.shadowRoot!.querySelector('.removal-history')).toBeNull();
  expect(el.shadowRoot!.querySelector('jf-ingestion-summary')).toBeNull();
  expect(el.shadowRoot!.querySelector('[label="Activity"]')).not.toBeNull();
  expect(el.shadowRoot!.querySelector('[label="Save patterns"]')).not.toBeNull();
  Object.assign(el, { forgetReadStatus: 'unavailable', roots: [], otherSources: { sources: [{ collection: 'notes', docCount: 3 }], truncated: false } });
  await settle(el);
  expect(el.shadowRoot!.querySelector('[data-testid="unfinished-forgets"]')).not.toBeNull();
  expect(el.shadowRoot!.textContent).toContain('3 documents from other apps');
  expect(must(el.shadowRoot, 'h2').textContent).not.toContain('Add a folder');
});
it('K6/M5: Browse column heads, stable root count, byte and shared date presentation, no inline styles', async () => {
  const { el } = await browse(); must<HTMLElement>(el.shadowRoot, '[role="treeitem"]').click(); await settle(el);
  expect(must(el.shadowRoot, '.column-heads').textContent).toMatch(/Name\s*Size\s*Changed/);
  expect(el.shadowRoot!.textContent).toContain('50,000+ files'); expect(el.shadowRoot!.textContent).toContain('90 bytes');
  expect(el.shadowRoot!.textContent).toContain('2000'); expect(el.shadowRoot!.querySelector('[style]')).toBeNull();
  expect(el.shadowRoot!.querySelector('[aria-label="Open file"]')).not.toBeNull();
});
it('M8: failed Browse children provide Retry and never say empty', async () => {
  const { el } = await browse(true); must<HTMLElement>(el.shadowRoot, '[role="treeitem"]').click(); await settle(el);
  expect(el.shadowRoot!.textContent).toContain('Could not load'); expect(el.shadowRoot!.textContent).toContain('Retry');
  expect(el.shadowRoot!.textContent).not.toContain('Folder is empty');
});
it('M8: truncated, filtered-empty and confirmed-empty are distinct', async () => {
  const { el } = await browse(); must<HTMLElement>(el.shadowRoot, '[role="treeitem"]').click(); await settle(el);
  expect(el.shadowRoot!.textContent).toContain('Showing first');
  el.filter = 'no-such-file'; await settle(el); expect(el.shadowRoot!.textContent).toContain('No matching');
  el.filter = ''; el.folderChildren = { '/Documents': [] }; el.fileChildren = { '/Documents': [] };
  el.partial = { '/Documents': false }; await settle(el);
  expect(el.shadowRoot!.textContent).toContain('No searchable files in this folder');
  expect(el.shadowRoot!.textContent).not.toContain('Showing first');
});
it('M9: one tree tab stop, full hierarchy, Right to child, Left to parent, focus after collapse', async () => {
  const { el } = await browse(); const root = must<HTMLElement>(el.shadowRoot, '[role="treeitem"]');
  root.focus(); key(root, 'ArrowRight'); await settle(el); key(root, 'ArrowRight'); await settle(el);
  const child = el.shadowRoot!.activeElement as HTMLElement;
  expect(child).not.toBeNull(); expect(child).not.toBe(root); expect(child.getAttribute('aria-level')).toBe('2');
  expect(child.getAttribute('aria-posinset')).toBe('1'); expect(child.getAttribute('aria-setsize')).toBe('2');
  key(child, 'ArrowLeft'); await settle(el); expect(el.shadowRoot!.activeElement).toBe(root);
  key(root, 'ArrowLeft'); await settle(el);
  expect(el.shadowRoot!.querySelectorAll('[role="treeitem"][tabindex="0"]')).toHaveLength(1);
});
it('M10: eight file kind glyphs are distinct and shared with search results', () => {
  const icons = ['pdf', 'document', 'markdown', 'spreadsheet', 'mail', 'image', 'code', undefined].map(kind =>
    projectResultView({ kind, path: '/a', title: 'a' }).icon);
  expect(new Set(icons).size).toBe(8);
});

it('M6/M12: hidden weekday restrictions stay effective across developer visibility changes', async () => {
  const el = await library();
  const editor = must(el.shadowRoot, 'jf-indexing-rules-editor') as HTMLElement & {
    indexingRules: { enabled: boolean; days: number[]; start: string; end: string; pauseOnBattery: boolean };
    updateComplete: Promise<unknown>;
  };
  editor.indexingRules = { enabled: true, days: [1, 3], start: '22:00', end: '07:00', pauseOnBattery: false };
  await settle(editor);
  expect(must<HTMLElement>(editor.shadowRoot, '.indexing-days').hidden).toBe(true);
  expect(must(editor.shadowRoot, '.effective-days').textContent).toBe('Mon, Wed');
  setShowDeveloperTools(true); await settle(el); await settle(editor);
  expect(must<HTMLElement>(editor.shadowRoot, '.indexing-days').hidden).toBe(false);
  setShowDeveloperTools(false); await settle(el); await settle(editor);
  expect(editor.indexingRules.days).toEqual([1, 3]);
  expect(editor.indexingRules.start).toBe('22:00'); expect(editor.indexingRules.end).toBe('07:00');
});

it('K4/M10: opening the Library menu closes the bar menu, and outside clicks inside Library dismiss', async () => {
  const el = await library();
  const bar = document.createElement('jf-top-bar') as HTMLElement & { updateComplete: Promise<unknown> };
  document.body.append(bar); await settle(bar);
  must<HTMLButtonElement>(bar.shadowRoot, '.more-trigger').click(); await settle(bar);
  expect(bar.shadowRoot!.querySelector('[role="menu"]')).not.toBeNull();
  must<HTMLButtonElement>(el.shadowRoot, '.more-trigger').click(); await settle(el); await settle(bar);
  expect(bar.shadowRoot!.querySelector('[role="menu"]')).toBeNull();
  must(el.shadowRoot, '.header').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
  await settle(el); expect(el.shadowRoot!.querySelector('[role="menu"]')).toBeNull();
});

it('M9: file action Enter cannot open the row, and disappearing focused descendants recover to their parent', async () => {
  const { el } = await browse();
  const root = must<HTMLElement>(el.shadowRoot, '[role="treeitem"]');
  root.click(); await settle(el);
  const file = must<HTMLElement>(el.shadowRoot, '.row.file');
  file.focus(); await settle(el);
  const open = vi.spyOn(el as unknown as { openFile(path: string): Promise<void> }, 'openFile').mockResolvedValue(undefined);
  const action = must<HTMLButtonElement>(file, '[aria-label="Open file"]');
  key(action, 'Enter'); expect(open).not.toHaveBeenCalled();
  action.click(); expect(open).toHaveBeenCalledTimes(1);
  file.focus(); el.expanded = {}; await settle(el);
  expect(el.shadowRoot!.activeElement).toBe(root);
});


it('K4: a hidden Library closes its transient menu before it is shown again', async () => {
  const el = await library();
  must<HTMLButtonElement>(el.shadowRoot, '.more-trigger').click(); await settle(el);
  expect(el.shadowRoot!.querySelector('jf-menu')).not.toBeNull();
  el.remove(); document.body.append(el); await settle(el);
  expect(el.shadowRoot!.querySelector('jf-menu')).toBeNull();
});

it('M4: paused meaning work keeps the last read time and never claims Reading now', async () => {
  const el = await library();
  Object.assign(el, { enrichmentStages: { embedding: true, splade: false, ner: false },
    indexProgress: { ...(el as unknown as { indexProgress: object }).indexProgress, pause: { state: 'paused', reason: 'user_paused', reasons: ['user_paused'] } },
    roots: [{ ...el.roots[0]!, lastIndexedIsoTime: '2026-01-01T00:00:00Z', parentDocsTotalEmbedding: 8, parentDocsSettledEmbedding: 4 }] });
  await settle(el); must<HTMLButtonElement>(el.shadowRoot, '.folder-toggle').click(); await settle(el);
  const read = [...el.shadowRoot!.querySelectorAll('.folder-details dl > div')].find(row => row.querySelector('dt')?.textContent === 'Read');
  expect(read).toBeDefined(); expect(must(read!, 'dd').textContent).not.toBe('Reading now');
});

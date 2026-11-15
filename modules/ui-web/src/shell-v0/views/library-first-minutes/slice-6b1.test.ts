// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import '../LibrarySurface.js';
import '../BrowseSurface.js';
import '../../components/IndexingControls.js';
import '../../chrome/TopBar.js';
import type { LibrarySurface } from '../LibrarySurface.js';
import type { BrowseSurface } from '../BrowseSurface.js';
import { createMockHostApi } from '../../plugin-api/testHostApi.js';
import { getOperationClient } from '../../operations/OperationClient.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../../state/aiStateStore.js';
import { selectIndexingProgress } from '../../state/indexingProgress.js';
import type { StatusResponse } from '../../../api/generated/schema-types/index.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';

function must<T extends Element = HTMLElement>(root: ParentNode | null, selector: string): T {
  expect(root, `root for ${selector}`).not.toBeNull();
  const found = root!.querySelector<T>(selector);
  expect(found, selector).not.toBeNull(); return found!;
}
async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 40; i++) { await Promise.resolve(); await el.updateComplete; }
}
function button(root: ParentNode, words: string): HTMLButtonElement {
  const found = [...root.querySelectorAll<HTMLButtonElement>('button')].find(el => el.textContent?.trim() === words);
  expect(found, words).toBeDefined(); return found!;
}
const row = { pathHash: 'slice6b1', status: 'indexed', fileCount: 8, fileCountCapped: false,
  failedCount: 0, inFlightCount: 0, walkCompleted: true };
async function library(items: unknown[] = [row]) {
  __feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8,
    indexState: 'IDLE', indexHealthy: true }, migration: { migrationState: 'IDLE' },
    enrichment: { embeddingEnabled: false, spladeEnabled: false, nerEnabled: false,
      embeddingDocCount: 8, embeddingPendingCount: 8, embeddingCoveragePercent: 0,
      spladeDocCount: 8, spladePendingCount: 8, spladeCoveragePercent: 0, pendingNerCount: 8,
      completedNerCount: 0, chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2, chunkVectorsReady: false } } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async path => path.startsWith('/api/indexing-roots/substrate') ? Response.json({ items })
    : path.startsWith('/api/operation-history/') ? Response.json({ state: 'complete', historySince: 0 }) : new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host; el.resolvedPaths = { slice6b1: '/Documents' };
  document.body.append(el); await settle(el); return el;
}
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); setShowDeveloperTools(false); vi.restoreAllMocks(); });

it('6b1-1: Library removes its more button and menu', async () => {
  const el = await library();
  expect(el.shadowRoot).not.toBeNull();
  expect(el.shadowRoot!.querySelector('[aria-label="More Library actions"]')).toBeNull();
  expect(el.shadowRoot!.querySelector('jf-menu')).toBeNull();
});
it('6b1-7: Library has no misleading waiting-work action', async () => {
  const el = await library();
  const more = el.shadowRoot?.querySelector<HTMLButtonElement>('[aria-label="More Library actions"]');
  if (more) { more.click(); await settle(el); }
  expect(el.shadowRoot).not.toBeNull();
  expect(el.shadowRoot!.textContent).not.toContain('Read waiting files now');
});
it('6b1-2: the quiet foot action asks in place, and Cancel runs nothing and restores focus', async () => {
  const invoke = vi.spyOn(getOperationClient(''), 'invokeWithConsent');
  const el = await library();
  const foot = must(el.shadowRoot, '.bottom-links');
  const action = button(foot, 'Read everything again');
  expect(must(foot, '[label="Activity"]').textContent).toBe('Activity');
  expect(action.getAttribute('tabindex')).not.toBe('-1');
  expect(action.disabled).toBe(false);
  expect(action.getAttribute('aria-expanded')).toBe('false');
  action.focus(); expect(el.shadowRoot!.activeElement).toBe(action);
  action.click(); await settle(el);
  const ask = must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]');
  expect(action.getAttribute('aria-expanded')).toBe('true');
  expect(ask.closest('.bottom-links')).toBe(foot);
  expect(ask.textContent).toContain('Read every watched folder again?');
  expect(el.shadowRoot!.querySelector('[role="dialog"], dialog')).toBeNull();
  expect(ask.parentElement?.getAttribute('aria-live')).toBe('polite');
  const cancel = button(ask, 'Cancel');
  button(ask, 'Read again'); cancel.focus(); cancel.click(); await settle(el);
  expect(el.shadowRoot!.querySelector('[role="region"][aria-label="Read every watched folder again?"]')).toBeNull();
  expect(el.shadowRoot!.activeElement).toBe(action);
  expect(action.getAttribute('aria-expanded')).toBe('false');
  expect(invoke).not.toHaveBeenCalled();
  expect(el.shadowRoot!.querySelector('[style]')).toBeNull();
});
/** Every element under root, through every open shadow root (a menu renders its items in its own). */
function deepElements(root: ParentNode): Element[] {
  return [...root.querySelectorAll('*')].flatMap(el => [el, ...(el.shadowRoot ? deepElements(el.shadowRoot) : [])]);
}
it('6b1-8: with no watched folders Read everything again is not on the page, in a menu or not', async () => {
  const el = await library([]);
  expect(el.shadowRoot).not.toBeNull();
  // The before tree kept a disabled item inside its menu: open any such menu so the item cannot hide there.
  const more = el.shadowRoot!.querySelector<HTMLButtonElement>('[aria-label="More Library actions"]');
  if (more) { more.click(); await settle(el); for (const menu of deepElements(el.shadowRoot!).filter(e => e.localName === 'jf-menu')) await settle(menu as unknown as LibrarySurface); }
  const everything = deepElements(el.shadowRoot!);
  expect(everything.length).toBeGreaterThan(0);
  expect(everything.filter(e => e.textContent?.includes('Read everything again'))).toEqual([]);
  expect(everything.filter(e => e.matches('[data-reread], #reread-confirmation'))).toEqual([]);
});
it('6b1-8: the ask closes when the last watched folder leaves, and the action stays disabled while a read-again request is in flight', async () => {
  let accept!: (value: unknown) => void;
  vi.spyOn(getOperationClient(''), 'invokeWithConsent').mockReturnValue(new Promise(resolve => { accept = resolve; }) as never);
  const el = await library();
  button(must(el.shadowRoot, '.bottom-links'), 'Read everything again').click(); await settle(el);
  must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]');
  el.roots = []; await settle(el);
  expect(el.shadowRoot!.querySelector('[data-reread], [role="region"][aria-label="Read every watched folder again?"]')).toBeNull();
  el.roots = [row]; await settle(el);
  const action = button(must(el.shadowRoot, '.bottom-links'), 'Read everything again');
  expect(action.getAttribute('aria-expanded')).toBe('false');
  action.click(); await settle(el);
  const confirm = button(must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]'), 'Read again');
  confirm.focus(); confirm.click(); await settle(el);
  const busy = button(must(el.shadowRoot, '.bottom-links'), 'Read everything again');
  // Blocked, not disabled: a disabled button would drop the focus the confirmation just gave it.
  expect(busy.getAttribute('aria-disabled')).toBe('true');
  expect(busy.disabled).toBe(false);
  expect(el.shadowRoot!.activeElement).toBe(busy);
  busy.click(); await settle(el);
  expect(el.shadowRoot!.querySelector('[role="region"][aria-label="Read every watched folder again?"]')).toBeNull();
  expect(busy.getAttribute('aria-expanded')).toBe('false');
  accept({ success: true, message: 'Reading accepted', structuredData: { operationKey: '0194f72c-0000-7000-8000-000000000068' } }); await settle(el);
  expect(busy.getAttribute('aria-disabled')).toBe('false');
  expect(el.shadowRoot!.activeElement).toBe(busy);
});
it.each(['Read again', 'Cancel'])('6b1-2: Escape on %s cancels the ask, runs nothing and returns focus to the action', async answer => {
  const invoke = vi.spyOn(getOperationClient(''), 'invokeWithConsent');
  const outside = vi.fn();
  document.addEventListener('keydown', outside);
  try {
    const el = await library();
    const action = button(must(el.shadowRoot, '.bottom-links'), 'Read everything again');
    action.click(); await settle(el);
    const target = button(must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]'), answer);
    target.focus(); expect(el.shadowRoot!.activeElement).toBe(target);
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true });
    target.dispatchEvent(escape); await settle(el);
    expect(el.shadowRoot!.querySelector('[role="region"][aria-label="Read every watched folder again?"]')).toBeNull();
    expect(action.getAttribute('aria-expanded')).toBe('false');
    expect(el.shadowRoot!.activeElement).toBe(action);
    expect(escape.defaultPrevented).toBe(true);
    expect(outside).not.toHaveBeenCalled();
    expect(invoke).not.toHaveBeenCalled();
  } finally { document.removeEventListener('keydown', outside); }
});
it('6b1-2: Escape outside the ask leaves it open', async () => {
  const el = await library();
  const foot = must(el.shadowRoot, '.bottom-links');
  button(foot, 'Read everything again').click(); await settle(el);
  must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]');
  const activity = must(foot, '[label="Activity"]');
  activity.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true })); await settle(el);
  must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]');
});
it('6b1-2: confirmation uses consent and recorded outcome', async () => {
  const invoke = vi.spyOn(getOperationClient(''), 'invokeWithConsent').mockResolvedValue({ success: true, message: 'Reading accepted',
    structuredData: { operationKey: '0194f72c-0000-7000-8000-000000000062' } });
  const el = await library();
  button(must(el.shadowRoot, '.bottom-links'), 'Read everything again').click(); await settle(el);
  expect(invoke).not.toHaveBeenCalled();
  const ask = must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]');
  button(ask, 'Read again').click(); await settle(el);
  expect(invoke).toHaveBeenCalledTimes(1);
  expect(invoke).toHaveBeenCalledWith('core.reindex', { args: { force: true } }, { requestConsent: expect.any(Function) });
  expect(el.shadowRoot!.textContent).toContain('Read again completed');
});
it('6b1-4: after no-AI completion Up to date occurs once on the page', async () => {
  vi.spyOn(getOperationClient(''), 'invokeWithConsent').mockResolvedValue({ success: true, message: 'Reading accepted',
    structuredData: { operationKey: '0194f72c-0000-7000-8000-000000000064' } });
  const el = await library();
  const more = el.shadowRoot?.querySelector<HTMLButtonElement>('[aria-label="More Library actions"]');
  if (more) { // The before tree reaches the same operation through its old menu.
    more.click(); await settle(el);
    const item = [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')]
      .find(el => el.textContent?.includes('Read everything again'));
    expect(item).toBeDefined(); item!.click();
  } else {
    button(must(el.shadowRoot, '.bottom-links'), 'Read everything again').click(); await settle(el);
    button(must(el.shadowRoot, '[role="region"][aria-label="Read every watched folder again?"]'), 'Read again').click();
  }
  await settle(el);
  expect(el.shadowRoot!.textContent).toContain('Read again completed');
  expect(el.shadowRoot!.textContent!.match(/Up to date/g)).toHaveLength(1);
});
it.each([[1, '1 document from other apps'], [0, '0 documents from other apps'], [12, '12 documents from other apps']] as const)(
  '6b1-3: other-app total %s has literal singular/plural words', async (count, words) => {
    const el = await library();
    el.otherSources = { sources: [{ collection: 'app', docCount: count }], truncated: false };
    await settle(el); expect(must(el.shadowRoot, '.bottom-links details summary').textContent?.trim()).toBe(words);
  });

async function browse(fetch: (path: string) => Promise<Response>) {
  const host = createMockHostApi(); host.data.fetch = fetch;
  const el = document.createElement('jf-browse-surface') as BrowseSurface;
  el.host_ = host; document.body.append(el); await settle(el); return el;
}
function inList(el: BrowseSurface, selector: string) {
  const heads = must(el.shadowRoot, '.column-heads');
  const notice = must(el.shadowRoot, selector);
  expect(notice.closest('.body')).toBe(heads.parentElement);
  expect(heads.compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy(); return notice;
}
it.each(['outside click', 'Escape'])('6b1-1 preservation: top bar and Browse context menus still close on %s', async dismiss => {
  const close = () => dismiss === 'Escape'
    ? document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    : document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
  const bar = document.createElement('jf-top-bar') as HTMLElement & { updateComplete: Promise<unknown> };
  document.body.append(bar); await settle(bar);
  must<HTMLButtonElement>(bar.shadowRoot, '.more-trigger').click(); await settle(bar);
  must(bar.shadowRoot, '[role="menu"]'); close(); await settle(bar);
  expect(bar.shadowRoot!.querySelector('[role="menu"]')).toBeNull();

  const el = await browse(vi.fn(async (path: string) => Response.json(path.includes('/api/indexing/roots')
    ? { roots: [{ path: '/Documents' }] } : path.endsWith('/folders') ? { folders: [] }
      : { files: [{ docId: '/Documents/one.txt', fields: { path: '/Documents/one.txt', filename: 'one.txt' } }], totalCount: 1 })));
  must<HTMLElement>(el.shadowRoot, '[role="treeitem"]').click(); await settle(el);
  must(el.shadowRoot, '.row.file').dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, composed: true }));
  await settle(el);
  const context = must<HTMLElement & { updateComplete: Promise<unknown> }>(document.body, 'jf-context-menu');
  await settle(context);
  const shared = must<HTMLElement & { updateComplete: Promise<unknown> }>(context.shadowRoot, 'jf-menu');
  await settle(shared); expect(must(context.shadowRoot, '[role="menu"]').getAttribute('aria-label')).toBe('File actions');
  close(); await settle(el); expect(document.querySelector('jf-context-menu')).toBeNull();
});
it('6b1-5: root loading and failure occupy the list area, hide browser errors, and Retry loading folders reloads', async () => {
  let reject!: (error: Error) => void;
  const fetch = vi.fn(() => new Promise<Response>((_resolve, fail) => { reject = fail; }));
  const el = await browse(fetch);
  expect(inList(el, '[role="status"]').textContent).toContain('Loading folders…');
  reject(new TypeError('Load failed')); await settle(el);
  const failure = inList(el, '.browse-error');
  expect(failure.textContent).toContain("Couldn't load folders");
  expect(el.shadowRoot!.textContent).not.toContain('Load failed');
  const retry = must<HTMLElement & { onActivate(): void }>(failure, 'jf-button[label="Retry loading folders"]');
  expect(retry.textContent?.trim()).toBe('Retry loading folders');
  fetch.mockResolvedValue(Response.json({ roots: [{ path: '/Documents' }] }));
  retry.onActivate(); await settle(el);
  expect(must(el.shadowRoot, '[role="treeitem"]').textContent).toContain('Documents');
});
it('6b1-5: folder loading/failure sit after heads, with plain failure and a working Retry that names the folder', async () => {
  let reject!: (error: Error) => void;
  const fetch = vi.fn((path: string) => path.includes('/api/indexing/roots') ? Promise.resolve(Response.json({ roots: [{ path: '/Documents' }] }))
    : path.endsWith('/folders') ? Promise.resolve(Response.json({ folders: [] }))
      : new Promise<Response>((_resolve, fail) => { reject = fail; }));
  const el = await browse(fetch);
  must<HTMLElement>(el.shadowRoot, '[role="treeitem"]').click(); await settle(el);
  expect(inList(el, '.notice').textContent).toContain('Loading Documents…');
  reject(new TypeError('Load failed')); await settle(el);
  const failure = inList(el, '.notice');
  expect(failure.textContent).toContain("Couldn't load this folder");
  expect(el.shadowRoot!.textContent).not.toContain('Load failed');
  const retry = must<HTMLElement & { onActivate(): void }>(failure, 'jf-button[label="Retry Documents"]');
  expect(retry.textContent?.trim()).toBe('Retry Documents');
  fetch.mockImplementation(async (path: string) => Response.json(path.endsWith('/folders') ? { folders: [] } : { files: [], totalCount: 0 }));
  retry.onActivate(); await settle(el);
  expect(inList(el, '.notice').textContent).toContain('No searchable files in this folder');
});
it('6b1-5: several folder failures each name their folder, in the line and in its Retry', async () => {
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/api/indexing/roots')) return Response.json({ roots: [{ path: '/Documents' }, { path: '/Photos' }] });
    throw new TypeError('Load failed');
  });
  const el = await browse(fetch);
  for (const item of [...el.shadowRoot!.querySelectorAll<HTMLElement>('[role="treeitem"]')]) { item.click(); await settle(el); }
  const failures = [...el.shadowRoot!.querySelectorAll('.notice')].filter(n => n.textContent?.includes("Couldn't load this folder"));
  expect(failures.map(n => n.textContent?.replace(/Retry \S+/, '').trim())).toEqual(
    ["Documents: Couldn't load this folder", "Photos: Couldn't load this folder"]);
  expect(failures.map(n => must(n, 'jf-button').getAttribute('label'))).toEqual(['Retry Documents', 'Retry Photos']);
  expect(failures.map(n => must(n, 'jf-button').textContent?.trim())).toEqual(['Retry Documents', 'Retry Photos']);
});
it('6b1-5: two failed folders of one name show enough of their paths to tell them apart', async () => {
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/api/indexing/roots')) return Response.json({ roots: [{ path: '/home/a/Documents' }, { path: '/home/b/Documents' }, { path: '/home/c/Photos' }] });
    throw new TypeError('Load failed');
  });
  const el = await browse(fetch);
  for (const item of [...el.shadowRoot!.querySelectorAll<HTMLElement>('[role="treeitem"]')]) { item.click(); await settle(el); }
  const failures = [...el.shadowRoot!.querySelectorAll('.notice')].filter(n => n.textContent?.includes("Couldn't load this folder"));
  expect(failures.map(n => must(n, 'jf-button').getAttribute('label'))).toEqual(['Retry a/Documents', 'Retry b/Documents', 'Retry Photos']);
  expect(failures.map(n => n.textContent?.replace(/Retry \S+/, '').trim())).toEqual(
    ["a/Documents: Couldn't load this folder", "b/Documents: Couldn't load this folder", "Photos: Couldn't load this folder"]);
});
it('6b1-6: focused Resume becomes enabled Pause when observation confirms reading runs', async () => {
  vi.spyOn(getOperationClient(''), 'invokeWithConsent').mockResolvedValue({ success: true, message: 'Resume accepted' });
  const progress = (paused: boolean) => selectIndexingProgress({ worker: { core: { indexState: 'IDLE', indexedDocuments: 1 } },
    operating: { indexing: { state: paused ? 'PAUSED' : 'RUNNING', reasons: paused ? ['user_paused'] : [] } } } as unknown as StatusResponse, true, 0, []);
  const el = document.createElement('jf-indexing-controls') as HTMLElement & { progress: ReturnType<typeof progress>; updateComplete: Promise<unknown> };
  Object.assign(el, { toolbar: true, compact: true, allowPause: true, progress: progress(true) });
  document.body.append(el); await settle(el);
  const toggle = button(must(el.shadowRoot, '.pause-menu'), 'Resume reading');
  toggle.focus(); expect(el.shadowRoot!.activeElement).toBe(toggle);
  toggle.click(); await settle(el); expect(el.shadowRoot!.activeElement).toBe(toggle);
  el.progress = progress(false); await settle(el);
  expect(el.shadowRoot!.activeElement).toBe(toggle);
  expect(toggle.textContent?.trim()).toBe('Pause reading');
  expect(toggle.getAttribute('aria-disabled')).toBe('false');
  toggle.click(); await settle(el); button(must(el.shadowRoot, '.pause-options'), 'Until I resume');
});

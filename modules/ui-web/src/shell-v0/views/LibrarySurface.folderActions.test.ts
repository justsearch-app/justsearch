// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import './BrowseSurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { SurfaceTabs } from '../components/SurfaceTabs.js';
import { __seedForTest, __resetForTest as resetSurfaces } from '../../api/registry/SurfaceCatalogClient.js';
import type { Surface } from '../../api/types/surface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';
import { __resetOwnReversalsForTest, latestReversalAt, undoLatestReversal } from '../state/ownReversals.js';

const key = '0194f72c-0000-7000-8000-000000000001';
const root = (pathHash: string): IndexedRootView => ({ pathHash, collection: 'default', fileCount: 0, walkCompleted: true });
async function pump(el: LibrarySurface) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function mount(options: { invoke?: ReturnType<typeof vi.fn>; picks?: (string | null)[]; pick?: () => Promise<string | null> } = {}) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexedDocuments: 0, indexHealthy: true }, migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
  let rows = [root('a'), root('b')];
  let outcome = { state: 'running', historySince: 0 };
  const fetch = vi.fn(async (path: string) => {
    if (path.startsWith('/api/indexing-roots/substrate')) return new Response(JSON.stringify({ items: rows }));
    if (path.startsWith('/api/operation-history/')) return new Response(JSON.stringify(outcome));
    return new Response('{}', { status: 503 });
  });
  const invoke = options.invoke ?? vi.fn().mockResolvedValue({ success: true });
  const confirm = vi.fn().mockResolvedValue(true);
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = { platform: { capabilities: new Set(options.picks ? ['folder-picker'] : []),
    pickFolder: options.pick ?? vi.fn(async () => options.picks?.shift() ?? null) },
    utilities: { formatRelativeTime: () => 'just now' },
    data: { fetch, invokeOperation: invoke }, ui: { showConfirmDialog: confirm },
  } as unknown as PluginHostApi;
  el.resolvedPaths = { a: '/docs', b: '/other' };
  document.body.append(el);
  await pump(el);
  for (const toggle of el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.folder-toggle')) toggle.click();
  await pump(el);
  return { el, invoke, confirm, fetch, setRows: (value: IndexedRootView[]) => { rows = value; },
    setOutcome: (value: typeof outcome) => { outcome = value; } };
}
type BandAnswer = HTMLElement & { onActivate: () => unknown };
type Band = HTMLElement & { heading: string; consequence: string; actionLabel: string; updateComplete: Promise<unknown> };
/** DE1 — the in-place band Remove opens (null when none is open). */
function band(el: LibrarySurface): Band | null {
  return el.shadowRoot!.querySelector<Band>('jf-confirm-band');
}
/** Answer the open band with its verb ('confirm') or "Keep it" ('keep'). */
async function answer(el: LibrarySurface, which: 'confirm' | 'keep') {
  const open = band(el);
  expect(open, 'removal band').not.toBeNull();
  await open!.updateComplete;
  open!.shadowRoot!.querySelector<BandAnswer>(`jf-button.${which}`)!.onActivate();
  await pump(el);
}
/** Remove the first listed folder: Remove opens the band under it; its verb removes. */
async function removeFolder(el: LibrarySurface) {
  button(el, 'Remove from library').onActivate(); await pump(el);
  await answer(el, 'confirm');
}
function button(el: LibrarySurface, label: string) {
  const found = [...el.shadowRoot!.querySelectorAll<HTMLElement & { onActivate: () => unknown; disabled: boolean }>('jf-button')]
    .find(b => b.getAttribute('label') === label);
  expect(found, `button ${label}`).toBeDefined();
  return found!;
}
async function draft(el: LibrarySurface) {
  button(el, 'Add folder').onActivate(); await pump(el);
  el.pendingPath = '/new'; el.pendingCollection = 'reports'; await pump(el);
}
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); resetSurfaces(); vi.restoreAllMocks(); });

function seedTabs() {
  const surfaces = ['library', 'browse'].map(name => ({ id: `core.${name}-surface`, mountTag: `jf-${name}-surface`,
    presentation: { labelKey: name, descriptionKey: '', iconHint: null, category: null }, audience: 'USER',
    placement: 'DEEPLINK', consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    ...(name === 'library' ? { members: ['core.browse-surface'] } : {}),
  } as Surface));
  __seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: surfaces });
}

it('keeps a refused Add draft reachable and allows correction and retry', async () => {
  const invoke = vi.fn().mockRejectedValueOnce(new Error('Folder overlaps an already watched folder: /docs'))
    .mockResolvedValue({ success: true });
  const { el } = await mount({ invoke }); await draft(el);
  button(el, 'Add').onActivate(); await pump(el);
  expect(el.showManualInput).toBe(true);
  expect(el.pendingPath).toBe('/new'); expect(el.pendingCollection).toBe('reports');
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('overlaps');
  expect(el.shadowRoot!.activeElement?.getAttribute('aria-label')).toBe('Folder path');
  el.pendingPath = '/corrected'; button(el, 'Add').onActivate(); await pump(el);
  expect(invoke).toHaveBeenLastCalledWith('core.add-watched-root', { path: '/corrected', collection: 'reports' });
  expect(el.showManualInput).toBe(false);
});

// S34.2: exercise the preview response boundary, including typing, before submission.
it.each(['inside', 'containing'])('explains a preview overlap (%s) and blocks click and Enter before Add', async (relationship) => {
  vi.useFakeTimers();
  try {
    const { el, fetch, invoke } = await mount(); await draft(el);
    fetch.mockImplementation(async (path: string) => path === '/api/indexing-roots/preview'
      ? new Response(JSON.stringify({ exists: true, isDir: true, fileCount: 0, capped: false,
        alreadyWatched: false, overlap: { path: '/docs', relationship } }))
      : new Response('{}', { status: 503 }));
    const input = el.shadowRoot!.querySelector<HTMLInputElement>('[aria-label="Folder path"]')!;
    input.value = '/new'; input.dispatchEvent(new Event('input', { bubbles: true }));
    await vi.advanceTimersByTimeAsync(350); await pump(el);
    const line = el.shadowRoot!.querySelector('.add-preview')!;
    expect(line.textContent).toContain('/docs');
    expect(line.textContent).toContain(relationship === 'inside' ? 'inside' : 'contains');
    expect(line.getAttribute('role')).toBe('status');
    const add = button(el, 'Add') as ReturnType<typeof button> & { availability: { kind: string; reason: string } };
    expect(add.availability.kind).toBe('unavailable');
    expect(add.availability.reason).toContain('/docs');
    add.onActivate();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await pump(el); expect(invoke).not.toHaveBeenCalled();
    // Correcting the path fetches a new preview; older servers may omit overlap.
    fetch.mockResolvedValue(new Response(JSON.stringify({ exists: true, isDir: true, fileCount: 0, alreadyWatched: false })));
    input.value = '/corrected'; input.dispatchEvent(new Event('input', { bubbles: true }));
    await vi.advanceTimersByTimeAsync(350); await pump(el);
    expect((button(el, 'Add') as typeof add).availability.kind).toBe('available');
    button(el, 'Add').onActivate(); await pump(el);
    expect(invoke).toHaveBeenCalledWith('core.add-watched-root', { path: '/corrected', collection: 'reports' });
  } finally { document.body.replaceChildren(); vi.useRealTimers(); }
});

it('submits Add once while waiting and announces its outcome', async () => {
  const request = deferred<{ success: boolean }>(); const invoke = vi.fn(() => request.promise);
  const { el } = await mount({ invoke }); await draft(el);
  const add = button(el, 'Add'); add.onActivate(); add.onActivate(); await pump(el);
  expect(invoke).toHaveBeenCalledTimes(1);
  expect(el.shadowRoot!.textContent).toContain('Adding folder');
  request.resolve({ success: true }); await pump(el);
  expect(el.shadowRoot!.querySelector('.action-message[role="status"]')?.textContent).toContain('Folder added');
  expect(el.shadowRoot!.activeElement?.getAttribute('label')).toBe('Add folder');
});

it('switching member tabs during Add retains the pending outcome and draft until acceptance', async () => {
  seedTabs();
  const request = deferred<{ success: boolean }>(); const invoke = vi.fn(() => request.promise);
  const { el } = await mount({ invoke }); await draft(el);
  button(el, 'Add').onActivate(); await pump(el);
  const tabs = el.shadowRoot!.querySelector<SurfaceTabs>('jf-surface-tabs')!;
  await tabs.updateComplete;
  tabs.shadowRoot!.querySelector<HTMLButtonElement>('[data-tab-id="core.browse-surface"]')!.click();
  await pump(el); await tabs.updateComplete;
  expect(tabs.shadowRoot!.querySelector('jf-browse-surface')).not.toBeNull();
  expect(el.addBusy).toBe(true); expect(el.pendingPath).toBe('/new');
  request.resolve({ success: true }); await pump(el);
  tabs.shadowRoot!.querySelector<HTMLButtonElement>('[data-tab-id="folders"]')!.click();
  await pump(el); await tabs.updateComplete;
  expect(invoke).toHaveBeenCalledTimes(1);
  expect(el.shadowRoot!.textContent).toContain('Folder added');
  expect(el.showManualInput).toBe(false);
});

it('a cancelled native picker leaves a closed form and an existing draft unchanged', async () => {
  const { el } = await mount({ picks: [null, '/picked', null] });
  button(el, 'Add folder').onActivate(); await pump(el);
  expect(el.showManualInput).toBe(false);
  button(el, 'Add folder').onActivate(); await pump(el);
  el.pendingCollection = 'keep'; const preview = el.pendingPreview;
  button(el, 'Add folder').onActivate(); await pump(el);
  expect(el.pendingPath).toBe('/picked'); expect(el.pendingCollection).toBe('keep');
  expect(el.pendingPreview).toBe(preview);
});

it('exposes row removal progress, suppresses repeats and keeps the other row usable', async () => {
  const request = deferred<{ success: boolean }>(); const invoke = vi.fn(() => request.promise);
  const { el, setRows } = await mount({ invoke });
  await removeFolder(el);
  expect(el.shadowRoot!.querySelector('.card[aria-busy="true"]')?.textContent).toContain('Removing');
  expect(button(el, 'Removing…').disabled).toBe(true);
  button(el, 'Removing…').onActivate(); await pump(el); expect(invoke).toHaveBeenCalledTimes(1);
  expect(button(el, 'Remove from library').disabled).toBe(false);
  setRows([root('b')]); request.resolve({ success: true }); await pump(el);
  expect(el.roots.map(r => r.pathHash)).toEqual(['b']);
  expect(el.shadowRoot!.querySelector('.action-message[role="status"]')?.textContent).toContain('Folder removed');
  expect(el.shadowRoot!.activeElement?.getAttribute('label')).toBe('Add folder');
});

it('R3 a completed removal offers to add the same folder again, once, with its plain consequence', async () => {
  const notices: EphemeralToastSpec[] = [];
  const listen = (event: Event) => notices.push((event as CustomEvent<EphemeralToastSpec>).detail);
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
  try {
    const { el, invoke, setRows } = await mount();
    setRows([root('b')]);
    await removeFolder(el);
    expect(notices.map(n => n.actionLabel)).toContain('Add docs again');
    const offer = notices.find(n => n.actionLabel === 'Add docs again')!;
    expect(offer.message).toBe('Removed docs from your library');
    // DE6 — the real reversal of the removal: Ctrl+Z and the pill's hint apply to it.
    expect(offer.reversal).toBe(true);
    expect(offer.restoresFolder).toBe('/docs');
    expect(offer.detail).toContain('/docs');
    expect(offer.detail).toContain('Adding it again reads its files again');
    // Works after leaving the Library; a repeated click adds once.
    el.remove();
    offer.onAction!(); offer.onAction!(); await pump(el);
    expect(invoke.mock.calls.filter(call => call[0] === 'core.add-watched-root')).toEqual([['core.add-watched-root', { path: '/docs' }]]);
    expect(notices.map(n => n.message)).toContain('Added docs again. Reading its files.');
  } finally { document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen); }
});

it('L20 Ctrl+Z after another window already added the folder back says so and sends no add', async () => {
  __resetOwnReversalsForTest();
  const notices: EphemeralToastSpec[] = [];
  const listen = (event: Event) => notices.push((event as CustomEvent<EphemeralToastSpec>).detail);
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
  try {
    const { el, invoke, fetch, setRows } = await mount();
    setRows([root('b')]);
    await removeFolder(el);
    expect(notices.map(n => n.actionLabel)).toContain('Add docs again');
    // Another window adds /docs again: the Engine now watches it.
    const watching = fetch.getMockImplementation()!;
    fetch.mockImplementation(async (path: string, init?: { body?: string }) => path === '/api/indexing-roots/preview'
      && JSON.parse(init?.body ?? '{}').path === '/docs' ? new Response(JSON.stringify({ exists: true, isDir: true, alreadyWatched: true }))
      : watching(path));
    const adds = () => invoke.mock.calls.filter(call => call[0] === 'core.add-watched-root').length;
    expect(undoLatestReversal(), 'Ctrl+Z finds the removal').toBe(true);
    await pump(el);
    expect(adds(), 'no add is sent for a folder that is already back').toBe(0);
    expect(notices.map(n => n.message)).not.toContain('Added docs again. Reading its files.');
    expect(notices.at(-1)?.message).toBe("Can't add docs again: docs is already back in your library.");
    // The offer no longer applies: a second Ctrl+Z finds nothing to add back.
    expect(latestReversalAt()).toBeNull();
    expect(undoLatestReversal()).toBe(false);
    expect(adds()).toBe(0);
  } finally { document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen); }
});

it('R3 a failed add-again says so and can be tried again', async () => {
  const notices: EphemeralToastSpec[] = [];
  const listen = (event: Event) => notices.push((event as CustomEvent<EphemeralToastSpec>).detail);
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
  try {
    const invoke = vi.fn().mockResolvedValueOnce({ success: true }).mockRejectedValueOnce(new Error('Engine busy')).mockResolvedValue({ success: true });
    const { el, setRows } = await mount({ invoke });
    setRows([root('b')]);
    await removeFolder(el);
    expect(notices.map(n => n.actionLabel)).toContain('Add docs again');
    const offer = notices.find(n => n.actionLabel === 'Add docs again')!;
    offer.onAction!(); await pump(el);
    expect(notices.find(n => n.severity === 'error')?.message).toBe('Could not add docs again. Engine busy');
    offer.onAction!(); await pump(el);
    expect(invoke).toHaveBeenCalledTimes(3);
  } finally { document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen); }
});

it('a refused removal retains the row, explains retry without a live-watch claim, and retries', async () => {
  const invoke = vi.fn().mockRejectedValueOnce(new Error('Engine refusal')).mockResolvedValue({ success: true });
  const { el, setRows } = await mount({ invoke });
  await removeFolder(el);
  expect(el.roots).toHaveLength(2);
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('Removal did not complete');
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('retry');
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).not.toContain('still being watched');
  setRows([root('b')]); await removeFolder(el);
  expect(invoke).toHaveBeenCalledTimes(2); expect(el.roots).toHaveLength(1);
});

it.each(['NETWORK_ERROR', 'SERIALIZATION_ERROR', 'HANDLER_FAILURE'])('reconciles an uncertain removal response with current roots (%s)', async (errorClass) => {
  const request = deferred<{ success: boolean }>(); const { el, setRows } = await mount({ invoke: vi.fn(() => request.promise) });
  await removeFolder(el);
  setRows([root('b')]); request.reject(Object.assign(new Error('Response lost'), { errorClass }));
  await pump(el);
  expect(el.shadowRoot!.textContent).toContain('Folder removed'); expect(el.error).toBeNull();
  expect(el.roots.map(r => r.pathHash)).toEqual(['b']);
});

it('ignores an old removal response after returning and reads the current roots', async () => {
  const request = deferred<{ success: boolean }>(); const { el, setRows } = await mount({ invoke: vi.fn(() => request.promise) });
  await removeFolder(el); el.remove(); setRows([root('b')]);
  document.body.append(el); await pump(el);
  request.reject(new Error('obsolete refusal')); await pump(el);
  expect(el.error).toBeNull(); expect(el.roots.map(r => r.pathHash)).toEqual(['b']);
  expect(el.shadowRoot!.textContent).not.toContain('obsolete refusal');
});

it('names legacy inner folders and ancestor coverage in Remove confirmations', async () => {
  const { el, confirm, invoke } = await mount();
  el.resolvedPaths = { a: '/docs', b: '/docs/inner' };
  button(el, 'Remove from library').onActivate(); await pump(el);
  expect(band(el)!.consequence).toContain('/docs/inner');
  await answer(el, 'keep');
  expect(band(el)).toBeNull();
  const removes = [...el.shadowRoot!.querySelectorAll<HTMLElement & { onActivate: () => unknown }>('jf-button[label="Remove from library"]')];
  removes[1]!.onActivate(); await pump(el);
  expect(band(el)!.consequence).toContain('stay searchable');
  expect(band(el)!.consequence).toContain('/docs');
  await answer(el, 'keep');
  // DE1 — the band replaced the window-blocking dialog; keeping removes nothing.
  expect(confirm).not.toHaveBeenCalled();
  expect(invoke).not.toHaveBeenCalled();
});

it('distinguishes accepted reindex from the recorded completion and failure', async () => {
  const { el, setOutcome } = await mount();
  const reindex = el.shadowRoot!.querySelector('jf-operation[operation-id="core.reindex"]')!;
  reindex.dispatchEvent(new CustomEvent('op-success', { detail: { structuredData: { operationKey: key } } }));
  await pump(el);
  expect(el.shadowRoot!.textContent).toContain('Reindex accepted');
  expect(el.shadowRoot!.textContent).not.toContain('Reindex completed');
  setOutcome({ state: 'complete', historySince: 0 });
  button(el, 'Check reindex outcome').onActivate(); await pump(el);
  expect(el.shadowRoot!.textContent).toContain('Reindex completed');
  setOutcome({ state: 'failed', historySince: 0 });
  reindex.dispatchEvent(new CustomEvent('op-success', { detail: { structuredData: { operationKey: key } } }));
  await pump(el);
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('Reindex did not complete');
  expect(el.shadowRoot!.textContent).toContain('retry');
});

it('explains an empty reindex scope and offline folders without claiming a full reindex', async () => {
  const { el } = await mount();
  el.roots = []; await pump(el);
  expect(el.shadowRoot!.textContent).toContain('No watched folders to reindex');
  expect(el.shadowRoot!.querySelector('jf-operation[operation-id="core.reindex"]')).toBeNull();
  el.roots = [{ ...root('a'), status: 'unavailable' }]; await pump(el);
  expect(el.shadowRoot!.textContent).toContain('offline');
});

for (const mode of ['browser', 'desktop'] as const) {
  it.each(['folders', 'core.browse-surface'])(`R1 ${mode} Add reveals and focuses Folders from %s and Cancel restores focus`, async tab => {
    seedTabs();
    const { el } = await mount(mode === 'desktop' ? { picks: ['/picked'] } : {});
    const tabs = el.shadowRoot!.querySelector<SurfaceTabs>('jf-surface-tabs')!;
    el.activeTab = tab; await pump(el); await tabs.updateComplete;
    button(el, 'Add folder').focus(); button(el, 'Add folder').onActivate();
    await pump(el); await tabs.updateComplete;
    expect(el.activeTab).toBe('folders');
    expect(tabs.shadowRoot!.querySelector('[data-tab-id="folders"]')?.getAttribute('aria-selected')).toBe('true');
    expect(tabs.shadowRoot!.querySelector('slot[name="tab-folders"]')).not.toBeNull();
    expect(el.showManualInput).toBe(true);
    expect(el.shadowRoot!.activeElement?.getAttribute('aria-label')).toBe('Folder path');
    expect(el.pendingPath).toBe(mode === 'desktop' ? '/picked' : '');
    button(el, 'Cancel').onActivate(); await pump(el);
    expect(el.showManualInput).toBe(false);
    expect(el.shadowRoot!.activeElement).toBe(button(el, 'Add folder'));
  });
}

it('R1 a late picker selects Folders, but a response from before reconnect cannot open a form', async () => {
  seedTabs(); const first = deferred<string | null>(); const obsolete = deferred<string | null>();
  const pick = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(obsolete.promise);
  const { el } = await mount({ picks: [], pick });
  el.activeTab = 'core.browse-surface'; await pump(el);
  button(el, 'Add folder').onActivate(); await pump(el);
  el.activeTab = 'folders'; await pump(el); el.activeTab = 'core.browse-surface'; await pump(el);
  first.resolve('/late'); await pump(el);
  expect(el.activeTab).toBe('folders'); expect(el.pendingPath).toBe('/late');
  expect(el.shadowRoot!.activeElement?.getAttribute('aria-label')).toBe('Folder path');
  button(el, 'Cancel').onActivate(); await pump(el);
  button(el, 'Add folder').onActivate(); await pump(el);
  el.remove(); document.body.append(el); await pump(el);
  obsolete.resolve('/obsolete'); await pump(el);
  expect(el.showManualInput).toBe(false); expect(el.pendingPath).not.toBe('/obsolete');
});

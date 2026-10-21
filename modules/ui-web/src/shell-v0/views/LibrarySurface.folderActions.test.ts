// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';

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
async function mount(options: { invoke?: ReturnType<typeof vi.fn>; picks?: (string | null)[] } = {}) {
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
    pickFolder: vi.fn(async () => options.picks?.shift() ?? null) },
    utilities: { formatRelativeTime: () => 'just now' },
    data: { fetch, invokeOperation: invoke }, ui: { showConfirmDialog: confirm },
  } as unknown as PluginHostApi;
  el.resolvedPaths = { a: '/docs', b: '/other' };
  document.body.append(el);
  await pump(el);
  return { el, invoke, confirm, fetch, setRows: (value: IndexedRootView[]) => { rows = value; },
    setOutcome: (value: typeof outcome) => { outcome = value; } };
}
function button(el: LibrarySurface, label: string) {
  const found = [...el.shadowRoot!.querySelectorAll<HTMLElement & { onActivate: () => unknown; disabled: boolean }>('jf-button')]
    .find(b => b.getAttribute('label') === label);
  expect(found, `button ${label}`).toBeDefined();
  return found!;
}
async function draft(el: LibrarySurface) {
  button(el, 'Add Folder').onActivate(); await pump(el);
  el.pendingPath = '/new'; el.pendingCollection = 'reports'; await pump(el);
}
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.restoreAllMocks(); });

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
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('Folder added');
  expect(el.shadowRoot!.activeElement?.getAttribute('label')).toBe('Add Folder');
});

it('a cancelled native picker leaves a closed form and an existing draft unchanged', async () => {
  const { el } = await mount({ picks: [null, '/picked', null] });
  button(el, 'Add Folder').onActivate(); await pump(el);
  expect(el.showManualInput).toBe(false);
  button(el, 'Add Folder').onActivate(); await pump(el);
  el.pendingCollection = 'keep'; const preview = el.pendingPreview;
  button(el, 'Add Folder').onActivate(); await pump(el);
  expect(el.pendingPath).toBe('/picked'); expect(el.pendingCollection).toBe('keep');
  expect(el.pendingPreview).toBe(preview);
});

it('exposes row removal progress, suppresses repeats and keeps the other row usable', async () => {
  const request = deferred<{ success: boolean }>(); const invoke = vi.fn(() => request.promise);
  const { el, setRows } = await mount({ invoke });
  button(el, 'Remove').onActivate(); await pump(el);
  expect(el.shadowRoot!.querySelector('.card[aria-busy="true"]')?.textContent).toContain('Removing');
  expect(button(el, 'Removing…').disabled).toBe(true);
  button(el, 'Removing…').onActivate(); await pump(el); expect(invoke).toHaveBeenCalledTimes(1);
  expect(button(el, 'Remove').disabled).toBe(false);
  setRows([root('b')]); request.resolve({ success: true }); await pump(el);
  expect(el.roots.map(r => r.pathHash)).toEqual(['b']);
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('Folder removed');
  expect(el.shadowRoot!.activeElement?.getAttribute('label')).toBe('Add Folder');
});

it('a refused removal retains the row, explains retry without a live-watch claim, and retries', async () => {
  const invoke = vi.fn().mockRejectedValueOnce(new Error('Engine refusal')).mockResolvedValue({ success: true });
  const { el, setRows } = await mount({ invoke });
  button(el, 'Remove').onActivate(); await pump(el);
  expect(el.roots).toHaveLength(2);
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('Removal did not complete');
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('retry');
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).not.toContain('still being watched');
  setRows([root('b')]); button(el, 'Remove').onActivate(); await pump(el);
  expect(invoke).toHaveBeenCalledTimes(2); expect(el.roots).toHaveLength(1);
});

it.each(['NETWORK_ERROR', 'SERIALIZATION_ERROR', 'HANDLER_FAILURE'])('reconciles an uncertain removal response with current roots (%s)', async (errorClass) => {
  const request = deferred<{ success: boolean }>(); const { el, setRows } = await mount({ invoke: vi.fn(() => request.promise) });
  button(el, 'Remove').onActivate(); await pump(el);
  setRows([root('b')]); request.reject(Object.assign(new Error('Response lost'), { errorClass }));
  await pump(el);
  expect(el.shadowRoot!.textContent).toContain('Folder removed'); expect(el.error).toBeNull();
  expect(el.roots.map(r => r.pathHash)).toEqual(['b']);
});

it('ignores an old removal response after returning and reads the current roots', async () => {
  const request = deferred<{ success: boolean }>(); const { el, setRows } = await mount({ invoke: vi.fn(() => request.promise) });
  button(el, 'Remove').onActivate(); await pump(el); el.remove(); setRows([root('b')]);
  document.body.append(el); await pump(el);
  request.reject(new Error('obsolete refusal')); await pump(el);
  expect(el.error).toBeNull(); expect(el.roots.map(r => r.pathHash)).toEqual(['b']);
  expect(el.shadowRoot!.textContent).not.toContain('obsolete refusal');
});

it('names legacy inner folders and ancestor coverage in Remove confirmations', async () => {
  const { el, confirm } = await mount();
  el.resolvedPaths = { a: '/docs', b: '/docs/inner' }; confirm.mockResolvedValue(false);
  button(el, 'Remove').onActivate(); await pump(el);
  expect(confirm.mock.calls[0]?.[0]).toContain('/docs/inner');
  const removes = [...el.shadowRoot!.querySelectorAll<HTMLElement & { onActivate: () => unknown }>('jf-button[label="Remove"]')];
  removes[1]!.onActivate(); await pump(el);
  expect(confirm.mock.calls[1]?.[0]).toContain('stay searchable');
  expect(confirm.mock.calls[1]?.[0]).toContain('/docs');
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

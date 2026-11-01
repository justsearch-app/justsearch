// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import '../renderers/controls/FolderCardRenderer.js';
import '../renderers/layouts/VerticalLayout.js';
import '../renderers/controls/XUiRendererControl.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { FolderCardRenderer } from '../renderers/controls/FolderCardRenderer.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import type { FailedJobsDrawer } from '../components/FailedJobsDrawer.js';
import { applyPresentationBodies, clearPresentation } from '../state/presentationRuntime.js';
import { CORE_DECLARED } from '../themes/builtinPresentations.js';
import { createPlatformApi } from '../plugin-api/capabilities/platform.js';

const native = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: native.invoke }));

const root = (pathHash: string): IndexedRootView => ({ pathHash, status: 'indexed', collection: 'personal',
  fileCount: 10, inFlightCount: 0, failedCount: 0, walkCompleted: true });
async function settle(el: { updateComplete: Promise<unknown> }) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
function observe(documents?: number) {
  __feedForTest({ status: { worker: { core: { indexedDocuments: documents, indexState: 'IDLE', indexHealthy: true },
    migration: { migrationState: 'IDLE' } } } as unknown as StatusSnapshot });
}
async function mount() {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async () => new Response('{}', { status: 503 }));
  host.platform.revealInExplorer = vi.fn().mockResolvedValue(undefined);
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host;
  document.body.append(el); await settle(el);
  el.roots = [root('a'), root('b')]; el.resolvedPaths = { a: '/one/Same name', b: '/two/文書, Same name' };
  el.loading = false; await settle(el);
  return { el, host };
}
afterEach(() => { document.body.replaceChildren(); clearPresentation(); __resetAiStateForTest(); vi.restoreAllMocks(); vi.unstubAllGlobals(); native.invoke.mockReset(); });

it.each([undefined, 0, 20])('the headline uses observed searchable documents (%s)', async documents => {
  observe(documents); const { el } = await mount();
  expect(el.shadowRoot!.querySelector('h2')?.textContent).toBe(documents === undefined ? 'Checking your folders'
    : documents === 0 ? 'Indexing your files' : 'Your files are searchable');
});

it('counts a rebuild decision during recovery and counts a terminal outcome only once', async () => {
  observe(20); const { el } = await mount();
  Object.assign(el, { provisional: true, reindexCancelKey: 'rebuild-key' }); await settle(el);
  expect(el.shadowRoot!.querySelector('h2')?.textContent).toBe('1 thing needs your attention');
  Object.assign(el, { bulkOutcome: { state: 'failed', message: 'Rebuild failed' } }); await settle(el);
  expect(el.shadowRoot!.querySelector('h2')?.textContent).toBe('1 thing needs your attention');
  expect(el.shadowRoot!.querySelector('.gap-decision')).toBeNull();
  expect(el.shadowRoot!.querySelector('.attention')?.textContent).toContain('Check outcome');
});

it('retains named last-known failures while a missing folder returns and jobs requeue, until settled', async () => {
  observe(20); const { el, host } = await mount();
  vi.mocked(host.data.fetch).mockImplementation(async () => Response.json({ jobs: [{ pathHash: 'bad-file',
    state: 'FAILED', attempts: 3, lastUpdatedMs: 1700000000000, errorMessage: 'Could not read the source',
    retryAfterMs: 0, collection: 'personal', scanId: '' }], count: 1 }));
  el.roots = [{ ...root('a'), status: 'unavailable', failedCount: 1 }]; await settle(el);
  const failures = el.shadowRoot!.querySelector<FailedJobsDrawer>('jf-failed-jobs-drawer[inline]')!;
  await settle(failures);
  expect(failures.shadowRoot!.textContent).toContain('Could not read the source');
  // A retained settled observation supplies the existing folderStatus memory seam.
  Object.assign(el, { lastKnownFailed: { a: 1 } });
  el.roots = [{ ...root('a'), inFlightCount: 1, failedCount: 0 }]; await settle(el); await settle(failures);
  expect(el.shadowRoot!.querySelector('jf-failed-jobs-drawer[inline]')).toBe(failures);
  expect(el.shadowRoot!.querySelector('.attention')?.textContent).toContain('last known');
  expect(failures.shadowRoot!.textContent).toContain('Could not read the source');
  el.roots = [root('a')]; await settle(el);
  expect(el.shadowRoot!.querySelector('jf-failed-jobs-drawer[inline]')).toBeNull();
});

it.each(['fallback', 'themed'])('%s rows keep several disclosures open through status updates and expose full paths', async mode => {
  if (mode === 'themed') expect(applyPresentationBodies(CORE_DECLARED).ok).toBe(true);
  observe(20); const { el } = await mount();
  const find = (node: ParentNode): FolderCardRenderer | null => {
    for (const child of node.querySelectorAll('*')) {
      if (child.localName === 'jf-folder-card') return child as FolderCardRenderer;
      if (child.shadowRoot) { const found = find(child.shadowRoot); if (found) return found; }
    }
    return null;
  };
  const rows = () => mode === 'themed' ? find(el.shadowRoot!)! : el;
  await vi.waitFor(() => expect(rows()).not.toBeNull());
  await settle(rows());
  expect([...rows().shadowRoot!.querySelectorAll('.folder-toggle')].map(button => button.getAttribute('aria-expanded'))).toEqual(['false', 'false']);
  expect(rows().shadowRoot!.querySelector('.folder-details')).toBeNull();
  for (let index = 0; index < 2; index++) {
    rows().shadowRoot!.querySelectorAll<HTMLButtonElement>('.folder-toggle')[index]!.click();
    await settle(el); await settle(rows());
  }
  // A fresh projection recreates the declared renderer, as a live status poll does.
  el.roots = el.roots.map(r => ({ ...r, fileCount: 12 })); await settle(el); await settle(rows());
  const toggles = [...rows().shadowRoot!.querySelectorAll('.folder-toggle')];
  expect(toggles.map(button => button.getAttribute('aria-expanded'))).toEqual(['true', 'true']);
  expect(rows().shadowRoot!.querySelector('.folder-name')?.getAttribute('title')).toBe('/one/Same name');
  expect(rows().shadowRoot!.textContent).toContain('12 files found');
  expect(rows().shadowRoot!.textContent).toContain('Searchable by words');
});

it('Explorer actions are absent in browsers and use each resolved path on desktop', async () => {
  observe(20); const { el, host } = await mount();
  expect(el.shadowRoot!.querySelector('jf-button[label="Open in Explorer"]')).toBeNull();
  el.isTauri = true; await settle(el);
  for (const toggle of el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.folder-toggle')) toggle.click();
  await settle(el);
  const actions = [...el.shadowRoot!.querySelectorAll<HTMLElement & { onActivate: () => void }>('jf-button[label="Open in Explorer"]')];
  actions.forEach(action => action.onActivate()); await settle(el);
  expect(host.platform.revealInExplorer).toHaveBeenNthCalledWith(1, '/one/Same name');
  expect(host.platform.revealInExplorer).toHaveBeenNthCalledWith(2, '/two/文書, Same name');
});

it.each(['unresolved', 'removed', 'disconnected', 'native refusal'])('Explorer explains %s including changes after rendering', async reason => {
  observe(20); const { el, host } = await mount(); el.isTauri = true; await settle(el);
  for (const toggle of el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.folder-toggle')) toggle.click();
  await settle(el);
  const action = el.shadowRoot!.querySelector<HTMLElement & { onActivate: () => void }>('jf-button[label="Open in Explorer"]')!;
  if (reason === 'unresolved') el.resolvedPaths = {};
  if (reason === 'removed') el.roots = [root('b')];
  if (reason === 'disconnected') el.roots = [{ ...root('a'), status: 'unavailable' }, root('b')];
  if (reason === 'native refusal') vi.mocked(host.platform.revealInExplorer).mockRejectedValue(new Error('Network and device paths are not allowed'));
  // Invoke the old rendered closure, before Lit has replaced it.
  action.onActivate(); await settle(el);
  expect(el.error).toContain(reason === 'unresolved' ? 'could not be resolved' : reason === 'removed' ? 'removed'
    : reason === 'disconnected' ? 'Folder not found' : 'Network and device paths are not allowed');
  expect(host.platform.revealInExplorer).toHaveBeenCalledTimes(reason === 'native refusal' ? 1 : 0);
});

it('uses the existing native reveal bridge and surfaces its network/device refusal', async () => {
  observe(20); const { el, host } = await mount();
  vi.stubGlobal('__TAURI_INTERNALS__', {});
  el.host_ = { ...host, platform: createPlatformApi('CORE') };
  el.isTauri = true; await settle(el);
  for (const toggle of el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.folder-toggle')) toggle.click();
  await settle(el);
  const action = el.shadowRoot!.querySelector<HTMLElement & { onActivate: () => void }>('jf-button[label="Open in Explorer"]')!;
  native.invoke.mockResolvedValue(undefined);
  action.onActivate();
  await vi.waitFor(() => expect(native.invoke).toHaveBeenCalledWith('reveal_in_explorer', { path: '/one/Same name' }));
  native.invoke.mockRejectedValue('Network and device paths are not allowed');
  action.onActivate();
  await vi.waitFor(() => expect(el.error).toContain('Network and device paths are not allowed'));
});

it('a second window receives live folder holds without overwriting a skip-rules draft', async () => {
  observe(20); const first = await mount(); const second = await mount();
  first.el.excludesText = '**/my-draft/**';
  __feedForTest({ status: { operating: { indexing: { state: 'PAUSED', reasons: ['schedule', 'on_battery'] } },
    worker: { core: { indexedDocuments: 20, indexState: 'IDLE', indexHealthy: true } } } as unknown as StatusSnapshot });
  await settle(first.el); await settle(second.el);
  for (const { el } of [first, second]) {
    expect(el.shadowRoot!.querySelector('.attention')?.textContent).toContain('Outside indexing hours');
    expect(el.shadowRoot!.querySelector('.attention')?.textContent).toContain('running on battery');
  }
  expect(first.el.excludesText).toBe('**/my-draft/**');
});


it('A2 expanded facts replace the old metadata line', async () => {
  observe(20); const { el } = await mount();
  el.shadowRoot!.querySelector<HTMLButtonElement>('.folder-toggle')!.click(); await settle(el);
  const details = el.shadowRoot!.querySelector('.folder-details')!;
  expect(details.textContent).toContain('Collection');
  expect(details.textContent).toContain('Searchable by words');
  expect(details.querySelector('.card-meta')).toBeNull();
});

it('A3 unconfirmed status is stated once through the real projection', async () => {
  observe(20); const { el } = await mount();
  __feedForTest({ status: { meta: { workerRpcStale: true }, worker: { core: { indexedDocuments: 20, indexState: 'IDLE' } } } as unknown as StatusSnapshot });
  await settle(el);
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toContain('unconfirmed');
  expect(el.shadowRoot!.querySelector('.subtitle')!.textContent).not.toContain('Indexing status unconfirmed');
});

it.each(['energy_saver', 'gpu_busy_external'])('A5 %s waits are stated once through the real projection', async reason => {
  observe(20); const { el } = await mount();
  __feedForTest({ status: { operating: { indexing: { reasons: [reason] } },
    worker: { core: { indexedDocuments: 20, indexState: 'IDLE', indexHealthy: true },
      enrichment: { embeddingEnabled: true, embeddingPendingCount: 2 } } } as unknown as StatusSnapshot });
  await settle(el);
  const summary = el.shadowRoot!.querySelector('.subtitle')!.textContent!;
  expect(summary).toContain(reason === 'energy_saver' ? 'Meaning search is waiting while the computer saves energy.'
    : 'GPU-bound idle enrichment deferred while another app is using the GPU.');
  expect(summary).not.toContain('Meaning processing is waiting');
});

it.each(['fallback', 'themed'])('V1 %s disclosure retains focus on toggle and live refresh', async mode => {
  if (mode === 'themed') expect(applyPresentationBodies(CORE_DECLARED).ok).toBe(true);
  observe(20); const { el } = await mount();
  const find = (node: ParentNode): HTMLButtonElement | null => {
    for (const child of node.querySelectorAll('*')) {
      if (child.matches('.folder-toggle')) return child as HTMLButtonElement;
      if (child.shadowRoot) { const found = find(child.shadowRoot); if (found) return found; }
    }
    return null;
  };
  const active = () => { let node = document.activeElement; while (node?.shadowRoot?.activeElement) node = node.shadowRoot.activeElement; return node; };
  await vi.waitFor(() => expect(find(el.shadowRoot!)).not.toBeNull());
  find(el.shadowRoot!)!.focus(); find(el.shadowRoot!)!.click(); await settle(el);
  await vi.waitFor(() => expect(active()).toBe(find(el.shadowRoot!)));
  expect(find(el.shadowRoot!)!.getAttribute('aria-expanded')).toBe('true');
  el.roots = el.roots.map(r => ({ ...r, fileCount: 21 })); await settle(el);
  await vi.waitFor(() => expect(active()).toBe(find(el.shadowRoot!)));
  expect(find(el.shadowRoot!)!.getAttribute('aria-expanded')).toBe('true');
  find(el.shadowRoot!)!.click(); await settle(el);
  await vi.waitFor(() => expect(active()).toBe(find(el.shadowRoot!)));
  expect(find(el.shadowRoot!)!.getAttribute('aria-expanded')).toBe('false');
});

it('D1 keeps attention and retried files through 25 alternating unconfirmed polls and clears on a settled observation', async () => {
  observe(20); const { el } = await mount();
  el.roots = [{ ...root('a'), failedCount: 1 }];
  const receivedAt = Date.now();
  const now = vi.spyOn(Date, 'now');
  for (let second = 0; second < 25; second++) {
    now.mockReturnValue(receivedAt + second * 1000);
    __feedForTest({ status: { meta: { workerRpcAtMs: receivedAt, workerRpcStale: second % 2 === 1 },
      operating: { indexing: { state: 'PAUSED', reasons: ['user_paused'] } },
      worker: { core: { indexedDocuments: 20, indexState: 'WAITING', pendingJobs: 1, indexHealthy: true } }
    } as unknown as StatusSnapshot });
    if (second === 1) {
      Object.assign(el, { lastKnownFailed: { a: 1 } });
      el.roots = [{ ...root('a'), inFlightCount: 1 }];
    }
    await settle(el);
    expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('2 things need your attention');
    expect(el.shadowRoot!.querySelector('.attention')!.textContent).toContain('Indexing paused');
    expect(el.shadowRoot!.querySelector('jf-failed-jobs-drawer[inline]')).not.toBeNull();
    if (second % 2) expect(el.shadowRoot!.querySelector('.attention')!.textContent).toContain('last known');
  }
  now.mockReturnValue(receivedAt + 31000); __tickClockForTest(); await settle(el);
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Indexing status unconfirmed');
  expect(el.shadowRoot!.querySelector('.attention')!.textContent).toContain('Indexing paused');
  observe(20); el.roots = [root('a')]; await settle(el);
  expect(el.shadowRoot!.querySelector('.attention-item')).toBeNull();
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Your files are searchable');
});

it('D1 never claims searchable during unconfirmed polls, including 42 seconds past the shared threshold', async () => {
  observe(20); const { el } = await mount();
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Your files are searchable');
  const receivedAt = Date.now();
  const now = vi.spyOn(Date, 'now');
  for (let second = 1; second <= 42; second++) {
    now.mockReturnValue(receivedAt + second * 1000);
    __feedForTest({ status: { meta: { workerRpcAtMs: receivedAt, workerRpcStale: true },
      worker: { core: { indexedDocuments: 20, indexState: 'IDLE', indexHealthy: true } }
    } as unknown as StatusSnapshot });
    await settle(el);
    expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Indexing status unconfirmed');
  }
  observe(20); await settle(el);
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Your files are searchable');
});

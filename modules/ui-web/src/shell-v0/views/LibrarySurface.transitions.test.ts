// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, getAiState, type StatusSnapshot } from '../state/aiStateStore.js';

async function settle(el: LibrarySurface) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}

function observe(indexState = 'IDLE', catchingUp = false, migrationState = 'IDLE') {
  __feedForTest({ status: { catchingUp, worker: {
    core: { indexedDocuments: 20, indexState, indexHealthy: true },
    migration: { migrationState },
  } } as unknown as StatusSnapshot });
}

async function mount(empty: boolean) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async () => new Response('{}', { status: 503 }));
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host;
  document.body.append(el); await settle(el);
  el.roots = empty ? [] : [{ pathHash: 'folder', status: 'indexed', collection: 'personal',
    fileCount: 20, inFlightCount: 0, failedCount: 0, walkCompleted: true }];
  el.loading = false; await settle(el);
  return el;
}

afterEach(() => {
  document.body.replaceChildren();
  __resetAiStateForTest();
  vi.restoreAllMocks();
});

it.each([false, true])('L2 wake recovery uses the shared catching-up headline (empty roots: %s)', async empty => {
  observe(); const el = await mount(empty);
  observe('IDLE', true); await settle(el);
  expect(getAiState().statusLabel).toBe('Catching up…');
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Catching up…');
  expect(el.shadowRoot!.querySelector('.empty')?.textContent ?? '').not.toContain('Rebuilding');
  if (empty) expect(el.shadowRoot!.querySelector('.empty')!.textContent).toContain('Catching up…');
  observe(); await settle(el);
  expect(el.shadowRoot!.querySelector('h2')!.textContent)
    .toBe(empty ? 'Add a folder to start searching' : 'Your files are searchable');
});

it.each([false, true])('L2 worker restart uses the shared restart headline (empty roots: %s)', async empty => {
  observe(); const el = await mount(empty);
  observe('UNAVAILABLE'); await settle(el);
  expect(getAiState().statusLabel).toBe('Restarting…');
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Restarting…');
  expect(el.shadowRoot!.querySelector('.empty')?.textContent ?? '').not.toContain('Rebuilding');
  if (empty) expect(el.shadowRoot!.querySelector('.empty')!.textContent).toContain('Restarting…');
  observe(); await settle(el);
  expect(el.shadowRoot!.querySelector('h2')!.textContent)
    .toBe(empty ? 'Add a folder to start searching' : 'Your files are searchable');
});

it.each(['MIGRATING', 'SWITCHING'])('L2 real migration retains the Library rebuilding wording (%s)', async migration => {
  observe(); const el = await mount(true);
  observe('IDLE', false, migration); await settle(el);
  expect(el.shadowRoot!.querySelector('h2')!.textContent).toBe('Rebuilding your Library');
  expect(el.shadowRoot!.querySelector('.empty')!.textContent).toContain('your watched folders will reappear');
});

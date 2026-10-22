// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';

const key = '0194f72c-0000-7000-8000-000000000001';
const message = 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild.';
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.restoreAllMocks(); });

it('keeps the server explanation and support code when polling a failed operation', async () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexedDocuments: 0, indexHealthy: true },
    migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
  const fetch = vi.fn(async (path: string) => {
    if (path.startsWith('/api/indexing-roots/substrate')) return new Response(JSON.stringify({ items: [
      { pathHash: 'a', collection: 'default', fileCount: 0, walkCompleted: true },
    ] }));
    if (path.startsWith('/api/operation-history/')) return new Response(JSON.stringify({ state: 'failed', historySince: 0,
      reason: 'BULK_GENERATION_REFUSED', message, result: { code: 'BULK_GENERATION_REFUSED' } }));
    return new Response('{}', { status: 503 });
  });
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = { platform: { capabilities: new Set() }, utilities: { formatRelativeTime: () => 'just now' },
    data: { fetch, invokeOperation: vi.fn() }, ui: { showConfirmDialog: vi.fn() },
  } as unknown as PluginHostApi;
  document.body.append(el);
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
  el.shadowRoot!.querySelector('jf-operation[operation-id="core.reindex"]')!.dispatchEvent(
    new CustomEvent('op-success', { detail: { structuredData: { operationKey: key } } }));
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain(message);
  expect(el.shadowRoot!.querySelector('[role="alert"]')?.textContent).toContain('Code: BULK_GENERATION_REFUSED');
});

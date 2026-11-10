// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';

afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); });

it('shows an interrupted import without indexed roots, and refreshes on focus and returning from Browse', async () => {
  const inventory = vi.fn(async () => Response.json({ imports: [{ operationKey: 'import-one',
    interruptionRevision: 3, reason: 'RECOVERY_AUTHORIZATION_REFUSED',
    folders: [{ path: '/original/private' }], resumeAvailable: true }] }));
  const fetch = vi.fn(async (path: string) => path === '/api/indexing/imports/interrupted'
    ? inventory() : new Response('', { status: 503 }));
  const library = document.createElement('jf-library-surface') as LibrarySurface;
  library.host_ = { platform: { capabilities: new Set<string>() }, data: { fetch } } as unknown as PluginHostApi;
  document.body.append(library);
  const pump = async () => {
    for (let i = 0; i < 30; i++) { await Promise.resolve(); await library.updateComplete; }
  };
  await pump();
  const panel = library.shadowRoot!.querySelector('jf-interrupted-imports');
  expect(panel, 'Library must expose retained imports even when there are no indexed roots').not.toBeNull();
  expect(panel!.shadowRoot!.textContent).toContain('/original/private');
  const before = inventory.mock.calls.length;
  window.dispatchEvent(new FocusEvent('focus')); await pump();
  expect(inventory.mock.calls.length).toBeGreaterThan(before);
  library.activeTab = 'core.browse-surface'; await pump();
  expect(library.shadowRoot!.querySelector('jf-interrupted-imports')).toBeNull();
  const hiddenReads = inventory.mock.calls.length;
  window.dispatchEvent(new FocusEvent('focus')); await pump();
  expect(inventory.mock.calls.length).toBe(hiddenReads);
  library.activeTab = 'folders'; await pump();
  expect(inventory.mock.calls.length).toBeGreaterThan(hiddenReads);
});

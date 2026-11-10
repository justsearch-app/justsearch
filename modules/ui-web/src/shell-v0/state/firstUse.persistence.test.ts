// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest';
const key = 'justsearch.userState.v2';
async function openWindow() {
  vi.resetModules();
  const doc = await import('./UserStateDocument.js');
  const loaders = import.meta.glob('./firstUse.ts');
  const loader = Object.values(loaders)[0];
  const api = loader ? await loader() as typeof import('./firstUse.js') : undefined;
  expect(api, 'Getting started must use the existing shared document').toBeDefined();
  doc.getDocument();
  return { doc, api: api! };
}
beforeEach(() => localStorage.clear());
describe('Getting started shared document', () => {
  it('Hide in another window survives a co-resident save and Help reopening propagates back', async () => {
    const a = await openWindow(); const b = await openWindow();
    b.api.hideGettingStarted(); a.doc.setPluginSetting('kept', 'setting', 42);
    expect(a.doc.getWalkthroughProgress('core.getting-started')?.dismissed).toBe(true);
    a.api.reopenGettingStarted();
    window.dispatchEvent(new StorageEvent('storage', { key, storageArea: localStorage, newValue: localStorage.getItem(key) }));
    expect(b.doc.getWalkthroughProgress('core.getting-started')).toMatchObject({ dismissed: false, completedStepIds: ['reopened-from-help'] });
    expect(b.doc.getDocument().pluginSettings).toEqual({ kept: { setting: 42 } });
  });
  it('malformed legacy storage is ignored without overwriting its original bytes on viewing', async () => {
    const a = await openWindow(); a.doc.getDocument();
    const stored = JSON.parse(localStorage.getItem(key)!);
    stored.walkthroughState = { welcome: { activeStepIndex: 'broken', completedStepIds: null, dismissed: 'yes' } };
    const raw = JSON.stringify(stored); localStorage.setItem(key, raw); a.doc.__resetInMemoryStateForTest();
    expect(a.doc.getWalkthroughProgress('welcome')).toBeUndefined();
    expect(a.api.projectFirstUse({ roots: 0, searchable: 5, helpSearchable: 5, waiting: 0, reading: false, aiActive: false }, undefined, a.doc.getWalkthroughProgress('welcome')).visible).toBe(true);
    expect(localStorage.getItem(key)).toBe(raw);
  });
});

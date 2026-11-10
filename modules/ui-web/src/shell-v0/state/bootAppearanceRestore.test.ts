// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { restoreAppearanceOnBoot, applyTheme } from './themeState.js';
import { restoreActivePresentationOnBoot, applyPresentation } from './presentationState.js';
import { __resetUserStateForTest, __resetInMemoryStateForTest, getDocument } from './UserStateDocument.js';
import { __resetPresentationForTest } from './presentationRuntime.js';

beforeEach(() => { localStorage.clear(); __resetUserStateForTest(); __resetPresentationForTest(); });
afterEach(() => { localStorage.clear(); __resetUserStateForTest(); __resetPresentationForTest(); document.getElementById('jf-active-theme')?.remove(); });
it('SA5 successful restore reports success', async () => {
  const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ui: { theme: 'light' } }) });
  expect(await restoreAppearanceOnBoot(fetcher)).toBe(true);
});
it('SA5 failed settings restore reports failure', async () => {
  expect(await restoreAppearanceOnBoot(vi.fn().mockRejectedValue(new Error('offline')))).toBe(false);
});
it('SA5 failed palette restore reports failure even when settings succeeded', async () => {
  applyTheme('missing.theme', ':root {}');
  const fetcher = vi.fn(async (url: string | URL | Request) => String(url) === '/api/settings/v2'
    ? { ok: true, json: async () => ({ ui: { theme: 'light' } }) } : { ok: false });
  expect(await restoreAppearanceOnBoot(fetcher as unknown as typeof fetch)).toBe(false);
});
it('SA16 SA18 presentation fallback preserves newer-schema bytes on repeated restores', () => {
  localStorage.setItem('justsearch.userState.v2', '{ "version": 999, "future": true }\n');
  const before = localStorage.getItem('justsearch.userState.v2');
  for (let i = 0; i < 3; i++) {
    __resetInMemoryStateForTest();
    restoreActivePresentationOnBoot();
    expect(localStorage.getItem('justsearch.userState.v2')).toBe(before);
  }
});
it('SA18 legacy migration and consecutive presentation history de-duplication survive reload', () => {
  localStorage.setItem('justsearch.activeTheme', 'core.nord');
  __resetInMemoryStateForTest();
  expect(getDocument().activeThemeId).toBe('core.nord');
  applyPresentation({ schemaVersion: 1, id: 'core.declared', displayName: 'Core' });
  restoreActivePresentationOnBoot();
  const history = getDocument().presentationHistory;
  __resetInMemoryStateForTest(); restoreActivePresentationOnBoot();
  expect(getDocument().presentationHistory).toEqual(history);
});

it('SA5 incomplete settings observation cannot authorize a boot cache write', async () => {
  const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ui: {} }) });
  expect(await restoreAppearanceOnBoot(fetcher)).toBe(false);
});
it('SA5 a newer-schema appearance cannot authorize a defaults cache write', async () => {
  localStorage.setItem('justsearch.userState.v2', '{"version":999}');
  __resetInMemoryStateForTest();
  const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ui: { theme: 'light' } }) });
  expect(await restoreAppearanceOnBoot(fetcher)).toBe(false);
});

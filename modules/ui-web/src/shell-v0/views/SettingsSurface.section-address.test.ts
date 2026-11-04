// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Shell slice 2 (FR3 / C9) — the Settings section is part of the address. Settings reports the
 * section it shows; the frame writes it into the address (Shell.navigation.test.ts pins that half).
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { requestMemberTab } from '../router/memberTabIntent.js';
import { __resetUiModeForTest } from '../state/uiModeState.js';

interface SettingsHost extends HTMLElement { host_?: unknown; activeCategory: string; updateComplete: Promise<unknown> }

beforeEach(() => {
  __resetUiModeForTest();
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ui: { mode: 'simple' }, llm: {} }),
    { status: 200, headers: { 'content-type': 'application/json' } })));
});
afterEach(() => { document.body.innerHTML = ''; __resetUiModeForTest(); vi.unstubAllGlobals(); });

it('reports each section it shows — on mount and when a section is requested — for the address', async () => {
  const sections: string[] = [];
  document.addEventListener('settings-section-change', e => sections.push((e as CustomEvent<{ id: string }>).detail.id));
  const el = document.createElement('jf-settings-surface') as SettingsHost;
  el.host_ = createMockHostApi({ data: { fetch: () => Promise.resolve(new Response(JSON.stringify({ ui: { mode: 'simple' }, llm: {} }),
    { status: 200, headers: { 'content-type': 'application/json' } })) } });
  document.body.appendChild(el);
  await el.updateComplete;
  expect(sections).toEqual([el.activeCategory]);
  // A section address (deep link, reload, Back/Forward) arrives as a request for that section.
  requestMemberTab('core.settings-surface', 'privacy-trust');
  await el.updateComplete;
  expect(el.activeCategory).toBe('privacy-trust');
  expect(sections.at(-1)).toBe('privacy-trust');
  // The event leaves the surface's shadow tree, so the frame hears it.
  expect(sections).toHaveLength(2);
});

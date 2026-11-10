// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';
import type { SettingsNav } from '../components/SettingsNav.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetUserStateForTest, __resetInMemoryStateForTest, __getLastPersistError, getDocument, mutateDocument, createProfile, setActiveProfileId } from '../state/UserStateDocument.js';
import { getViewerAudience, setViewerAudience } from '../state/viewerAudienceState.js';
import { requestMemberTab } from '../router/memberTabIntent.js';
import { __seedForTest as seedWords, __resetForTest as resetWords } from '../../i18n/resourceCatalog.js';
import type { Audience } from '../../api/types/registry.js';

const KEY = 'justsearch.userState.v2';
const audiences: Audience[] = ['USER', 'OPERATOR', 'DEVELOPER', 'AGENT'];
const settle = async (el: SettingsSurface) => {
  await el.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
  await nav(el).updateComplete;
};
const nav = (el: SettingsSurface) => el.shadowRoot!.querySelector<SettingsNav>('jf-settings-nav')!;
const toggle = (el: SettingsSurface) => el.shadowRoot!.querySelector<HTMLInputElement>('[role="switch"]');
const row = (el: SettingsSurface, key: string) => el.shadowRoot!.querySelector<HTMLElement>(`[data-settings-anchor="${key}"]`);
async function mount(): Promise<SettingsSurface> {
  const el = document.createElement('jf-settings-surface') as SettingsSurface;
  el.host_ = createMockHostApi({ data: { fetch: async () => new Response('{"ui":{}}') } });
  document.body.append(el);
  requestMemberTab('core.settings-surface', 'advanced');
  await settle(el);
  return el;
}
async function choose(el: SettingsSurface, value: boolean) {
  expect(toggle(el), 'Show developer tools is always reachable').not.toBeNull();
  toggle(el)!.checked = value;
  toggle(el)!.dispatchEvent(new Event('change', { bubbles: true }));
  await settle(el);
}
beforeEach(() => {
  __resetUserStateForTest();
  seedWords({ 'settings.section.view-tier': 'View tier', 'settings.section.workspace-profiles': 'Workspace profiles', 'settings.section.show-developer-tools': 'Show developer tools' });
});
afterEach(() => { document.body.replaceChildren(); __resetUserStateForTest(); resetWords(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it.each(audiences)('SD1 SD2 untouched %s defaults without saving; toggles write only the current profile field', async audience => {
  setViewerAudience(audience);
  createProfile('other', 'Other');
  const before = JSON.parse(localStorage.getItem(KEY)!);
  const el = await mount();
  expect(toggle(el)).not.toBeNull();
  expect(toggle(el)!.checked).toBe(audience === 'DEVELOPER');
  expect(Boolean(row(el, 'view-tier'))).toBe(audience === 'DEVELOPER');
  expect(localStorage.getItem(KEY)).toBe(JSON.stringify(before));
  expect(getDocument().showDeveloperTools).toBeUndefined();
  for (const value of [true, false]) {
    await choose(el, value);
    expect(getViewerAudience()).toBe(audience);
    expect(row(el, 'view-tier') !== null).toBe(value);
    expect(row(el, 'workspace-profiles') !== null).toBe(value && audience === 'DEVELOPER');
    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ ...before, profiles: { ...before.profiles, default: { ...before.profiles.default, showDeveloperTools: value } } });
  }
});

it.each([undefined, true, false])('SD2 amended audience matrix retains explicit %s and never materializes absence', async value => {
  if (value !== undefined) mutateDocument(doc => ({ ...doc, showDeveloperTools: value }));
  const el = await mount();
  expect(toggle(el)).not.toBeNull();
  for (const audience of audiences) {
    setViewerAudience(audience); await settle(el);
    expect(toggle(el)!.checked).toBe(value ?? audience === 'DEVELOPER');
    expect(getDocument().showDeveloperTools).toBe(value);
    const stored = JSON.parse(localStorage.getItem(KEY)!).profiles.default;
    if (value === undefined) expect(stored).not.toHaveProperty('showDeveloperTools');
    else expect(stored.showDeveloperTools).toBe(value);
  }
});

it.each(audiences.flatMap(audience => [undefined, true, false].map(value => ({ audience, value }))))('SD3 amended A to B to A at $audience with $value, equal effective values and reload isolates profiles', async ({ audience, value }) => {
  setViewerAudience(audience);
  if (value !== undefined) mutateDocument(doc => ({ ...doc, showDeveloperTools: value }));
  createProfile('b', 'B');
  setActiveProfileId('b'); setViewerAudience(audience); setActiveProfileId('default');
  const el = await mount();
  expect(toggle(el)).not.toBeNull();
  const a = value ?? audience === 'DEVELOPER';
  setActiveProfileId('b'); await settle(el);
  expect(toggle(el)!.checked).toBe(a);
  await choose(el, !a);
  setActiveProfileId('default'); await settle(el);
  expect(toggle(el)!.checked).toBe(a);
  expect(getDocument().showDeveloperTools).toBe(value);
  setActiveProfileId('b'); await settle(el);
  expect(toggle(el)!.checked).toBe(!a);
  el.remove(); __resetInMemoryStateForTest();
  const reloaded = await mount();
  expect(toggle(reloaded)!.checked).toBe(!a);
  setActiveProfileId('default'); await settle(reloaded);
  expect(toggle(reloaded)!.checked).toBe(a);
});

it('SD3 same-profile storage adoption updates the open switch, content, unchanged search and anchors', async () => {
  const el = await mount();
  expect(toggle(el)).not.toBeNull();
  nav(el).query = 'View tier'; await nav(el).updateComplete;
  expect(nav(el).shadowRoot!.querySelector('.search-result-row')).toBeNull();
  const saved = JSON.parse(localStorage.getItem(KEY) ?? JSON.stringify({ version: 2, activeProfileId: 'default', profiles: getDocument().profiles }));
  for (const value of [true, false]) {
    saved.profiles.default.showDeveloperTools = value;
    localStorage.setItem(KEY, JSON.stringify(saved));
    window.dispatchEvent(new StorageEvent('storage', { key: KEY, storageArea: localStorage }));
    await settle(el);
    expect(toggle(el)!.checked).toBe(value);
    expect(Boolean(row(el, 'view-tier'))).toBe(value);
    expect(Boolean(nav(el).shadowRoot!.querySelector('.search-result-row'))).toBe(value);
    expect(nav(el).query).toBe('View tier');
  }
  nav(el).query = ''; await nav(el).updateComplete;
  expect(nav(el).shadowRoot!.querySelector('[data-settings-anchor="view-tier"]')).toBeNull();
});

it.each(['content', 'navigation', 'search'].flatMap(location => ['audience', 'tools'].map(cause => ({ location, cause }))))('SD3 SG6 disappearing focused $location row on $cause change moves focus to the switch', async ({ location, cause }) => {
  setViewerAudience('DEVELOPER');
  const el = await mount();
  expect(toggle(el)).not.toBeNull();
  let target: HTMLElement | null;
  if (location === 'search') { nav(el).query = 'Workspace profiles'; await nav(el).updateComplete; }
  target = location === 'content' ? row(el, 'workspace-profiles')
    : nav(el).shadowRoot!.querySelector<HTMLElement>(location === 'search' ? '.search-result-row' : '[data-settings-anchor="workspace-profiles"]');
  expect(target).not.toBeNull(); target!.focus();
  if (cause === 'audience') setViewerAudience('USER');
  else mutateDocument(doc => ({ ...doc, showDeveloperTools: false }));
  await settle(el);
  expect(el.shadowRoot!.activeElement).toBe(toggle(el));
  expect(row(el, 'workspace-profiles')).toBeNull();
});

it.each(['pending', 'live'])('SD3 SG6 %s hidden links explain the correct gate and never enable tools', async mode => {
  let el: SettingsSurface;
  if (mode === 'pending') {
    requestMemberTab('core.settings-surface', 'developer', 'view-tier');
    el = document.createElement('jf-settings-surface') as SettingsSurface;
    el.host_ = createMockHostApi({ data: { fetch: async () => new Response('{"ui":{}}') } });
    document.body.append(el); await settle(el);
  } else { el = await mount(); requestMemberTab('core.settings-surface', 'developer', 'view-tier'); await settle(el); }
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('row appears when developer tools are shown');
  expect(el.shadowRoot!.activeElement).toBe(toggle(el));
  expect(toggle(el)!.checked).toBe(false);
  expect(getDocument().showDeveloperTools).toBeUndefined();
  await choose(el, true);
  expect(el.shadowRoot!.querySelector('[role="status"]')).toBeNull();
  await choose(el, false);
  requestMemberTab('core.settings-surface', 'developer', 'workspace-profiles'); await settle(el);
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('hidden by your view tier');
  expect(el.shadowRoot!.textContent).not.toContain('row appears when');
  expect(el.shadowRoot!.activeElement).toBe(toggle(el));
  setViewerAudience('DEVELOPER'); await settle(el);
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('row appears when developer tools are shown');
  setViewerAudience('USER'); await choose(el, true);
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('hidden by your view tier');
  requestMemberTab('core.settings-surface', 'plugins', 'plugin-permissions'); await settle(el);
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('hidden by your view tier');
  expect(toggle(el)!.checked).toBe(true);
  setViewerAudience('OPERATOR'); await settle(el);
  expect(el.shadowRoot!.querySelector('[role="status"]')).toBeNull();
});

it.each(audiences)('SD4 plugin permissions at %s stay independent of tools', async audience => {
  setViewerAudience(audience);
  const el = await mount();
  await choose(el, false);
  requestMemberTab('core.settings-surface', 'security-privacy'); await settle(el);
  expect(Boolean(row(el, 'plugin-permissions'))).toBe(audience !== 'USER');
});

it('SD2 failed preference save keeps the session value, records the error and reload uses stored value', async () => {
  const el = await mount();
  await choose(el, false);
  const saved = localStorage.getItem(KEY);
  const failure = vi.spyOn(localStorage, 'setItem').mockImplementation(() => { throw new Error('Storage is full'); });
  await choose(el, true);
  expect(toggle(el)!.checked).toBe(true);
  expect(__getLastPersistError()).toBeInstanceOf(Error);
  expect(localStorage.getItem(KEY)).toBe(saved);
  failure.mockRestore(); el.remove(); __resetInMemoryStateForTest();
  const reloaded = await mount();
  expect(toggle(reloaded)!.checked).toBe(false);
});

it('SD6 developer tools stay usable in backend Session-only mode', async () => {
  const el = await mount();
  el.readOnly = true; await settle(el);
  await choose(el, true);
  expect(toggle(el)!.disabled).toBe(false);
  expect(getDocument().showDeveloperTools).toBe(true);
  expect(row(el, 'view-tier')).not.toBeNull();
});


it.each(audiences)('SD4 Health keeps the API endpoint readout at %s with developer tools off', async audience => {
  setViewerAudience(audience);
  mutateDocument(doc => ({ ...doc, showDeveloperTools: false }));
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } })));
  const health = document.createElement('jf-health-surface') as HealthSurface;
  health.apiBase = 'http://127.0.0.1:41000';
  document.body.append(health); await health.updateComplete;
  expect(health.shadowRoot!.textContent).toContain('API endpoint');
  expect(health.shadowRoot!.textContent).toContain('http://127.0.0.1:41000');
});
